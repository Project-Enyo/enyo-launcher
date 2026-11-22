package space.koyu.enyo

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Archive queries that follow every write, run off the main thread. */
@Composable
internal fun <T> rememberArchive(initial: T, vararg keys: Any?, query: (android.content.Context) -> T): T {
    val context = LocalContext.current
    val revision by remember(context) { MessageArchive.get(context).revision }.collectAsState()
    val value by produceState(initial, revision, *keys) { value = withContext(Dispatchers.IO) { query(context) } }
    return value
}

@Composable
private fun rememberAvatar(summary: ThreadSummary?): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, summary?.ref, summary?.contactLookup, summary?.avatar?.size) {
        value = summary?.let { withContext(Dispatchers.IO) { ArchiveData.avatar(context, it) } }
    }
    return image
}

/** A round person avatar: contact photo, the app's avatar, or an initial. */
@Composable
internal fun PersonAvatar(summary: ThreadSummary?, size: androidx.compose.ui.unit.Dp, name: String = summary?.name.orEmpty()) {
    val tokens = LocalTokens.current
    val image = rememberAvatar(summary)
    Box(Modifier.size(size).clip(CircleShape).background(tokens.accentContainer), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            val initial = name.codePoints().filter { Character.isLetterOrDigit(it) }.findFirst().orElse('?'.code)
            Text(String(Character.toChars(initial)).uppercase(), style = tokens.state, color = tokens.onAccentContainer)
        }
    }
}

/** One chat head: the person's avatar with an unread dot. */
@Composable
internal fun ChatHead(summary: ThreadSummary, open: () -> Unit) {
    val tokens = LocalTokens.current
    Box(Modifier.size(tokens.iconSize).clip(CircleShape).appPress(open).semantics { contentDescription = summary.name }) {
        PersonAvatar(summary, tokens.iconSize)
        if (summary.unread > 0) Box(
            Modifier.align(Alignment.TopEnd).size(tokens.unreadDot).clip(CircleShape).background(tokens.accent)
        )
    }
}

/** A thread row in the unified inbox list. */
@Composable
internal fun ThreadRow(summary: ThreadSummary, launchers: Map<String, android.content.pm.LauncherActivityInfo>, open: () -> Unit) {
    val tokens = LocalTokens.current
    val time = remember(summary.lastAt) {
        DateUtils.getRelativeTimeSpanString(summary.lastAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
    ListRow(
        summary.name, Modifier.appPress(open), summary = summary.preview, summaryLines = 2,
        leading = { PersonAvatar(summary, tokens.listAvatar) },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(tokens.gap / 2)) {
                Text(time, style = tokens.rowSummary, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap / 2), verticalAlignment = Alignment.CenterVertically) {
                    summary.packages.take(3).forEach { pkg -> launchers[pkg]?.let { AppIcon(it, displaySize = tokens.threadAppIcon) } }
                    if (summary.unread > 0) Box(
                        Modifier.clip(CircleShape).background(tokens.accent).padding(horizontal = tokens.gap / 2),
                        contentAlignment = Alignment.Center
                    ) { Text(summary.unread.toString(), style = tokens.pillLabel.copy(color = tokens.onAccent)) }
                }
            }
        }
    )
}

/**
 * The merged thread of one person across messengers, or one group chat. Replies go through the
 * app whose notification currently offers a reply action; otherwise the thread can open the app.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ThreadScreen(
    thread: ThreadRef,
    launchers: Map<String, android.content.pm.LauncherActivityInfo>,
    openApp: (android.content.pm.LauncherActivityInfo) -> Unit,
    close: () -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val summary = rememberArchive<ThreadSummary?>(null, thread) { ArchiveData.summary(it, thread) }
    val messages = rememberArchive(emptyList<ArchivedMessage>(), thread) { ArchiveData.messages(it, thread) }
    val personId = (thread as? ThreadRef.Person)?.id
    val identities = rememberArchive(emptyList<ThreadIdentity>(), personId) { if (personId == null) emptyList() else ArchiveData.identities(it, personId) }
    val suggestions = rememberArchive(emptyList<LinkSuggestion>(), personId) { if (personId == null) emptyList() else ArchiveData.suggestions(it, personId) }
    val proposals = rememberArchive(emptyList<DuplicateProposal>(), messages) { ArchiveData.duplicateProposals(it, messages) }
    val active by EnyoNotificationService.entries.collectAsState()
    LaunchedEffect(thread, messages.size) { ArchiveData.markRead(context, thread) }
    var confirmDelete by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var deletingMessage by remember { mutableStateOf<ArchivedMessage?>(null) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        val contact = uri?.let { ArchiveData.contactOf(context, it) } ?: return@rememberLauncherForActivityResult
        identities.forEach { ArchiveData.linkToContact(context, it.id, contact.lookup, contact.name) }
    }
    // Live notifications for this thread, newest first, one per app.
    val keys = remember(messages) { messages.mapNotNull { it.notificationKey }.toSet() }
    val live = remember(active, keys) {
        active.filter { it.key in keys }.sortedByDescending { it.postTime }.distinctBy { it.packageName }
    }
    val replies = live.mapNotNull { entry -> entry.notification.replyAction()?.let { entry to it } }
    var replyVia by remember(replies.map { it.first.packageName }) { mutableStateOf(replies.firstOrNull()?.first?.packageName) }
    var draft by remember { mutableStateOf("") }
    val label = { pkg: String -> launchers[pkg]?.label?.toString() ?: sourceLabel(context, pkg) }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(tokens.settingsBackground).safeDrawingPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = tokens.gap, vertical = tokens.gap),
                horizontalArrangement = Arrangement.spacedBy(tokens.gap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val backLabel = stringResource(R.string.back)
                Box(Modifier.size(tokens.touch).clip(CircleShape).appPress(close).semantics { contentDescription = backLabel },
                    contentAlignment = Alignment.Center) { EnyoGlyph(Glyph.Back) }
                PersonAvatar(summary, tokens.listAvatar)
                Column(Modifier.weight(1f)) {
                    Text(summary?.name.orEmpty(), style = tokens.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(summary?.packages.orEmpty().joinToString(" · ") { label(it) }, style = tokens.rowSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = rememberLazyListState(),
                contentPadding = PaddingValues(horizontal = tokens.margin, vertical = tokens.gap),
                verticalArrangement = Arrangement.spacedBy(tokens.gap),
                reverseLayout = true
            ) {
                items(messages.asReversed(), key = { it.id }) { message ->
                    MessageBubble(message, label(message.packageName), thread is ThreadRef.Group) { deletingMessage = message }
                }
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                        proposals.forEach { proposal ->
                            ListCard {
                                ListRow(
                                    stringResource(R.string.duplicate_question, label(proposal.providerPackage)),
                                    summary = proposal.text, summaryLines = 2,
                                    leading = { EnyoGlyph(Glyph.Messages, color = tokens.accent) },
                                    trailing = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap / 2)) {
                                            PillButton(stringResource(R.string.duplicate_keep)) { ArchiveData.keepBothDuplicates(context, proposal) }
                                            PillButton(stringResource(R.string.duplicate_merge), filled = true) { ArchiveData.confirmDuplicate(context, proposal) }
                                        }
                                    }
                                )
                            }
                        }
                        suggestions.forEach { suggestion ->
                            ListCard {
                                ListRow(
                                    stringResource(R.string.thread_suggestion, suggestion.contactName ?: "?"),
                                    summary = label(suggestion.packageName),
                                    leading = { EnyoGlyph(Glyph.Message, color = tokens.accent) },
                                    trailing = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap / 2)) {
                                            PillButton(stringResource(R.string.thread_suggestion_no)) { ArchiveData.dismissSuggestion(context, suggestion) }
                                            PillButton(stringResource(R.string.thread_suggestion_link), filled = true) {
                                                ArchiveData.linkToContact(context, suggestion.identityId, suggestion.lookup, suggestion.contactName)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        if (personId != null) SettingsPanel(stringResource(R.string.thread_people_section)) {
                            identities.forEach { identity ->
                                ListRow(
                                    identity.name ?: label(identity.packageName),
                                    summary = label(identity.packageName),
                                    leading = { launchers[identity.packageName]?.let { AppIcon(it, displaySize = tokens.listAvatar) } },
                                    trailing = if (identities.size > 1) ({
                                        PillButton(stringResource(R.string.thread_split)) { ArchiveData.split(context, identity.id) }
                                    }) else null
                                )
                            }
                            SettingsAction(stringResource(R.string.thread_link_contact), Glyph.Phone) { runCatching { picker.launch(null) }.onFailure { failed = true } }
                            SettingsAction(stringResource(R.string.thread_merge), Glyph.Messages) { merging = true }
                        }
                        ListCard {
                            SettingsAction(stringResource(R.string.thread_delete), Glyph.Trash) { confirmDelete = true }
                            ListNote(stringResource(R.string.archive_note))
                        }
                        if (failed) ListCard { ListNote(stringResource(R.string.action_unavailable)) }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = tokens.margin, vertical = tokens.gap),
                verticalArrangement = Arrangement.spacedBy(tokens.gap)
            ) {
                if (replies.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    replies.forEach { (entry, _) ->
                        PillButton(label(entry.packageName), filled = replyVia == entry.packageName) { replyVia = entry.packageName }
                    }
                }
                val selected = replies.firstOrNull { it.first.packageName == replyVia }
                if (selected != null) Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        draft, { draft = it },
                        Modifier.weight(1f).clip(RoundedCornerShape(tokens.radius)).background(tokens.widgetSurface)
                            .padding(horizontal = tokens.padding, vertical = tokens.gap * 1.5f),
                        textStyle = tokens.rowTitle,
                        cursorBrush = SolidColor(tokens.accent),
                        decorationBox = { field ->
                            if (draft.isEmpty()) Text(stringResource(R.string.thread_reply_hint, label(selected.first.packageName)), style = tokens.rowSummary)
                            field()
                        }
                    )
                    PillButton(stringResource(R.string.message_send), filled = true, enabled = draft.isNotBlank()) {
                        val (entry, action) = selected
                        failed = !sendReply(context, action, draft)
                        if (!failed) draft = ""
                        if (failed) entry.openFromForeground(context)
                    }
                } else ListNote(stringResource(R.string.thread_no_reply))
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    val openable = (live.map { it.packageName } + summary?.packages.orEmpty()).distinct()
                    openable.take(3).forEach { pkg ->
                        PillButton(stringResource(R.string.thread_open_in, label(pkg))) {
                            val entry = live.firstOrNull { it.packageName == pkg }
                            val opened = entry?.openFromForeground(context) == true || launchers[pkg]?.let { openApp(it); true } == true
                            failed = !opened
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) EnyoSheet(stringResource(R.string.thread_delete), { confirmDelete = false }) {
        Text(stringResource(R.string.thread_delete_confirm, summary?.name.orEmpty()), style = tokens.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
            PillButton(stringResource(R.string.cancel)) { confirmDelete = false }
            PillButton(stringResource(R.string.thread_delete), filled = true) {
                confirmDelete = false
                ArchiveData.deleteThread(context, thread)
                close()
            }
        }
    }
    deletingMessage?.let { message ->
        EnyoSheet(stringResource(R.string.thread_delete_message), { deletingMessage = null }) {
            Text(message.text, style = tokens.secondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                PillButton(stringResource(R.string.cancel)) { deletingMessage = null }
                PillButton(stringResource(R.string.thread_delete_message), filled = true) {
                    ArchiveData.deleteMessage(context, message.id)
                    deletingMessage = null
                }
            }
        }
    }
    if (merging && personId != null) {
        val others = rememberArchive(emptyList<ThreadSummary>(), personId) { ArchiveData.people(it, personId) }
        EnyoSheet(stringResource(R.string.thread_merge), { merging = false }) {
            LazyColumn(Modifier.heightIn(max = tokens.widgetPickerHeight)) {
                items(others, key = { (it.ref as ThreadRef.Person).id }) { other ->
                    ListRow(
                        other.name,
                        Modifier.appPress({
                            val target = (other.ref as ThreadRef.Person).id
                            identities.forEach { ArchiveData.linkToPerson(context, it.id, target) }
                            merging = false
                            ArchiveData.requestedThread.value = other.ref
                            close()
                        }),
                        summary = other.packages.joinToString(" · ") { label(it) },
                        leading = { PersonAvatar(other, tokens.listAvatar) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: ArchivedMessage, app: String, showSender: Boolean, delete: () -> Unit) {
    val tokens = LocalTokens.current
    val time = remember(message.postedAt) {
        DateUtils.formatDateTime(null, message.postedAt, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = tokens.bubbleMaxWidth)
                .clip(RoundedCornerShape(tokens.radius))
                .background(if (message.outgoing) tokens.accentContainer else tokens.widgetSurface)
                .combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = delete)
                .padding(horizontal = tokens.padding, vertical = tokens.gap),
            horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start
        ) {
            if (showSender && !message.outgoing && message.sender != null) Text(message.sender, style = tokens.pillLabel.copy(color = tokens.accent))
            Text(message.text, style = tokens.rowTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
            Text("$app · $time", style = tokens.rowSummary)
        }
    }
}

/** Sends a reply through the notification's own RemoteInput action, only after the person submits it. */
private fun sendReply(context: android.content.Context, action: Notification.Action, text: String): Boolean = runCatching {
    val input = action.remoteInputs.first { it.allowFreeFormInput }
    val intent = Intent()
    RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, text) })
    action.actionIntent.send(context, 0, intent)
    true
}.getOrDefault(false)
