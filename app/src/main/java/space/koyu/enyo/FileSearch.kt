package space.koyu.enyo

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import android.provider.DocumentsContract.Document
import android.util.LruCache
import android.util.Xml
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

class SearchFileProvider : FileProvider()

internal const val sharedStorage = "shared_storage"

@Composable
internal fun fileSearchSource(): String? {
    val context = LocalContext.current
    val store = context.searchPreferences
    val selected by remember(store) { store.data.map { it[searchFolder] } }.collectAsState(null)
    val foreground = foregroundCycle()
    return remember(foreground, selected) { currentSearchSource(context, selected) }
}

internal fun currentSearchSource(context: Context, selected: String?): String? {
    if (Environment.isExternalStorageManager()) return sharedStorage
    return selected?.takeIf { saved ->
        context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == saved }
    }
}

internal val searchFolder = stringPreferencesKey("file_search_folder")
private const val textLimit = 128 * 1024
private const val officeLimit = 24 * 1024 * 1024

internal data class SearchFile(val uri: Uri, val name: String, val mime: String, val modified: Long, val size: Long) {
    fun viewIntent() = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

internal data class FileHit(val file: SearchFile, val excerpt: String?)

// This cache lives in memory and is keyed by the provider's file metadata.
private val documentText = object : LruCache<SearchFile, String>(8 * 1024 * 1024) {
    override fun sizeOf(key: SearchFile, value: String) = value.length * 2 + 256
}

@Composable
internal fun SearchFolderPreference() {
    val context = LocalContext.current
    val store = context.searchPreferences
    val current by remember(store) { store.data.map { it[searchFolder] } }.collectAsState(null)
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            val resolver = context.contentResolver
            val granted = runCatching {
                resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            failed = !granted
            if (granted) {
                val saved = store.edit { it[searchFolder] = tree.toString() }
                current?.takeIf { it != saved[searchFolder] }?.let { releaseFolder(resolver, it) }
                documentText.evictAll()
            }
        }
    }
    val foreground = foregroundCycle()
    val allFiles = remember(foreground) { Environment.isExternalStorageManager() }
    SettingsAction(stringResource(R.string.search_all_files), Glyph.Folder,
        summary = stringResource(if (allFiles) R.string.search_all_files_granted else R.string.search_all_files_summary)) {
        val app = Uri.fromParts("package", context.packageName, null)
        if (!context.launchSafely(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, app))) {
            failed = !context.launchSafely(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
    SettingsAction(stringResource(R.string.search_choose_folder), Glyph.Folder,
        summary = current?.let { runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)).substringAfterLast('/') }.getOrNull() }) {
        picker.launch(current?.let(Uri::parse))
    }
    if (current != null) SettingsAction(stringResource(R.string.search_remove_folder), Glyph.Unlink) {
        scope.launch {
            val previous = current
            store.edit { it.remove(searchFolder) }
            previous?.let { releaseFolder(context.contentResolver, it) }
            documentText.evictAll()
        }
    }
    if (failed) ListNote(stringResource(R.string.action_unavailable))
}

private fun releaseFolder(resolver: ContentResolver, tree: String) {
    runCatching { resolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

internal suspend fun visitSearchFiles(context: Context, folder: String, visit: suspend (SearchFile) -> Boolean) {
    if (folder == sharedStorage) {
        visitSharedStorage(context, visit)
        return
    }
    val tree = Uri.parse(folder)
    val resolver = context.contentResolver
    val directories = ArrayDeque<String>()
    val visited = mutableSetOf<String>()
    val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)
    directories.add(DocumentsContract.getTreeDocumentId(tree))
    while (directories.isNotEmpty()) {
        coroutineContext.ensureActive()
        val id = directories.removeFirst()
        if (!visited.add(id)) continue
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        // Close the cursor before reading file contents through the same provider.
        val files = resolver.query(children, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val child = cursor.getString(0) ?: continue
                    val mime = cursor.getString(2) ?: "application/octet-stream"
                    if (mime == Document.MIME_TYPE_DIR) directories.add(child)
                    else add(SearchFile(DocumentsContract.buildDocumentUriUsingTree(tree, child),
                        cursor.getString(1) ?: child, mime, cursor.getLong(3), cursor.getLong(4)))
                }
            }
        }.orEmpty()
        for (file in files) {
            coroutineContext.ensureActive()
            if (!visit(file)) return
        }
    }
}

private suspend fun visitSharedStorage(context: Context, visit: suspend (SearchFile) -> Boolean) {
    if (!Environment.isExternalStorageManager()) return
    val volumes = context.getSystemService(StorageManager::class.java).storageVolumes
        .mapNotNull { it.directory?.canonicalFile }.distinct()
    for (root in volumes) {
        val pending = ArrayDeque<File>().apply { add(root) }
        val prefix = root.path + File.separator
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val directory = pending.removeLast()
            for (file in directory.listFiles().orEmpty()) {
                coroutineContext.ensureActive()
                if (file.isHidden || file.canonicalFile != file.absoluteFile || !file.path.startsWith(prefix)) continue
                if (file.isDirectory) {
                    if (file.path != prefix + "Android/data" && file.path != prefix + "Android/obb") pending.add(file)
                } else if (file.isFile && file.canRead()) {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".searchfiles", file)
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                        ?: "application/octet-stream"
                    if (!visit(SearchFile(uri, file.name, mime, file.lastModified(), file.length()))) return
                }
            }
        }
    }
}

internal suspend fun findFiles(
    context: Context,
    folder: String,
    query: String,
    report: suspend (List<FileHit>) -> Unit
) = withContext(Dispatchers.IO) {
    val names = mutableListOf<FileHit>()
    visitSearchFiles(context, folder) { file ->
        if (file.name.contains(query, ignoreCase = true)) names += FileHit(file, null)
        names.size < 10
    }
    report(names.toList())
    if (names.size < 10) {
        visitSearchFiles(context, folder) { file ->
            if (!file.name.contains(query, ignoreCase = true)) {
                val text = readSearchText(context.contentResolver, file)
                val index = text.indexOf(query, ignoreCase = true)
                if (index >= 0) {
                    names += FileHit(file, text.substring(
                        (index - 35).coerceAtLeast(0), (index + query.length + 70).coerceAtMost(text.length)
                    ).trim())
                    report(names.toList())
                }
            }
            names.size < 10
        }
    }
}

internal fun readSearchText(resolver: ContentResolver, file: SearchFile): String {
    documentText.get(file)?.let { return it }
    val extension = file.name.substringAfterLast('.', "").lowercase()
    val text = runCatching {
        when {
            extension == "pdf" && Build.VERSION.SDK_INT >= 35 -> readPdf(resolver, file.uri)
            extension in setOf("docx", "xlsx", "pptx", "odt", "ods", "odp") -> readOffice(resolver, file, extension)
            file.mime.startsWith("text/") || extension in setOf("txt", "md", "csv", "tsv", "log") ->
                resolver.openInputStream(file.uri)?.bufferedReader()?.use { reader ->
                    val chars = CharArray(textLimit)
                    var size = 0
                    while (size < chars.size) {
                        val count = reader.read(chars, size, chars.size - size)
                        if (count < 0) break
                        size += count
                    }
                    String(chars, 0, size)
                }.orEmpty()
            else -> ""
        }
    }.getOrDefault("").take(textLimit).replace(Regex("\\s+"), " ").trim()
    if (file.modified > 0 && text.isNotEmpty()) documentText.put(file, text)
    return text
}

@androidx.annotation.RequiresApi(35)
private fun readPdf(resolver: ContentResolver, uri: Uri): String {
    val descriptor = resolver.openFileDescriptor(uri, "r") ?: return ""
    val renderer = try { PdfRenderer(descriptor) } catch (error: Exception) {
        descriptor.close()
        throw error
    }
    return renderer.use { pdf ->
        buildString {
            for (index in 0 until minOf(pdf.pageCount, 200)) {
                pdf.openPage(index).use { page ->
                    for (piece in page.textContents) {
                        append(piece.text.take(textLimit - length)).append(' ')
                        if (length >= textLimit) break
                    }
                }
                if (length >= textLimit) break
            }
        }
    }
}

private fun readOffice(resolver: ContentResolver, file: SearchFile, extension: String): String {
    if (file.size > officeLimit) return ""
    val input = resolver.openInputStream(file.uri) ?: return ""
    return ZipInputStream(input.buffered()).use { zip ->
        val text = StringBuilder()
        val buffer = ByteArray(8192)
        var inflated = 0
        while (inflated < officeLimit && text.length < textLimit) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            val relevant = when (extension) {
                "docx" -> name == "word/document.xml"
                "xlsx" -> name == "xl/sharedStrings.xml" || (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
                "pptx" -> name.startsWith("ppt/slides/slide") && name.endsWith(".xml")
                else -> name == "content.xml"
            }
            val bytes = if (relevant) java.io.ByteArrayOutputStream() else null
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                inflated += count
                if (inflated > officeLimit) return@use text.toString()
                bytes?.write(buffer, 0, count)
            }
            if (bytes != null) {
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                parser.setInput(ByteArrayInputStream(bytes.toByteArray()), null)
                while (parser.eventType != XmlPullParser.END_DOCUMENT && text.length < textLimit) {
                    if (parser.eventType == XmlPullParser.TEXT) {
                        text.append(parser.text.take(textLimit - text.length)).append(' ')
                    }
                    parser.next()
                }
            }
        }
        text.toString()
    }
}
