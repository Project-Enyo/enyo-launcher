package space.koyu.enyo

import android.app.Notification
import android.app.RemoteInput
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import android.text.format.DateUtils
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * A chat message from a messaging app. Uploads, calls, and other status notices those apps post
 * (Discord's upload progress, Anura's ringing and ongoing calls) stay ordinary notifications.
 */
internal fun StatusBarNotification.isMessagingNotice(): Boolean {
    if (packageName !in messagingPackages) return false
    val note = notification
    if (note.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return false
    if (note.category in nonChatCategories) return false
    if (note.extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 || note.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) return false
    val channel = note.channelId.orEmpty()
    return nonChatChannels.none { channel.contains(it, ignoreCase = true) }
}

private val nonChatCategories = setOf(
    Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL, Notification.CATEGORY_PROGRESS,
    Notification.CATEGORY_SERVICE, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_STATUS,
    Notification.CATEGORY_SYSTEM, Notification.CATEGORY_ERROR, Notification.CATEGORY_LOCATION_SHARING,
    Notification.CATEGORY_STOPWATCH
)

/** Channel names of transfer and call notices; Discord posts uploads on NOTIFICATION_CHANNEL_UPLOAD. */
private val nonChatChannels = listOf("upload", "filetransfer", "call")

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
    var failed by remember { mutableStateOf(false) }
    val labels = remember(apps) { apps.associate { it.componentName.packageName to it.label.toString() } }
    val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    val entriesVisible = if (locked) emptyList()
    else if (group != null) entries.filter { it.noticeGroup() == group }
    else entries
    val messages = entriesVisible.filter { it.isMessagingNotice() }
    val others = entriesVisible.filterNot {
        it.isMessagingNotice() || (it.packageName in messagingPackages && it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
    }
    fun open(entry: StatusBarNotification) {
        failed = false
        if (entry.openFromForeground(context)) return
        val provider = apps.firstOrNull { it.componentName.packageName == entry.packageName }
        if (provider == null) failed = true else openApp(provider)
    }
    fun dismiss(entry: StatusBarNotification) {
        failed = !EnyoNotificationService.dismiss(entry.key)
    }
    val providers = remember(apps) {
        apps.filter { it.componentName.packageName in messagingPackages }.distinctBy { it.componentName.packageName }
    }
    val launchers = remember(apps) { apps.associateBy { it.componentName.packageName } }
    val listening by EnyoNotificationService.listening.collectAsState()
    val threads = if (group == null) rememberArchive(emptyList<ThreadSummary>()) { ArchiveData.threads(it) } else emptyList()
    val heads = remember(threads) { threads.filter { it.ref is ThreadRef.Person }.take(ArchiveData.recentHeadCount) }
    var openThread by remember { mutableStateOf<ThreadRef?>(null) }
    var allThreads by remember { mutableStateOf(false) }
    if (group == null) LaunchedEffect(Unit) {
        ArchiveData.requestedThread.collect { requested ->
            if (requested != null) { openThread = requested; ArchiveData.requestedThread.value = null }
        }
    }
    openThread?.let { thread -> ThreadScreen(thread, launchers, openApp) { openThread = null } }
    Column(Modifier.fillMaxSize()) {
        if (group == null) {
            val conversations = if (locked) emptyList() else heads
            val hasShortcuts = providers.isNotEmpty() || conversations.isNotEmpty()
            if (hasShortcuts) LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = tokens.margin),
                contentPadding = PaddingValues(horizontal = tokens.margin),
                horizontalArrangement = Arrangement.spacedBy(tokens.margin)
            ) {
                // Chat heads: the most recent people across every messenger, from the archive.
                items(conversations, key = { it.ref.toString() }) { head ->
                    ChatHead(head) { openThread = head.ref }
                }
                items(providers, key = { it.componentName.flattenToString() }) { app ->
                    val name = labels.getValue(app.componentName.packageName)
                    Box(
                        Modifier.size(tokens.iconSize).clip(CircleShape).appPress({ openApp(app) })
                            .semantics { contentDescription = name },
                        contentAlignment = Alignment.Center
                    ) { AppIcon(app, label = name) }
                }
            }
            Text(
                stringResource(R.string.inbox_notifications_heading),
                Modifier.padding(horizontal = tokens.margin)
                    .padding(top = if (hasShortcuts) tokens.margin else tokens.gap, bottom = tokens.gap)
                    .semantics { heading() },
                style = tokens.settingsTitle
            )
        }
        val visibleThreads = if (group == null && !locked) threads else emptyList()
        if (visibleThreads.isEmpty() && messages.isEmpty() && others.isEmpty()) {
            if (group == null && !listening) Box(Modifier.padding(horizontal = tokens.margin)) { ListCard { ListNote(stringResource(R.string.archive_access_missing)) } }
            if (failed) Box(Modifier.padding(horizontal = tokens.margin)) { ListCard { ListNote(stringResource(R.string.notice_open_failed)) } }
            Box(Modifier.fillMaxSize().padding(tokens.margin), contentAlignment = Alignment.Center) {
                WorkspaceEmptyState(R.string.inbox_empty)
            }
        } else LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = tokens.margin, end = tokens.margin, bottom = tokens.margin),
            verticalArrangement = Arrangement.spacedBy(tokens.gap)
        ) {
            if (failed) item { ListCard { ListNote(stringResource(R.string.notice_open_failed)) } }
            if (group == null && !listening) item { ListCard { ListNote(stringResource(R.string.archive_access_missing)) } }
            if (visibleThreads.isNotEmpty()) item(key = "threads") {
                SettingsPanel(stringResource(R.string.inbox_conversations)) {
                    val shown = if (allThreads) visibleThreads else visibleThreads.take(tokens.inboxThreadPreview)
                    shown.forEach { summary -> key(summary.ref.toString()) { ThreadRow(summary, launchers) { openThread = summary.ref } } }
                    if (!allThreads && visibleThreads.size > shown.size) {
                        SettingsAction(stringResource(R.string.inbox_show_all, visibleThreads.size), Glyph.Messages) { allThreads = true }
                    }
                }
            }
            // The unified list already holds messaging notifications, so their own card stays in the banner tray only.
            if (group != null && messages.isNotEmpty()) item(key = "messages") {
                ListCard {
                    NoticeGroupHeader(
                        stringResource(R.string.messages), messages.size,
                        icon = { EnyoGlyph(Glyph.Messages, Modifier.size(tokens.noticeAppIcon), color = tokens.onAccentContainer) },
                        expanded = if (group == null) expanded else null,
                        toggle = { expanded = !expanded }
                    )
                    if (expanded) messages.forEach { entry ->
                        NoticeItem(
                            entry, labels[entry.packageName] ?: sourceLabel(context, entry.packageName),
                            open = { open(entry) },
                            reply = { draftEntry = entry.key },
                            dismiss = { dismiss(entry) },
                            runAction = { failed = !it.actionIntent.sendFromForeground(context) }
                        )
                    }
                }
            }
            others.groupBy { it.packageName }.forEach { (source, notifications) ->
                item(key = source) {
                    ListCard {
                        val app = launchers[source]
                        NoticeGroupHeader(
                            labels[source] ?: sourceLabel(context, source), notifications.size,
                            icon = {
                                if (app != null) AppIcon(app, displaySize = tokens.noticeAppIcon, label = labels[source])
                                else EnyoGlyph(Glyph.Bell, Modifier.size(tokens.noticeAppIcon), color = tokens.onAccentContainer)
                            }
                        )
                        notifications.forEach { entry ->
                            NoticeItem(
                                entry, null,
                                open = { open(entry) },
                                reply = null,
                                dismiss = { dismiss(entry) },
                                runAction = { failed = !it.actionIntent.sendFromForeground(context) }
                            )
                        }
                    }
                }
            }
        }
    }
    val replying = messages.firstOrNull { it.key == draftEntry && it.notification.replyAction() != null }
    if (replying != null) NotificationReplySheet(
        replying, labels[replying.packageName] ?: sourceLabel(context, replying.packageName),
        close = { draftEntry = null },
        openApp = { open(replying) }
    )
    LaunchedEffect(replying) {
        if (replying == null) draftEntry = null
    }
}

/** Card header: the source icon and name, the count, and an optional collapse chevron. */
@Composable
private fun NoticeGroupHeader(
    title: String,
    count: Int,
    icon: @Composable () -> Unit,
    expanded: Boolean? = null,
    toggle: () -> Unit = {}
) {
    val tokens = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = tokens.touch)
            .then(if (expanded != null) Modifier.appPress(toggle) else Modifier)
            .padding(horizontal = tokens.padding),
        horizontalArrangement = Arrangement.spacedBy(tokens.gap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(tokens.noticeAppIcon).clip(CircleShape), contentAlignment = Alignment.Center) { icon() }
        Text(title, Modifier.weight(1f), style = tokens.label.copy(color = tokens.onAccentContainer), maxLines = 1)
        if (count > 1) Text(count.toString(), style = tokens.label)
        if (expanded != null) EnyoGlyph(
            Glyph.Forward,
            Modifier.size(tokens.chevronSize * 1.5f).graphicsLayer { rotationZ = if (expanded) 90f else 0f },
            color = tokens.quiet
        )
    }
}

/** One notification: tap to open, a dismiss button, and the reply and app actions below. */
@Composable
private fun NoticeItem(
    entry: StatusBarNotification,
    provider: String?,
    open: () -> Unit,
    reply: (() -> Unit)?,
    dismiss: () -> Unit,
    runAction: (Notification.Action) -> Unit
) {
    val tokens = LocalTokens.current
    val note = entry.notification
    val messages = runCatching { androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(note)?.messages }.getOrNull()
    val latest = messages?.lastOrNull()
    val title = (latest?.person?.name ?: note.extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()
    val body = (latest?.text ?: note.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: note.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
    val time = remember(entry.postTime) {
        DateUtils.getRelativeTimeSpanString(entry.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
    val dismissLabel = stringResource(R.string.notice_dismiss)
    val replyAction = if (reply != null) note.replyAction() else null
    val actions = note.actions.orEmpty().filter { it.remoteInputs.isNullOrEmpty() && it.actionIntent != null }.take(2)
    Column(Modifier.fillMaxWidth().appPress(open).padding(start = tokens.padding, end = tokens.gap, top = tokens.gap / 2, bottom = tokens.gap)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = tokens.gap / 2)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(provider, title?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { time },
                        Modifier.weight(1f, fill = false), style = tokens.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("  $time", style = tokens.rowSummary, maxLines = 1)
                }
                if (!body.isNullOrBlank()) Text(body, style = tokens.rowSummary, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (entry.isClearable) Box(
                Modifier.size(tokens.touch).clip(CircleShape).appPress(dismiss).semantics { contentDescription = dismissLabel },
                contentAlignment = Alignment.Center
            ) { EnyoGlyph(Glyph.Close, Modifier.size(tokens.noticeDismissGlyph), color = tokens.quiet) }
        }
        if (replyAction != null || actions.isNotEmpty()) Row(
            Modifier.padding(top = tokens.gap),
            horizontalArrangement = Arrangement.spacedBy(tokens.gap)
        ) {
            if (replyAction != null) PillButton(stringResource(R.string.message_reply), filled = true) { reply?.invoke() }
            actions.forEach { action -> PillButton(action.title.toString()) { runAction(action) } }
        }
    }
}

internal fun sourceLabel(context: Context, packageName: String): String =
    runCatching { context.packageManager.getApplicationInfo(packageName, 0).loadLabel(context.packageManager).toString() }
        .getOrDefault(packageName)
