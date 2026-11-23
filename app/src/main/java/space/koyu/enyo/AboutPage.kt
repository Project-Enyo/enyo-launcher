package space.koyu.enyo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A full screen page over the settings, with a title and a way back. */
@Composable
internal fun FullPage(title: String, back: () -> Unit, content: LazyListScope.() -> Unit) {
    val tokens = LocalTokens.current
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(tokens.settingsBackground).safeDrawingPadding()) {
            Box(Modifier.padding(horizontal = tokens.margin, vertical = tokens.gap)) { SettingsHeading(title, back) }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = tokens.margin, end = tokens.margin, bottom = tokens.margin),
                verticalArrangement = Arrangement.spacedBy(tokens.gap),
                content = content
            )
        }
    }
}

private const val repository = "https://github.com/Project-Enyo/enyo-launcher"

/** The details a bug report needs, as plain text to paste. */
private fun deviceInfo(context: Context): String {
    val metrics = context.resources.displayMetrics
    val chip = (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).trim()
    return "Enyo ${Updates.version(context)}\n" +
        "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
        "Chip: $chip\n" +
        "Screen: ${metrics.widthPixels} × ${metrics.heightPixels}, ${metrics.densityDpi} dpi\n" +
        "ABI: ${Build.SUPPORTED_ABIS.joinToString()}\n" +
        "Language: ${context.resources.configuration.locales[0]?.toLanguageTag().orEmpty()}"
}

/** The release notes of the installed version; the updater's last answer helps when GitHub limits requests. */
private suspend fun releaseNotes(context: Context): String = withContext(Dispatchers.IO) {
    val version = Updates.version(context).substringBefore('-')
    for (tag in listOf(version, "v$version")) {
        val connection = URL("https://api.github.com/repos/Project-Enyo/enyo-launcher/releases/tags/$tag").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Enyo-Android/$version")
            when (connection.responseCode) {
                200 -> return@withContext JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optString("body")
                404 -> continue
                403, 429 -> {
                    val cached = context.getSharedPreferences("updates", Context.MODE_PRIVATE).getString("json", null)
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (cached != null && cached.optString("tag_name").removePrefix("v") == version) return@withContext cached.optString("body")
                    throw java.io.IOException(context.getString(R.string.update_rate_limited))
                }
                else -> throw java.io.IOException("HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }
    context.getString(R.string.about_no_notes)
}

private fun size(file: File?): Long = when {
    file == null || !file.exists() -> 0L
    file.isFile -> file.length()
    else -> file.listFiles().orEmpty().sumOf { size(it) }
}

private data class Storage(val model: Long, val search: Long, val archive: Long, val cache: Long)

private data class Notice(val name: String, val license: String, val url: String)

/** What Enyo is built with, and the models it can download; each license was checked at its source. */
private val libraries = listOf(
    Notice("AndroidX and Jetpack Compose", "Apache License 2.0", "https://developer.android.com/jetpack/androidx"),
    Notice("Kotlin and kotlinx.coroutines", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    Notice("LiteRT-LM", "Apache License 2.0", "https://github.com/google-ai-edge/LiteRT-LM"),
    Notice("ONNX Runtime", "MIT License", "https://github.com/microsoft/onnxruntime"),
    Notice("SQLCipher for Android", "BSD 3-Clause License", "https://github.com/sqlcipher/sqlcipher-android"),
    Notice("commonmark-java", "BSD 2-Clause License", "https://github.com/commonmark/commonmark-java"),
    Notice("Shizuku API", "MIT License", "https://github.com/RikkaApps/Shizuku-API"),
    Notice("Tabler Icons", "MIT License", "https://github.com/tabler/tabler-icons")
)
private val models = listOf(
    Notice("Gemma 4", "Apache License 2.0", "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"),
    Notice("Qwen3 and Qwen3.5", "Apache License 2.0", "https://huggingface.co/litert-community/Qwen3.5-2B"),
    Notice("CLIP ViT-B/32", "MIT License", "https://huggingface.co/Xenova/clip-vit-base-patch32"),
    Notice("all-MiniLM-L6-v2", "Apache License 2.0", "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2")
)
/** The sounds of the first setup, both shared on Freesound as CC0. */
private val sounds = listOf(
    Notice("Seagull by henner1964", "CC0 1.0", "https://freesound.org/s/699979/"),
    Notice("Storm waves breaking on sandy beach in Bournemouth by chris_dagorne", "CC0 1.0", "https://freesound.org/s/426076/")
)

private enum class AboutSheet { Notes, Device, Licenses }

/** Settings → About: the version and updates, what is new, device details, storage, the source and licenses. */
@Composable
internal fun AboutPage(close: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<AboutSheet?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val storage by produceState<Storage?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            Storage(
                model = size(File(context.noBackupFilesDir, "assistant.litertlm")),
                search = size(File(context.noBackupFilesDir, "file_search_models")),
                archive = size(context.getDatabasePath("messages.db")) + size(File(context.getDatabasePath("messages.db").path + "-wal")),
                cache = size(context.cacheDir)
            )
        }
    }
    val icon = remember(context) {
        runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull()
    }
    FullPage(stringResource(R.string.about), close) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(vertical = tokens.padding), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(tokens.gap / 2)) {
                icon?.let { Image(it, null, Modifier.size(tokens.iconSize * 1.5f)) }
                Text("Enyo ${Updates.version(context)}", style = tokens.state)
                Text(stringResource(R.string.about_tagline), style = tokens.rowSummary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        item(key = "app") {
            SettingsPanel(stringResource(R.string.about_app)) {
                UpdateCheckRow()
                SettingsAction(stringResource(R.string.about_whats_new), Glyph.News) { sheet = AboutSheet.Notes }
                SettingsAction(stringResource(R.string.about_device), Glyph.Phone) { sheet = AboutSheet.Device }
            }
        }
        item(key = "storage") {
            SettingsPanel(stringResource(R.string.about_storage)) {
                val current = storage
                if (current == null) ListNote(stringResource(R.string.inbox_loading))
                else {
                    fun bytes(value: Long) = Formatter.formatShortFileSize(context, value)
                    ListRow(stringResource(R.string.about_storage_model), summary = bytes(current.model), leading = { EnyoGlyph(Glyph.Sparkles) })
                    ListRow(stringResource(R.string.about_storage_search), summary = bytes(current.search), leading = { EnyoGlyph(Glyph.Search) })
                    ListRow(stringResource(R.string.about_storage_archive), summary = bytes(current.archive), leading = { EnyoGlyph(Glyph.Messages) })
                    SettingsAction(stringResource(R.string.about_clear_cache, bytes(current.cache)), Glyph.Trash,
                        summary = stringResource(R.string.about_clear_cache_summary), enabled = current.cache > 0) {
                        scope.launch {
                            withContext(Dispatchers.IO) { context.cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() } }
                            refresh++
                        }
                    }
                }
            }
        }
        item(key = "project") {
            SettingsPanel(stringResource(R.string.about_project)) {
                SettingsLink(stringResource(R.string.about_source), "github.com/Project-Enyo/enyo-launcher", Glyph.External) { context.openWeb(repository) }
                SettingsLink(stringResource(R.string.about_report), stringResource(R.string.about_report_summary), Glyph.Message) {
                    val body = "**What happened?**\n\n\n**Steps to reproduce**\n\n\n**Device**\n```\n${deviceInfo(context)}\n```\n"
                    context.openWeb(Uri.parse("$repository/issues/new").buildUpon().appendQueryParameter("body", body).build().toString())
                }
                SettingsLink(stringResource(R.string.about_licenses), glyph = Glyph.Note) { sheet = AboutSheet.Licenses }
            }
        }
    }
    when (sheet) {
        AboutSheet.Notes -> EnyoSheet(stringResource(R.string.about_whats_new_in, Updates.version(context)), { sheet = null }) {
            val notes by produceState<Result<String>?>(null) { value = runCatching { releaseNotes(context) } }
            val current = notes
            Column(Modifier.heightIn(max = tokens.widgetPickerHeight).verticalScroll(rememberScrollState())) {
                when {
                    current == null -> Text(stringResource(R.string.inbox_loading), style = tokens.secondary)
                    current.isFailure -> Text(stringResource(R.string.about_notes_failed, current.exceptionOrNull()?.message.orEmpty()), style = tokens.secondary)
                    else -> ChatMarkdown(current.getOrThrow().ifBlank { stringResource(R.string.about_no_notes) }, tokens.secondary)
                }
            }
        }
        AboutSheet.Device -> EnyoSheet(stringResource(R.string.about_device), { sheet = null }) {
            val info = remember { deviceInfo(context) }
            Text(info, Modifier.fillMaxWidth().clip(RoundedCornerShape(tokens.radius / 3)).background(tokens.widgetTrack).padding(tokens.gap),
                style = tokens.secondary.copy(fontFamily = FontFamily.Monospace))
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                PillButton(stringResource(R.string.cancel)) { sheet = null }
                PillButton(stringResource(R.string.inbox_copy), filled = true) {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Enyo device info", info))
                    sheet = null
                }
            }
        }
        AboutSheet.Licenses -> EnyoSheet(stringResource(R.string.about_licenses), { sheet = null }) {
            Column(Modifier.heightIn(max = tokens.widgetPickerHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                ListCard {
                    libraries.forEach { notice ->
                        ListRow(notice.name, Modifier.appPress({ context.openWeb(notice.url) }), summary = notice.license)
                    }
                }
                Text(stringResource(R.string.about_licenses_models), style = tokens.caption)
                ListCard {
                    models.forEach { notice ->
                        ListRow(notice.name, Modifier.appPress({ context.openWeb(notice.url) }), summary = notice.license)
                    }
                }
                Text(stringResource(R.string.about_licenses_sounds), style = tokens.caption)
                ListCard {
                    sounds.forEach { notice ->
                        ListRow(notice.name, Modifier.appPress({ context.openWeb(notice.url) }), summary = notice.license)
                    }
                }
            }
        }
        null -> {}
    }
}
