package space.koyu.enyo

import android.app.Notification
import android.app.RemoteInput
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

internal val messagingPackages = setOf(
    "space.koyu.apollo.mini", "space.koyu.apollo", "org.telegram.messenger", "com.whatsapp",
    "im.molly.app", "org.thoughtcrime.securesms", "com.discord", "com.beeper.android",
    "com.android.messaging", "org.fossify.messages"
)

internal fun StatusBarNotification.isMessagingNotice() =
    packageName in messagingPackages && notification.flags and Notification.FLAG_GROUP_SUMMARY == 0

@Composable
internal fun InboxPage(
    apps: List<LauncherActivityInfo>,
    openApp: (LauncherActivityInfo) -> Unit,
    group: NoticeGroup? = null
) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val entries by EnyoNotificationService.entries.collectAsState()
    var expanded by remember(group) { mutableStateOf(group?.messaging == true) }
    var draftEntry by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val labels = remember(apps) { apps.associate { it.componentName.packageName to it.label.toString() } }
    val entriesVisible = if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) emptyList()
    else if (group != null) entries.filter { it.noticeGroup() == group }
    else entries
    val messages = entriesVisible.filter { it.isMessagingNotice() }
    val others = entriesVisible.filterNot { it.isMessagingNotice() || it.packageName in messagingPackages }
    fun open(entry: StatusBarNotification) {
        if (runCatching { entry.notification.contentIntent?.send() != null }.getOrDefault(false)) return
        val provider = apps.firstOrNull { it.componentName.packageName == entry.packageName }
        if (provider == null) failed = true else openApp(provider)
    }
    if (messages.isEmpty() && others.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(tokens.margin), contentAlignment = Alignment.Center) {
            WorkspaceEmptyState(R.string.inbox_empty)
        }
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(tokens.margin)) {
        if (failed) item { Text(stringResource(R.string.action_unavailable), style = tokens.secondary) }
        if (messages.isNotEmpty()) {
            if (group == null) item {
                TextButton(onClick = { expanded = !expanded }) {
                    EnyoGlyph(Glyph.Messages)
                    Spacer(Modifier.width(tokens.gap))
                    Text(stringResource(R.string.messages), style = tokens.state)
                }
            }
            if (expanded) items(messages, key = { it.key }) { entry ->
                NoticeRow(entry, labels[entry.packageName] ?: sourceLabel(context, entry.packageName),
                    { open(entry) }, { draft = ""; draftEntry = entry.key }, { failed = !EnyoNotificationService.dismiss(entry.key) })
            }
        }
        others.groupBy { it.packageName }.forEach { (source, notifications) ->
            item { Text(labels[source] ?: sourceLabel(context, source), style = tokens.state) }
            items(notifications, key = { it.key }) { entry ->
                NoticeRow(entry, null, { open(entry) }, null, { failed = !EnyoNotificationService.dismiss(entry.key) })
            }
        }
    }
    val replying = messages.firstOrNull { it.key == draftEntry }
    val reply = replying?.notification?.replyAction()
    if (replying != null && reply != null) EnyoSheet(
        stringResource(R.string.message_reply), { draftEntry = null; draft = "" }
    ) {
        val title = replying.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
        if (!title.isNullOrBlank()) Text(title.toString(), style = tokens.secondary)
        OutlinedTextField(draft, { draft = it }, label = { Text(stringResource(R.string.message_reply)) })
        TextButton(enabled = draft.isNotBlank(), onClick = {
            val input = reply.remoteInputs.first { it.allowFreeFormInput }
            runCatching {
                val intent = Intent()
                RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, draft) })
                reply.actionIntent.send(context, 0, intent)
            }.onSuccess {
                draftEntry = null
                draft = ""
            }.onFailure { failed = true }
        }) { Text(stringResource(R.string.message_send)) }
        TextButton(onClick = { draftEntry = null; draft = ""; open(replying) }) {
            Text(stringResource(R.string.message_open))
        }
    }
    LaunchedEffect(replying) {
        if (replying == null) { draftEntry = null; draft = "" }
    }
}

@Composable
private fun NoticeRow(
    entry: StatusBarNotification,
    provider: String?,
    open: () -> Unit,
    reply: (() -> Unit)?,
    dismiss: () -> Unit
) {
    val tokens = LocalTokens.current
    val note = entry.notification
    val messages = runCatching { androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(note)?.messages }.getOrNull()
    val latest = messages?.lastOrNull()
    val title = latest?.person?.name ?: note.extras.getCharSequence(Notification.EXTRA_TITLE)
    val body = latest?.text ?: note.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: note.extras.getCharSequence(Notification.EXTRA_TEXT)
    Column(Modifier.fillMaxWidth().padding(vertical = tokens.gap)) {
        provider?.let { Text(it, style = tokens.label) }
        if (!title.isNullOrBlank()) Text(title.toString(), style = tokens.state)
        if (!body.isNullOrBlank()) Text(body.toString(), style = tokens.secondary)
        Row {
            if (provider != null || note.contentIntent != null) TextButton(onClick = open) {
                Text(stringResource(R.string.message_open))
            }
            if (reply != null && note.replyAction() != null) TextButton(onClick = reply) {
                Text(stringResource(R.string.message_reply))
            }
            if (entry.isClearable) TextButton(onClick = dismiss) {
                Text(stringResource(R.string.notice_dismiss))
            }
        }
        if (provider == null) note.actions.orEmpty().firstOrNull { it.remoteInputs.isNullOrEmpty() }?.let { action ->
            TextButton(onClick = { runCatching { action.actionIntent.send() } }) { Text(action.title.toString()) }
        }
    }
}

private fun Notification.replyAction(): Notification.Action? =
    actions.orEmpty().firstOrNull { it.actionIntent != null && it.remoteInputs.orEmpty().any(RemoteInput::getAllowFreeFormInput) }

internal fun sourceLabel(context: Context, packageName: String): String =
    runCatching { context.packageManager.getApplicationInfo(packageName, 0).loadLabel(context.packageManager).toString() }
        .getOrDefault(packageName)
