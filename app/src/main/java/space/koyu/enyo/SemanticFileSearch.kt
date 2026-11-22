package space.koyu.enyo

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private val semanticEnabled = booleanPreferencesKey("semantic_files")
internal val semanticRevision = intPreferencesKey("semantic_revision")
private const val periodicIndex = "periodic_file_index"
private val fileIndexLock = Mutex()
private const val immediateIndex = "initial_file_index"

private const val clip = "Xenova/clip-vit-base-patch32/resolve/6b7c523703ae26e39be4b03774966f8571c7f523/"
private const val miniLm = "sentence-transformers/all-MiniLM-L6-v2/resolve/1110a243fdf4706b3f48f1d95db1a4f5529b4d41/"
private val semanticAssets = listOf(
    ModelAsset(clip, "onnx/text_model_quantized.onnx", "text.onnx", 64504507, "73baab855d406190da9faa498cfedf65f15cf309f4cc7385b7b032e6d08e5c3a"),
    ModelAsset(clip, "onnx/vision_model_quantized.onnx", "vision.onnx", 89117001, "583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299"),
    ModelAsset(clip, "vocab.json", "vocab.json", 862328, "5047b556ce86ccaf6aa22b3ffccfc52d391ea4accdab9c2f2407da5b742d4363"),
    ModelAsset(clip, "merges.txt", "merges.txt", 524619, "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a"),
    ModelAsset(miniLm, "onnx/model_qint8_arm64.onnx", "doc.onnx", 23026053, "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474"),
    ModelAsset(miniLm, "vocab.txt", "vocab.txt", 231508, "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3")
)

private fun modelDirectory(context: Context) = File(context.noBackupFilesDir, "file_search_models")

internal suspend fun installSemanticModels(context: Context, progress: suspend (Int) -> Unit) {
    downloadModels(modelDirectory(context), semanticAssets, progress)
    context.searchPreferences.edit { it[semanticEnabled] = true }
    scheduleFileIndex(context)
}

@Composable
internal fun semanticSearchEnabled(): Boolean {
    val store = LocalContext.current.searchPreferences
    return remember(store) { store.data.map { it[semanticEnabled] == true } }.collectAsState(false).value
}

@Composable
internal fun SemanticSearchPreference() {
    val context = LocalContext.current
    if (!remember(context) { supportsLocalAssistant(context) }) return
    val source = fileSearchSource()
    val enabled = semanticSearchEnabled()
    val scope = rememberCoroutineScope()
    val store = context.searchPreferences
    val download = modelDownloadState(ModelDownloadKind.Search)
    if (!enabled && !download.active) {
        SettingsAction(stringResource(R.string.search_semantic_enable), Glyph.Sparkles,
            enabled = source != null, summary = stringResource(R.string.search_semantic_summary), run = download.start)
    } else if (enabled) {
        SettingsAction(stringResource(R.string.search_semantic_refresh), Glyph.Refresh, enabled = source != null,
            summary = stringResource(R.string.search_semantic_schedule)) { scheduleFileIndex(context, replace = true) }
        SettingsAction(stringResource(R.string.search_semantic_disable), Glyph.Trash) {
            scope.launch {
                store.edit { it.remove(semanticEnabled) }
                WorkManager.getInstance(context).apply {
                    cancelUniqueWork(periodicIndex)
                    cancelUniqueWork(immediateIndex)
                }
                withContext(Dispatchers.IO) {
                    localInference.withLock {
                        modelDirectory(context).deleteRecursively()
                        MessageArchive.get(context).read { it.execSQL("DELETE FROM semantic_files") }
                    }
                }
            }
        }
    }
    ModelDownloadStatus(ModelDownloadKind.Search, download)
}

internal fun scheduleFileIndex(context: Context, replace: Boolean = false) {
    val constraints = Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build()
    WorkManager.getInstance(context).apply {
        enqueueUniqueWork(immediateIndex, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SemanticFileIndexWorker>().setConstraints(constraints).build())
        enqueueUniquePeriodicWork(periodicIndex, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SemanticFileIndexWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints).setInitialDelay(1, TimeUnit.DAYS).build())
    }
}

internal object SemanticFiles {
    fun createSchema(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE semantic_files (
            uri TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL, source TEXT NOT NULL,
            modified INTEGER NOT NULL, size INTEGER NOT NULL, kind INTEGER NOT NULL,
            vectors BLOB, scan TEXT NOT NULL, PRIMARY KEY(source, uri)
        )""")
        db.execSQL("CREATE INDEX semantic_files_source ON semantic_files(source, kind)")
    }
}

class SemanticFileIndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val store = context.searchPreferences
        val preferences = store.data.first()
        if (preferences[semanticEnabled] != true) return@withContext Result.success()
        val source = currentSearchSource(context, preferences[searchFolder]) ?: return@withContext Result.success()
        if (!canRunLocalModels(context)) return@withContext Result.retry()
        val archive = MessageArchive.get(context)
        val scan = UUID.randomUUID().toString()
        try {
            fileIndexLock.withLock {
                SearchEmbeddings(modelDirectory(context)).use { encoder ->
                    visitSearchFiles(context, source) { file ->
                        val kind = if (file.mime.startsWith("image/")) EmbeddingKind.Image else EmbeddingKind.Document
                        val unchanged = archive.read { db ->
                            db.query("SELECT modified, size FROM semantic_files WHERE source = ? AND uri = ?",
                                arrayOf(source, file.uri.toString())).use { cursor ->
                                file.modified > 0 && cursor.moveToFirst() &&
                                    cursor.getLong(0) == file.modified && cursor.getLong(1) == file.size
                            }
                        } == true
                        if (unchanged) {
                            archive.read { it.execSQL("UPDATE semantic_files SET scan = ? WHERE source = ? AND uri = ?",
                                arrayOf(scan, source, file.uri.toString())) }
                        } else {
                            val vectors = localInference.withLock {
                                when (kind) {
                                    EmbeddingKind.Image -> try {
                                        listOf(encoder.image(context.contentResolver, file))
                                    } catch (_: java.io.IOException) {
                                        emptyList()
                                    } catch (_: IllegalStateException) {
                                        emptyList()
                                    }
                                    EmbeddingKind.Document -> {
                                        val text = readSearchText(context.contentResolver, file)
                                        if (text.isBlank()) emptyList() else {
                                            text.take(8000).chunked(500).map { chunk ->
                                                coroutineContext.ensureActive()
                                                encoder.text(file.name + " " + chunk, kind)
                                            }
                                        }
                                    }
                                }
                            }
                            val blob = if (vectors.isEmpty()) null else ByteBuffer
                                .allocate(vectors.sumOf { it.size } * Float.SIZE_BYTES).apply {
                                    vectors.forEach { vector -> vector.forEach { putFloat(it) } }
                                }.array()
                            archive.read { it.execSQL("INSERT OR REPLACE INTO semantic_files VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                arrayOf(file.uri.toString(), file.name, file.mime, source,
                                    file.modified, file.size, kind.ordinal, blob, scan)) }
                        }
                        true
                    }
                }
                archive.read { it.execSQL("DELETE FROM semantic_files WHERE scan != ?", arrayOf(scan)) }
            }
            store.edit { it[semanticRevision] = (it[semanticRevision] ?: 0) + 1 }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (_: LinkageError) {
            Result.failure()
        }
    }
}

internal suspend fun semanticFileMatches(context: Context, source: String, query: String): List<FileHit> =
    withContext(Dispatchers.IO) {
        // File indexing only runs while idle. Skip semantic ranking if chat or indexing is still draining.
        if (!canRunLocalModels(context) || !localInference.tryLock()) return@withContext emptyList()
        try {
            val archive = MessageArchive.get(context)
            SearchEmbeddings(modelDirectory(context)).use { encoder ->
                EmbeddingKind.entries.flatMap { kind ->
                    val exists = archive.read { db ->
                        db.query("SELECT 1 FROM semantic_files WHERE source = ? AND kind = ? AND vectors IS NOT NULL LIMIT 1",
                            arrayOf(source, kind.ordinal)).use { it.moveToFirst() }
                    } == true
                    if (!exists) emptyList() else {
                        val match = encoder.text(query.take(360), kind)
                        val limit = 4
                        val ranked = java.util.PriorityQueue<Pair<Float, FileHit>>(compareBy { it.first })
                        archive.read { db ->
                            db.query("SELECT uri, name, mime, modified, size, vectors FROM semantic_files WHERE source = ? AND kind = ? AND vectors IS NOT NULL",
                                arrayOf(source, kind.ordinal)).use { cursor ->
                                while (cursor.moveToNext()) {
                                    coroutineContext.ensureActive()
                                    val vectors = ByteBuffer.wrap(cursor.getBlob(5)).asFloatBuffer()
                                    var best = -1f
                                    while (vectors.remaining() >= match.size) {
                                        var score = 0f
                                        for (weight in match) score += weight * vectors.get()
                                        best = maxOf(best, score)
                                    }
                                    val threshold = if (kind == EmbeddingKind.Image) 0.18f else 0.30f
                                    if (best >= threshold && (ranked.size < limit || best > ranked.peek()!!.first)) {
                                        if (ranked.size == limit) ranked.remove()
                                        ranked.add(best to FileHit(SearchFile(Uri.parse(cursor.getString(0)),
                                            cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getLong(4)), null))
                                    }
                                }
                            }
                        }
                        ranked.sortedByDescending { it.first }.map { it.second }
                    }
                }
            }
        } finally {
            localInference.unlock()
        }
    }
