package space.koyu.enyo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

internal enum class ModelDownloadKind(val notificationId: Int, val title: Int) {
    Chat(17102, R.string.local_ai_downloading),
    Search(17103, R.string.search_semantic_downloading);

    val workName get() = "download_model_${name.lowercase()}"
}

internal data class ModelAsset(val repository: String, val path: String, val name: String, val bytes: Long, val sha256: String)
private val downloadLocks = ModelDownloadKind.entries.associateWith { Mutex() }
private const val downloadChannel = "model_download"
private const val modelKind = "model_kind"
private const val modelOption = "model_option"
private const val progressKey = "download_progress"
private const val progressMax = 10_000
private const val progressIntervalMillis = 2_000L

private fun downloadPercent(progress: Int): Double = progress * 100.0 / progressMax

internal fun StatusBarNotification.isModelDownload(packageName: String): Boolean =
    this.packageName == packageName && notification.channelId == downloadChannel

internal fun StatusBarNotification.modelDownloadProgress(packageName: String): Float? {
    if (!isModelDownload(packageName)) return null
    val max = notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX)
    return if (max > 0) (notification.extras.getInt(Notification.EXTRA_PROGRESS).toFloat() / max)
        .coerceIn(0f, 1f) else null
}

internal class ModelDownloadState(
    val active: Boolean,
    val failed: Boolean,
    val progress: Int?,
    val start: () -> Unit,
    val startOption: (String) -> Unit,
    val cancel: () -> Unit
)

@Composable
internal fun modelDownloadState(kind: ModelDownloadKind): ModelDownloadState {
    val context = LocalContext.current
    val manager = remember(context) { WorkManager.getInstance(context) }
    val work by remember(manager, kind) { manager.getWorkInfosForUniqueWorkFlow(kind.workName) }.collectAsState(emptyList())
    val current = work.firstOrNull { !it.state.isFinished } ?: work.firstOrNull()
    val active = current?.state?.isFinished == false
    val failed = current?.state == WorkInfo.State.FAILED
    val progress = current?.progress?.getInt(progressKey, -1)?.takeIf { it >= 0 }
    var option by remember(kind) { mutableStateOf<String?>(null) }
    val enqueue: () -> Unit = {
        manager.enqueueUniqueWork(kind.workName, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(modelKind to kind.name, modelOption to option)).build())
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { enqueue() }
    val start: (String?) -> Unit = { selected ->
        option = selected
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else enqueue()
    }
    return ModelDownloadState(active, failed, progress, start = { start(null) }, startOption = { start(it) },
        cancel = { manager.cancelUniqueWork(kind.workName) })
}

@Composable
internal fun ModelDownloadStatus(kind: ModelDownloadKind, state: ModelDownloadState) {
    if (state.active) SettingsAction(stringResource(R.string.model_download_cancel), Glyph.Close,
        summary = state.progress?.let { stringResource(R.string.model_download_percent, downloadPercent(it)) }
            ?: stringResource(kind.title), run = state.cancel)
    else if (state.failed) ListNote(stringResource(R.string.model_download_failed))
}

class ModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val kind = ModelDownloadKind.entries.firstOrNull { it.name == inputData.getString(modelKind) }
            ?: return Result.failure()
        return downloadLocks.getValue(kind).withLock { download(kind) }
    }

    private suspend fun download(kind: ModelDownloadKind): Result {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(id)
        val content = PendingIntent.getActivity(context, kind.notificationId, Intent(context, EnyoActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun notification(progress: Int) = NotificationCompat.Builder(context, downloadChannel)
            .setSmallIcon(R.drawable.tabler_cloud_upload)
            .setContentTitle(context.getString(kind.title))
            .setContentText(context.getString(R.string.model_download_percent, downloadPercent(progress)))
            .setProgress(progressMax, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(content)
            .addAction(R.drawable.tabler_x, context.getString(R.string.cancel), cancel)
            .build()
        manager.createNotificationChannel(NotificationChannel(downloadChannel,
            context.getString(R.string.model_download_channel), NotificationManager.IMPORTANCE_LOW))
        var lastUpdate: Long? = null
        return try {
            try {
                setForeground(ForegroundInfo(kind.notificationId, notification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalStateException) {
                // A queued job can start after the launcher leaves the foreground.
            }
            val progress: suspend (Int) -> Unit = { downloaded ->
                val now = SystemClock.elapsedRealtime()
                val previous = lastUpdate
                if (previous == null || now - previous >= progressIntervalMillis || downloaded == progressMax) {
                    lastUpdate = now
                    setProgress(workDataOf(progressKey to downloaded))
                    runCatching { manager.notify(kind.notificationId, notification(downloaded)) }
                }
            }
            progress(0)
            when (kind) {
                ModelDownloadKind.Chat -> installAssistant(context, inputData.getString(modelOption), progress)
                ModelDownloadKind.Search -> installSemanticModels(context, progress)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount == 0) Result.retry() else Result.failure()
        } finally {
            manager.cancel(kind.notificationId)
        }
    }
}

internal suspend fun downloadModels(folder: File, assets: List<ModelAsset>, report: suspend (Int) -> Unit) =
    withContext(Dispatchers.IO) {
        check(folder.isDirectory || folder.mkdirs())
        val total = assets.sumOf { it.bytes }
        var completed = 0L
        for (asset in assets) {
            ensureActive()
            val file = File(folder, asset.name)
            if (file.length() != asset.bytes) {
                val atomic = AtomicFile(file)
                val connection = URI("https://huggingface.co/${asset.repository}${asset.path}").toURL()
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 20_000
                connection.readTimeout = 20_000
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    connection.inputStream.use { input ->
                        val output = atomic.startWrite()
                        try {
                            val buffer = ByteArray(256 * 1024)
                            var size = 0L
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count
                                check(size <= asset.bytes)
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                report(((completed + size) * progressMax / total).toInt().coerceAtMost(progressMax - 1))
                            }
                            check(size == asset.bytes && digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256)
                            atomic.finishWrite(output)
                        } catch (error: Throwable) {
                            atomic.failWrite(output)
                            throw error
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            completed += asset.bytes
        }
        report(progressMax)
    }
