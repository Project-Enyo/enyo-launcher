package space.koyu.enyo

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val minimumPassword = 8

@Composable
private fun PasswordField(value: String, hint: String, change: (String) -> Unit) {
    val tokens = LocalTokens.current
    Box(Modifier.fillMaxWidth().heightIn(min = tokens.touch).clip(RoundedCornerShape(tokens.touch / 2)).background(tokens.widgetTrack)
        .padding(horizontal = tokens.padding, vertical = tokens.gap * 1.5f), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(hint, style = tokens.body.copy(color = tokens.quiet))
        BasicTextField(value, change, Modifier.fillMaxWidth().semantics { contentDescription = hint }, singleLine = true,
            textStyle = tokens.body, cursorBrush = SolidColor(tokens.accent), visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    }
}

/** Ticks for what a backup holds or a restore brings back. */
@Composable
private fun CategoryChoice(available: Set<BackupCategory>, chosen: Set<BackupCategory>, change: (Set<BackupCategory>) -> Unit) {
    ListCard {
        BackupCategory.entries.filter { it in available }.forEach { category ->
            SettingsToggle(stringResource(category.label), category in chosen) { on -> change(if (on) chosen + category else chosen - category) }
        }
    }
}

private sealed class BackupSheet {
    data object Password : BackupSheet()
    data object Create : BackupSheet()
    data class Unlock(val bytes: ByteArray) : BackupSheet()
    data class Restore(val preview: BackupPreview) : BackupSheet()
}

/**
 * Settings → Backups: encrypted snapshots of messages, links, notes, Local AI, settings and layout,
 * written into a folder the person picks. Syncthing can share that folder with their other devices,
 * as in EmuGo; any device restores a file with the password.
 */
@Composable
internal fun BackupsPage(close: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    val board = LocalWidgetBoard.current
    var revision by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<BackupSheet?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val hasPassword = remember(revision) { Backups.hasPassword(context) }
    val folderName = remember(revision) { Backups.folderName(context) }
    val automatic = remember(revision) { Backups.automatic(context) }
    val last = remember(revision) { Backups.lastAutomatic(context) }
    val lastFailed = remember(revision) { Backups.lastError(context) }
    var syncthing by remember { mutableStateOf<String?>(null) }
    val syncthingInstalled = remember(revision) { Backups.syncthingInstalled(context) != null }
    var choosingStore by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { syncthing = Backups.syncthingStatus(context); delay(2_000) } }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { Backups.setFolder(context, uri); revision++ }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            if (bytes == null) status = context.getString(R.string.backup_read_failed) else sheet = BackupSheet.Unlock(bytes)
        }
    }

    FullPage(stringResource(R.string.backups), close) {
        item(key = "intro") { ListCard { ListNote(stringResource(R.string.backup_intro)) } }
        status?.let { item(key = "status") { ListCard { ListNote(it) } } }
        item(key = "setup") {
            SettingsPanel(stringResource(R.string.backup_setup)) {
                SettingsAction(stringResource(if (hasPassword) R.string.backup_change_password else R.string.backup_set_password), Glyph.Pin,
                    summary = stringResource(if (hasPassword) R.string.backup_password_set else R.string.backup_password_needed)) { sheet = BackupSheet.Password }
                SettingsAction(stringResource(R.string.backup_folder), Glyph.Folder,
                    summary = folderName ?: stringResource(R.string.backup_folder_none)) { runCatching { folderPicker.launch(null) }.onFailure { status = context.getString(R.string.action_unavailable) } }
                SettingsToggle(stringResource(R.string.backup_automatic), automatic && hasPassword && folderName != null,
                    summary = when {
                        !hasPassword || folderName == null -> stringResource(R.string.backup_automatic_needs)
                        lastFailed -> stringResource(R.string.backup_automatic_failed)
                        last > 0 -> stringResource(R.string.backup_automatic_last, DateUtils.getRelativeTimeSpanString(last).toString())
                        else -> stringResource(R.string.backup_automatic_summary)
                    }, glyph = Glyph.History) { on ->
                    if (hasPassword && folderName != null) { Backups.setAutomatic(context, on); revision++ }
                }
            }
        }
        item(key = "now") {
            SettingsPanel(stringResource(R.string.backup_manual)) {
                SettingsAction(stringResource(R.string.backup_now), Glyph.Push, enabled = hasPassword && folderName != null && !working,
                    summary = stringResource(R.string.backup_now_summary)) { sheet = BackupSheet.Create }
                SettingsAction(stringResource(R.string.backup_restore), Glyph.Refresh, enabled = !working,
                    summary = stringResource(R.string.backup_restore_summary)) { runCatching { filePicker.launch(arrayOf("*/*")) }.onFailure { status = context.getString(R.string.action_unavailable) } }
            }
        }
        item(key = "syncthing") {
            SettingsPanel("Syncthing") {
                ListRow(stringResource(R.string.backup_syncthing_status), summary = when {
                    !syncthingInstalled -> stringResource(R.string.backup_syncthing_missing)
                    syncthing != null -> syncthing
                    else -> stringResource(R.string.backup_syncthing_quiet)
                }, summaryLines = 4, leading = { EnyoGlyph(Glyph.Refresh, color = tokens.onAccentContainer) })
                SettingsLink(stringResource(if (syncthingInstalled) R.string.backup_syncthing_open else R.string.backup_syncthing_get),
                    stringResource(R.string.backup_syncthing_summary), Glyph.External) {
                    val launch = Backups.syncthingInstalled(context)?.let { context.packageManager.getLaunchIntentForPackage(it) }
                    if (launch == null || !context.launchSafely(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) choosingStore = true
                }
            }
        }
    }

    // Where to get Syncthing-Fork: each store's app when it is installed, its web page otherwise.
    if (choosingStore) EnyoSheet(stringResource(R.string.backup_syncthing_get), { choosingStore = false }) {
        fun get(app: String?, link: String, web: String) {
            choosingStore = false
            val direct = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (app != null) setPackage(app) }
            if (!context.launchSafely(direct)) context.openWeb(web)
        }
        ListCard {
            SettingsLink("Google Play", Backups.syncthingPlay, Glyph.External) {
                get("com.android.vending", "market://details?id=${Backups.syncthingPlay}", "https://play.google.com/store/apps/details?id=${Backups.syncthingPlay}")
            }
            SettingsLink("F-Droid", Backups.syncthingFork, Glyph.External) {
                get("org.fdroid.fdroid", "market://details?id=${Backups.syncthingFork}", "https://f-droid.org/packages/${Backups.syncthingFork}")
            }
            SettingsLink("Obtainium", "github.com/researchxxl/syncthing-android", Glyph.External) {
                // Syncthing-Fork's own Obtainium link, which picks the right APK of its releases.
                val app = "obtainium://app/%7B%22id%22%3A%22com.github.catfriend1.syncthingfork%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fresearchxxl%2Fsyncthing-android%22%2C%22author%22%3A%22researchxxl%22%2C%22name%22%3A%22Syncthing-Fork%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22verifyLatestTag%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22com.github.catfriend1.syncthingfork%5C%22%7D%22%2C%22overrideSource%22%3Anull%7D"
                get(null, app, "https://apps.obtainium.imranr.dev/redirect?r=$app")
            }
        }
    }
    when (val current = sheet) {
        BackupSheet.Password -> EnyoSheet(stringResource(if (hasPassword) R.string.backup_change_password else R.string.backup_set_password), { sheet = null }) {
            var first by remember { mutableStateOf("") }
            var second by remember { mutableStateOf("") }
            Text(stringResource(R.string.backup_password_note), style = tokens.secondary)
            PasswordField(first, stringResource(R.string.backup_password)) { first = it }
            PasswordField(second, stringResource(R.string.backup_password_repeat)) { second = it }
            val valid = first.length >= minimumPassword && first == second
            if (first.isNotEmpty() && first.length < minimumPassword) Text(stringResource(R.string.backup_password_short, minimumPassword), style = tokens.caption)
            else if (second.isNotEmpty() && first != second) Text(stringResource(R.string.backup_password_mismatch), style = tokens.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                PillButton(stringResource(R.string.cancel)) { sheet = null }
                PillButton(stringResource(R.string.notes_done), filled = true, enabled = valid) {
                    val password = first.toCharArray()
                    first = ""; second = ""
                    sheet = null
                    scope.launch {
                        withContext(Dispatchers.Default) { Backups.setPassword(context, password) }
                        revision++
                    }
                }
            }
        }
        BackupSheet.Create -> EnyoSheet(stringResource(R.string.backup_now), { sheet = null }) {
            var chosen by remember { mutableStateOf(BackupCategory.entries.toSet()) }
            CategoryChoice(BackupCategory.entries.toSet(), chosen) { chosen = it }
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                PillButton(stringResource(R.string.cancel)) { sheet = null }
                PillButton(stringResource(R.string.backup_now), filled = true, enabled = chosen.isNotEmpty()) {
                    val categories = chosen
                    sheet = null
                    working = true
                    status = context.getString(R.string.backup_working)
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { Backups.create(context, categories)?.let { Backups.writeToFolder(context, it, automatic = false) } }.getOrNull() == true
                        }
                        status = context.getString(if (ok) R.string.backup_done else R.string.backup_failed)
                        working = false
                    }
                }
            }
        }
        is BackupSheet.Unlock -> EnyoSheet(stringResource(R.string.backup_restore), { sheet = null }) {
            var password by remember { mutableStateOf("") }
            var wrong by remember { mutableStateOf(false) }
            Text(stringResource(R.string.backup_unlock_note), style = tokens.secondary)
            PasswordField(password, stringResource(R.string.backup_password)) { password = it; wrong = false }
            if (wrong) Text(stringResource(R.string.backup_unlock_failed), style = tokens.caption.copy(color = tokens.danger))
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                PillButton(stringResource(R.string.cancel)) { sheet = null }
                PillButton(stringResource(R.string.backup_unlock), filled = true, enabled = password.isNotEmpty() && !working) {
                    val secret = password.toCharArray()
                    working = true
                    scope.launch {
                        val preview = withContext(Dispatchers.Default) { Backups.open(context, current.bytes, secret) }
                        working = false
                        if (preview == null) wrong = true else { password = ""; sheet = BackupSheet.Restore(preview) }
                    }
                }
            }
        }
        is BackupSheet.Restore -> EnyoSheet(stringResource(R.string.backup_restore), { sheet = null }) {
            val preview = current.preview
            var chosen by remember { mutableStateOf(preview.categories) }
            Text(stringResource(R.string.backup_preview, DateUtils.formatDateTime(context, preview.created,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR), preview.messages, preview.people), style = tokens.secondary)
            if (!preview.sameProfile) Text(stringResource(R.string.backup_other_profile), style = tokens.caption)
            CategoryChoice(preview.categories, chosen) { chosen = it }
            Text(stringResource(R.string.backup_restore_note), style = tokens.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                PillButton(stringResource(R.string.cancel)) { sheet = null }
                PillButton(stringResource(R.string.backup_restore), filled = true, enabled = chosen.isNotEmpty() && !working) {
                    val categories = chosen
                    sheet = null
                    working = true
                    status = context.getString(R.string.backup_working)
                    scope.launch {
                        val ok = runCatching { Backups.restore(context, preview, categories, board) }.isSuccess
                        status = context.getString(if (ok) R.string.backup_restored else R.string.backup_restore_failed)
                        working = false
                    }
                }
            }
        }
        null -> {}
    }
}
