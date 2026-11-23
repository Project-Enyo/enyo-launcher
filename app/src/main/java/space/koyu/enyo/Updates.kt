package space.koyu.enyo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

internal data class Release(val version: String, val notes: String, val page: String, val apkUrl: String?, val apkSize: Long)

/**
 * Updates from GitHub releases of Project-Enyo/enyo-launcher, as in EmuGo: a check every 15 minutes,
 * one notification per version, the changelog in Enyo, and an install through PackageInstaller.
 * Debug builds have their own package id and never install a release over themselves.
 */
internal object Updates {
    private const val repository = "Project-Enyo/enyo-launcher"
    private const val endpoint = "https://api.github.com/repos/$repository/releases/latest"
    private const val channel = "updates"
    private const val notificationId = 4900
    private const val work = "enyo_update_check"
    const val extraShowUpdate = "space.koyu.enyo.SHOW_UPDATE"

    /** A newer release the person asked to see, shown as a sheet by the launcher. */
    val offered = MutableStateFlow<Release?>(null)
    val progress = MutableStateFlow<Int?>(null)

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun version(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    fun enabled(context: Context) = !context.packageName.endsWith(".debug")

    fun schedule(context: Context) {
        if (!enabled(context)) return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            work, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }

    /** The latest release, or null when the repository has none. Unchanged answers cost no API quota. */
    suspend fun fetch(context: Context): Release? = withContext(Dispatchers.IO) {
        val prefs = prefs(context)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Enyo-Android/${version(context)}")
            val cached = prefs.getString("json", null)
            prefs.getString("etag", null)?.takeIf { cached != null }?.let { connection.setRequestProperty("If-None-Match", it) }
            when (val status = connection.responseCode) {
                304 -> cached?.let(::parse)
                404 -> null
                403, 429 -> fromReleasePage(context, cached) ?: throw IOException(context.getString(R.string.update_rate_limited))
                200 -> {
                    val json = connection.inputStream.bufferedReader().use { it.readText() }
                    prefs.edit().putString("json", json).putString("etag", connection.getHeaderField("ETag")).apply()
                    parse(json)
                }
                else -> throw IOException("HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(text: String): Release {
        val json = JSONObject(text)
        val assets = json.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").lowercase().endsWith(".apk") }
        return Release(json.optString("tag_name"), json.optString("body"), json.optString("html_url"),
            apk?.optString("browser_download_url"), apk?.optLong("size") ?: 0L)
    }

    /** When the API is rate limited: the releases page redirects to the newest tag, and assets are named Enyo-<version>.apk. */
    private fun fromReleasePage(context: Context, cached: String?): Release? = runCatching {
        val connection = URL("https://github.com/$repository/releases/latest").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Enyo-Android/${version(context)}")
        val location = connection.getHeaderField("Location")?.takeIf { "/releases/tag/" in it } ?: return null
        val tag = Uri.decode(location.substringAfterLast('/'))
        val previous = cached?.let(::parse)
        Release(tag, previous?.takeIf { it.version == tag }?.notes.orEmpty(), location,
            "https://github.com/$repository/releases/download/$tag/Enyo-${tag.removePrefix("v")}.apk", 0L)
    }.getOrNull()

    fun newer(context: Context, release: Release?): Boolean {
        if (release == null) return false
        val remote = release.version.removePrefix("v").removePrefix("V").split('.')
        val local = version(context).removePrefix("v").split('.')
        for (index in 0 until maxOf(remote.size, local.size)) {
            val a = remote.getOrNull(index)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
            val b = local.getOrNull(index)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
            if (a != b) return a > b
        }
        return false
    }

    /** Background check: notifies once per version. */
    suspend fun notifyIfNewer(context: Context) {
        val release = fetch(context)
        if (!newer(context, release) || release == null) return
        if (prefs(context).getString("notified", null) == release.version) return
        prefs(context).edit().putString("notified", release.version).apply()
        post(context, release)
    }

    private fun post(context: Context, release: Release) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, notificationId,
            Intent(context, EnyoActivity::class.java).putExtra(extraShowUpdate, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(Glyph.Refresh.drawable)
            .setContentTitle(context.getString(R.string.update_available, release.version))
            .setContentText(context.getString(R.string.update_tap))
            .setStyle(Notification.BigTextStyle().bigText(changelog(context, release.notes)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(notificationId, notification) }
    }

    /** Opens the update sheet from the notification, using the last fetched release. */
    fun showFromNotification(context: Context) {
        val cached = prefs(context).getString("json", null)?.let { runCatching { parse(it) }.getOrNull() }
        if (newer(context, cached)) offered.value = cached
    }

    /** GitHub release notes are Markdown; shown as plain text with bullets. */
    fun changelog(context: Context, notes: String): String {
        if (notes.isBlank()) return context.getString(R.string.update_no_changelog)
        return notes.replace("\r", "").lines().joinToString("\n") { line ->
            var text = line.trim()
            if (Regex("^[-*+] ").containsMatchIn(text)) text = "•  " + text.drop(2).trim()
            text.replace(Regex("^#+\\s*"), "").replace("**", "").replace("__", "").replace("`", "")
                .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        }.trim().take(3000)
    }

    /** Downloads the release APK and hands it to PackageInstaller, which asks the person to confirm. */
    suspend fun install(context: Context, release: Release): String? = withContext(Dispatchers.IO) {
        val url = release.apkUrl ?: return@withContext context.getString(R.string.update_no_apk)
        val directory = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val apk = File(directory, "Enyo-${release.version}.apk")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "Enyo-Android/${version(context)}")
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            connection.inputStream.use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) progress.value = (done * 100 / total).toInt()
                    }
                }
            }
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("Enyo.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val result = PendingIntent.getBroadcast(context, id, Intent(context, UpdateInstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(result.intentSender)
            }
            null
        } catch (error: Exception) {
            apk.delete()
            context.getString(R.string.update_failed, error.message.orEmpty())
        } finally {
            progress.value = null
        }
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { Updates.notifyIfNewer(applicationContext) }
        return Result.success()
    }
}

/** PackageInstaller results: Android's confirmation screen, or a failure message. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> Toast.makeText(context, context.getString(R.string.update_failed,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status.toString()), Toast.LENGTH_LONG).show()
        }
    }
}

/** The changelog of a newer release, with download and install. */
@Composable
internal fun UpdateSheet(release: Release, close: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    val progress by Updates.progress.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    val foreground = foregroundCycle()
    LaunchedEffect(foreground) { if (needsPermission && context.packageManager.canRequestPackageInstalls()) needsPermission = false }
    EnyoSheet(stringResource(R.string.update_available, release.version), close) {
        Text(
            remember(release) { Updates.changelog(context, release.notes) },
            Modifier.heightIn(max = tokens.widgetPickerHeight).verticalScroll(rememberScrollState()),
            style = tokens.body
        )
        progress?.let { Text(stringResource(R.string.update_downloading, it), style = tokens.caption) }
        error?.let { Text(it, style = tokens.caption) }
        if (needsPermission) Text(stringResource(R.string.update_permission), style = tokens.caption)
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
            PillButton(stringResource(R.string.update_later)) { close() }
            Spacer(Modifier.weight(1f))
            if (needsPermission) PillButton(stringResource(R.string.push_allow), filled = true) {
                context.launchSafely(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } else PillButton(stringResource(R.string.update_install), filled = true, enabled = progress == null) {
                if (!context.packageManager.canRequestPackageInstalls()) needsPermission = true
                else scope.launch { error = Updates.install(context, release) }
            }
        }
    }
}

/** Settings: the installed version and a manual check. */
@Composable
internal fun UpdatePreference() {
    SettingsPanel(stringResource(R.string.update_title)) { UpdateCheckRow() }
}

/** Checks GitHub for a newer release; a newer one opens the update sheet. */
@Composable
internal fun UpdateCheckRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    run {
        val summary = status ?: stringResource(R.string.update_version, Updates.version(context))
        if (!Updates.enabled(context)) ListRow(stringResource(R.string.update_check), summary = stringResource(R.string.update_debug), enabled = false,
            leading = { EnyoGlyph(Glyph.Refresh, color = LocalTokens.current.onAccentContainer) })
        else SettingsAction(stringResource(R.string.update_check), Glyph.Refresh, enabled = !checking, summary = summary) {
            checking = true
            scope.launch {
                status = runCatching {
                    val release = Updates.fetch(context)
                    when {
                        release == null -> context.getString(R.string.update_none_published)
                        Updates.newer(context, release) -> { Updates.offered.value = release; null }
                        else -> context.getString(R.string.update_current, Updates.version(context))
                    }
                }.getOrElse { context.getString(R.string.update_check_failed, it.message.orEmpty()) }
                checking = false
            }
        }
    }
}
