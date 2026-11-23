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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

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

/**
 * A round avatar: the contact photo, the app's picture, or an initial. Groups show their picture
 * with a small group badge, or a group icon when they have none.
 */
@Composable
internal fun PersonAvatar(summary: ThreadSummary?, size: androidx.compose.ui.unit.Dp, name: String = summary?.name.orEmpty(), groupBadge: Boolean = true) {
    val tokens = LocalTokens.current
    val group = summary?.ref is ThreadRef.Group
    val image = rememberAvatar(summary)
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(tokens.accentContainer), contentAlignment = Alignment.Center) {
            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (group) EnyoGlyph(Glyph.People, color = tokens.onAccentContainer)
            else {
                val initial = name.codePoints().filter { Character.isLetterOrDigit(it) }.findFirst().orElse('?'.code)
                Text(String(Character.toChars(initial)).uppercase(), style = tokens.state, color = tokens.onAccentContainer)
            }
        }
        // The badge sits on the opposite corner from the app badge of thread rows.
        if (group && groupBadge && image != null) Box(
            Modifier.align(Alignment.BottomStart).size(maxOf(tokens.threadAppIcon, size * 0.3f) + tokens.focusStroke * 2)
                .clip(CircleShape).background(tokens.widgetSurface).padding(tokens.focusStroke)
                .clip(CircleShape).background(tokens.accentContainer),
            contentAlignment = Alignment.Center
        ) {
            EnyoGlyph(Glyph.People, Modifier.fillMaxSize(0.7f), color = tokens.onAccentContainer)
        }
    }
}

/** One chat head: the person's avatar with an unread dot. */
@Composable
internal fun ChatHead(summary: ThreadSummary, open: () -> Unit, person: () -> Unit) {
    val tokens = LocalTokens.current
    // The dot sits on the avatar's edge, outside its clip, with a ring in the page color.
    Box(Modifier.size(tokens.iconSize).semantics(mergeDescendants = true) { contentDescription = summary.name }) {
        Box(Modifier.fillMaxSize().clip(CircleShape).appPress(open, person)) { PersonAvatar(summary, tokens.iconSize) }
        if (summary.unread > 0) Box(
            Modifier.align(Alignment.TopEnd).offset(x = tokens.unreadDot / 4, y = -tokens.unreadDot / 4)
                .size(tokens.unreadDot + tokens.focusStroke * 2).clip(CircleShape).background(tokens.settingsBackground)
                .padding(tokens.focusStroke).clip(CircleShape).background(tokens.accent)
        )
    }
}

/** A thread row in the unified inbox list. */
@Composable
internal fun ThreadRow(summary: ThreadSummary, launchers: Map<String, android.content.pm.LauncherActivityInfo>, person: () -> Unit, open: () -> Unit) {
    val tokens = LocalTokens.current
    val time = remember(summary.lastAt) {
        DateUtils.getRelativeTimeSpanString(summary.lastAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
    ListRow(
        summary.name, Modifier.appPress(open), summary = summary.preview, summaryLines = 2,
        leading = {
            val personLabel = stringResource(R.string.contact_details_of, summary.name)
            Box(Modifier.size(tokens.listAvatar).appPress(person).semantics { contentDescription = personLabel }) {
                // The row marks groups next to the time instead.
                PersonAvatar(summary, tokens.listAvatar, groupBadge = false)
                summary.packages.singleOrNull()?.let { pkg ->
                    Box(Modifier.align(Alignment.BottomEnd).size(tokens.threadAppIcon + tokens.focusStroke * 2)
                        .clip(CircleShape).background(tokens.widgetSurface).padding(tokens.focusStroke).clip(CircleShape)) {
                        ChatPlatformIcon(pkg, launchers[pkg])
                    }
                }
            }
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(tokens.gap / 2)) {
                Text(time, style = tokens.rowSummary, maxLines = 1)
                if (summary.ref is ThreadRef.Group) {
                    val groupLabel = stringResource(R.string.inbox_group_chat)
                    EnyoGlyph(Glyph.People, Modifier.size(tokens.threadAppIcon), color = tokens.quiet, description = groupLabel)
                }
                if (summary.unread > 0) Box(
                    Modifier.clip(CircleShape).background(tokens.accent).padding(horizontal = tokens.gap / 2),
                    contentAlignment = Alignment.Center
                ) { Text(summary.unread.toString(), style = tokens.pillLabel.copy(color = tokens.onAccent)) }
            }
        }
    )
}

@Composable
private fun ChatPlatformIcon(packageName: String, launcher: android.content.pm.LauncherActivityInfo?) {
    val context = LocalContext.current
    val size = LocalTokens.current.threadAppIcon
    if (launcher != null) AppIcon(launcher, displaySize = size, label = launcher.label.toString())
    else {
        val pixels = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
        val icon by produceState<ImageBitmap?>(null, packageName, pixels) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(packageName).toBitmap(pixels, pixels).asImageBitmap()
                }.getOrNull()
            }
        }
        icon?.let { Image(it, sourceLabel(context, packageName), Modifier.fillMaxSize().clip(CircleShape)) }
    }
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
    var knownConversation by remember(thread) { mutableStateOf<Long?>(null) }
    LaunchedEffect(messages.lastOrNull()?.conversationId) {
        messages.lastOrNull()?.conversationId?.let { knownConversation = it }
    }
    val replacement = rememberArchive<ThreadRef.Person?>(null, thread, summary, knownConversation) { ctx ->
        val conversation = knownConversation
        if (summary != null || thread !is ThreadRef.Person || conversation == null) null
        else ArchiveData.replacementThread(ctx, thread, conversation)
    }
    LaunchedEffect(replacement) {
        replacement?.let { target -> close(); ArchiveData.requestedThread.value = target }
    }
    val personId = (thread as? ThreadRef.Person)?.id
    val identities = rememberArchive(emptyList<ThreadIdentity>(), personId) { if (personId == null) emptyList() else ArchiveData.identities(it, personId) }
    val suggestions = rememberArchive(emptyList<LinkSuggestion>(), personId) { if (personId == null) emptyList() else ArchiveData.suggestions(it, personId) }
    val proposals = rememberArchive(emptyList<DuplicateProposal>(), messages) { ArchiveData.duplicateProposals(it, messages) }
    val active by EnyoNotificationService.entries.collectAsState()
    LaunchedEffect(thread, messages.size) { ArchiveData.markRead(context, thread) }
    var confirmDelete by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var deletingMessage by remember { mutableStateOf<ArchivedMessage?>(null) }
    var acting by remember { mutableStateOf<ArchivedMessage?>(null) }
    var quoting by remember(thread) { mutableStateOf<ArchivedMessage?>(null) }
    var viewing by remember { mutableStateOf<Pair<ArchivedMessage, InboxAttachmentInfo>?>(null) }
    var sendingFiles by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var sendingPlace by remember { mutableStateOf<InboxLocationInfo?>(null) }
    val byApiId = remember(messages) { messages.filter { it.apiId != null }.associateBy { it.packageName to it.apiId } }
    var failed by remember { mutableStateOf(false) }
    var linkingSuggestion by remember { mutableStateOf<LinkSuggestion?>(null) }
    var confirmUnlink by remember { mutableStateOf(false) }
    var savedNote by remember { mutableStateOf<Int?>(null) }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        val contact = uri?.let { ArchiveData.contactOf(context, it) } ?: return@rememberLauncherForActivityResult
        if (identities.isNotEmpty()) {
            ArchiveData.linkToContact(context, identities.map { it.id }, contact.lookup, contact.name)
            close()
        }
    }
    // Saving links into the address book needs write access; without it, links stay in Enyo only.
    val contactsWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val suggestion = linkingSuggestion
        linkingSuggestion = null
        if (suggestion != null) {
            ArchiveData.linkToContact(context, listOf(suggestion.identityId), suggestion.lookup, suggestion.contactName)
            close()
        } else runCatching { pickContact.launch(null) }.onFailure { failed = true }
    }
    val canWriteContacts = { ContactConnections.canWrite(context) }
    val picker = object {
        fun launch(@Suppress("UNUSED_PARAMETER") input: Void?) {
            if (canWriteContacts()) pickContact.launch(null) else contactsWrite.launch(android.Manifest.permission.WRITE_CONTACTS)
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) sendingFiles = uris.take(10)
    }
    val placeScope = rememberCoroutineScope()
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) placeScope.launch { sendingPlace = currentPlace(context); if (sendingPlace == null) failed = true }
    }
    val archivedKeys = remember(messages) { messages.mapNotNull { it.notificationKey }.toSet() }
    val activeKeys = remember(active, archivedKeys) { active.mapNotNull { it.key.takeIf(archivedKeys::contains) }.toSet() }
    val notificationRoutes = rememberArchive(emptyMap<String, Long>(), activeKeys) {
        ArchiveData.notificationConversations(it, activeKeys)
    }
    val scope = rememberCoroutineScope()
    val label = { pkg: String -> launchers[pkg]?.label?.toString() ?: sourceLabel(context, pkg) }

    var accounts by remember { mutableStateOf(false) }
    var flyout by remember { mutableStateOf(false) }
    val routes = rememberArchive(emptyList<ChatRoute>(), thread) { ArchiveData.routes(it, thread) }
    val liveByRoute = remember(active, routes, notificationRoutes) {
        val byId = routes.associateBy { it.conversationId }
        val shortcuts = routes.mapNotNull { route ->
            route.shortcutId?.let { (route.packageName to it) to route }
        }.toMap()
        active.sortedByDescending { it.postTime }.mapNotNull { entry ->
            val shortcut = entry.notification.shortcutId
            val route = if (shortcut != null) shortcuts[entry.packageName to shortcut]
            else byId[notificationRoutes[entry.key]]?.takeIf { it.packageName == entry.packageName }
            route?.conversationId?.let { it to entry }
        }.distinctBy { it.first }.toMap()
    }
    fun routeLabel(route: ChatRoute) = chatRouteLabel(context, route, routes, label(route.packageName))

    val connectors by InboxHost.connectors.collectAsState()
    val targets = remember(routes, liveByRoute, connectors) {
        routes.mapNotNull { route ->
            if (route.inboxId != null && InboxHost.supports(context, route.packageName, InboxHost.featureReply)) {
                ReplyTarget(route, emptyList(), inboxId = route.inboxId)
            } else liveByRoute[route.conversationId]?.let { entry ->
                entry.notification.replyAction()?.let { action ->
                    ReplyTarget(route, action.replyChoices(), inboxId = null, notification = entry to action)
                }
            }
        }
    }
    var pendingReplies by remember { mutableStateOf(setOf<String>()) }
    val failures by InboxHost.failedReplies.collectAsState()
    val sending by InboxHost.pending.collectAsState()
    val inboxRoutes = remember(routes) { routes.mapNotNull { route -> route.inboxId?.let { route.packageName to it } }.toSet() }
    val ownSending = sending.filter { (it.packageName to it.conversation) in inboxRoutes }
    var replyVia by remember { mutableStateOf<Long?>(null) }
    val selected = if (replyVia == null) targets.firstOrNull()
    else targets.firstOrNull { it.route.conversationId == replyVia }
    LaunchedEffect(thread, messages.lastOrNull()?.id, routes) {
        routes.filter { it.inboxId != null }.forEach { InboxHost.markRead(context, it.packageName, it.inboxId!!) }
    }
    val list = rememberLazyListState()
    // The list is laid out from the newest message up, so the newest stays in view as messages arrive.
    LaunchedEffect(messages.lastOrNull()?.id) { if (messages.isNotEmpty()) list.scrollToItem(0) }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(tokens.settingsBackground).safeDrawingPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = tokens.gap, vertical = tokens.gap),
                horizontalArrangement = Arrangement.spacedBy(tokens.gap / 2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThreadIconButton(Glyph.Back, stringResource(R.string.back), run = close)
                val personLabel = stringResource(R.string.contact_details_of, summary?.name.orEmpty())
                Box(Modifier.clip(CircleShape).appPress({ if (summary != null) flyout = true }).semantics { contentDescription = personLabel }) {
                    PersonAvatar(summary, tokens.listAvatar)
                }
                Column(Modifier.weight(1f).padding(start = tokens.gap / 2)) {
                    Text(summary?.name.orEmpty(), style = tokens.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(summary?.packages.orEmpty().joinToString(" · ") { label(it) }, style = tokens.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (personId != null) {
                    ThreadIconButton(Glyph.Contact, stringResource(R.string.thread_link_contact)) {
                        runCatching { picker.launch(null) }.onFailure { failed = true }
                    }
                    ThreadIconButton(Glyph.People, stringResource(R.string.thread_merge)) { merging = true }
                }
                ThreadIconButton(Glyph.More, stringResource(R.string.thread_options)) { accounts = true }
            }
            // Questions about this thread stay above the messages until they are answered.
            if (proposals.isNotEmpty() || suggestions.isNotEmpty() || failed) Column(
                Modifier.fillMaxWidth().padding(horizontal = tokens.margin).padding(bottom = tokens.gap),
                verticalArrangement = Arrangement.spacedBy(tokens.gap)
            ) {
                proposals.firstOrNull()?.let { proposal ->
                    ListCard {
                        ListRow(
                            stringResource(R.string.duplicate_question, label(proposal.providerPackage)),
                            summary = proposal.text, summaryLines = 2,
                            leading = { EnyoGlyph(Glyph.Messages, color = tokens.accent) }
                        )
                        Row(Modifier.padding(start = tokens.padding, end = tokens.padding, bottom = tokens.gap), horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                            PillButton(stringResource(R.string.duplicate_keep)) { ArchiveData.keepBothDuplicates(context, proposal) }
                            PillButton(stringResource(R.string.duplicate_merge), filled = true) { ArchiveData.confirmDuplicate(context, proposal) }
                        }
                    }
                }
                suggestions.firstOrNull()?.let { suggestion ->
                    ListCard {
                        ListRow(
                            stringResource(R.string.thread_suggestion, suggestion.contactName ?: "?"),
                            summary = label(suggestion.packageName),
                            leading = { EnyoGlyph(Glyph.Contact, color = tokens.accent) }
                        )
                        Row(Modifier.padding(start = tokens.padding, end = tokens.padding, bottom = tokens.gap), horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                            PillButton(stringResource(R.string.thread_suggestion_no)) { ArchiveData.dismissSuggestion(context, suggestion) }
                            PillButton(stringResource(R.string.thread_suggestion_link), filled = true) {
                                if (canWriteContacts()) {
                                    ArchiveData.linkToContact(context, listOf(suggestion.identityId), suggestion.lookup, suggestion.contactName)
                                    close()
                                } else {
                                    linkingSuggestion = suggestion
                                    contactsWrite.launch(android.Manifest.permission.WRITE_CONTACTS)
                                }
                            }
                        }
                    }
                }
                if (failed) ListCard { ListNote(stringResource(R.string.action_unavailable)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = list,
                    contentPadding = PaddingValues(horizontal = tokens.margin, vertical = tokens.gap),
                    verticalArrangement = Arrangement.spacedBy(tokens.gap),
                    reverseLayout = true
                ) {
                    items(ownSending.asReversed(), key = { "sending:" + it.request }) { send ->
                        PendingBubble(send, label(send.packageName), failures[send.request] ?: if (send.request in failures) "" else null) {
                            InboxHost.pending.value = InboxHost.pending.value - send
                        }
                    }
                    items(messages.asReversed(), key = { it.id }) { message ->
                        val quoted = message.replyTo?.let { byApiId[message.packageName to it] }
                        val react = reactAction(context, message)
                        MessageBubble(message, label(message.packageName), thread is ThreadRef.Group, quoted, react,
                            open = { attachment -> viewing = message to attachment }) { acting = message }
                    }
                    item(key = "note") {
                        Text(
                            stringResource(R.string.archive_note), Modifier.fillMaxWidth().padding(vertical = tokens.gap),
                            style = tokens.caption, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                val behind by remember { derivedStateOf { list.firstVisibleItemIndex > 2 } }
                if (behind) Box(Modifier.align(Alignment.BottomEnd).padding(tokens.margin)) {
                    ThreadIconButton(Glyph.Down, stringResource(R.string.thread_latest), filled = true) {
                        scope.launch { list.animateScrollToItem(0) }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = tokens.margin, vertical = tokens.gap),
                verticalArrangement = Arrangement.spacedBy(tokens.gap)
            ) {
                if (targets.size > 1 || (selected == null && targets.isNotEmpty())) Row(
                    Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(tokens.gap)
                ) {
                    targets.forEach { target ->
                        PillButton(routeLabel(target.route), filled = selected == target) { replyVia = target.route.conversationId }
                    }
                }
                val richInbox = selected?.inboxId?.let { selected.route.packageName to it }
                quoting?.takeIf { richInbox != null && it.packageName == richInbox.first }?.let { quote ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(tokens.radius / 2)).background(tokens.widgetSurface)
                            .padding(horizontal = tokens.padding, vertical = tokens.gap),
                        horizontalArrangement = Arrangement.spacedBy(tokens.gap), verticalAlignment = Alignment.CenterVertically
                    ) {
                        EnyoGlyph(Glyph.Reply, color = tokens.accent)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.inbox_replying_to, if (quote.outgoing) stringResource(R.string.inbox_you) else quote.sender.orEmpty()),
                                style = tokens.pillLabel.copy(color = tokens.accent), maxLines = 1)
                            Text(spoilerText(quote.text, false).replace('\n', ' '), style = tokens.rowSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ThreadIconButton(Glyph.Close, stringResource(R.string.cancel)) { quoting = null }
                    }
                }
                if (richInbox != null) {
                    val features = connectors.firstOrNull { it.packageName == richInbox.first }?.features.orEmpty()
                    val canAttach = InboxHost.featureSendAttachments in features
                    val canPlace = InboxHost.featureSendLocation in features
                    if (canAttach || canPlace) Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                        if (canAttach) ThreadIconButton(Glyph.Attach, stringResource(R.string.inbox_attach)) {
                            runCatching { filePicker.launch(arrayOf("*/*")) }.onFailure { failed = true }
                        }
                        if (canPlace) ThreadIconButton(Glyph.Location, stringResource(R.string.inbox_send_location)) {
                            if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                                context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) scope.launch { sendingPlace = currentPlace(context); if (sendingPlace == null) failed = true }
                            else locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                        }
                    }
                }
                if (selected != null) key(selected.route.conversationId, selected.notification?.first?.key) {
                    ReplyComposer(
                        stringResource(R.string.thread_reply_hint, routeLabel(selected.route)),
                        selected.choices, autofocus = false, confirm = selected.inboxId == null
                    ) { text ->
                        val inboxId = selected.inboxId
                        val notification = selected.notification
                        val sent = when {
                            inboxId != null -> {
                                val quote = quoting?.takeIf { it.packageName == selected.route.packageName }?.apiId
                                val request = if (quote != null) InboxHost.sendRich(context, selected.route.packageName, inboxId, text, replyTo = quote)
                                    else InboxHost.reply(context, selected.route.packageName, inboxId, text)
                                request?.also { pendingReplies = pendingReplies + it; quoting = null } != null
                            }
                            notification != null -> sendReply(context, notification.first, notification.second, text)
                                .also { if (!it) notification.first.openFromForeground(context) }
                            else -> false
                        }
                        if (sent) scope.launch { list.animateScrollToItem(0) }
                        sent
                    }
                } else Text(stringResource(R.string.thread_no_reply), style = tokens.caption)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    routes.forEach { route ->
                        PillButton(stringResource(R.string.thread_open_in, routeLabel(route))) {
                            failed = !context.openChat(route, liveByRoute[route.conversationId])
                        }
                    }
                    if (routes.isEmpty()) summary?.packages.orEmpty().forEach { pkg ->
                        PillButton(stringResource(R.string.thread_open_in, label(pkg))) {
                            failed = !(launchers[pkg]?.let { openApp(it); true } ?: context.launchNotifyingApp(pkg))
                        }
                    }
                }
            }
        }
    }
    if (flyout && summary != null) ContactFlyout(summary, launchers, openThread = null) { flyout = false }
    if (accounts) EnyoSheet(stringResource(R.string.thread_options), { accounts = false }) {
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
            SettingsAction(stringResource(R.string.thread_link_contact), Glyph.Contact) {
                accounts = false
                runCatching { picker.launch(null) }.onFailure { failed = true }
            }
            if (summary?.contactLookup != null) {
                SettingsAction(stringResource(R.string.thread_save_contact), Glyph.Contact,
                    summary = savedNote?.let { pluralStringResource(R.plurals.thread_saved_contact, it, it) } ?: stringResource(R.string.thread_save_contact_summary)) {
                    if (!canWriteContacts()) contactsWrite.launch(android.Manifest.permission.WRITE_CONTACTS)
                    else scope.launch {
                        savedNote = withContext(Dispatchers.IO) { var added: Int? = null; ArchiveData.saveConnections(context, personId) { added = it }; added }
                        if (savedNote == null) failed = true
                    }
                }
                SettingsAction(stringResource(R.string.thread_unlink_contact), Glyph.Unlink) { accounts = false; confirmUnlink = true }
            }
            SettingsAction(stringResource(R.string.thread_merge), Glyph.People) { accounts = false; merging = true }
            if (identities.size > 1) SettingsAction(stringResource(R.string.thread_unmerge), Glyph.Unlink,
                summary = stringResource(R.string.thread_unmerge_summary)) {
                accounts = false
                ArchiveData.unmerge(context, personId)
                close()
            }
        }
        ListCard {
            SettingsAction(stringResource(R.string.thread_delete), Glyph.Trash) { accounts = false; confirmDelete = true }
        }
    }
    if (confirmUnlink && personId != null) EnyoSheet(stringResource(R.string.thread_unlink_contact), { confirmUnlink = false }) {
        Text(stringResource(R.string.thread_unlink_confirm, summary?.name.orEmpty()), style = tokens.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
            PillButton(stringResource(R.string.cancel)) { confirmUnlink = false }
            PillButton(stringResource(R.string.thread_unlink_contact), filled = true) {
                confirmUnlink = false
                ArchiveData.unlinkContact(context, personId)
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
    acting?.let { message ->
        val react = reactAction(context, message)
        val canQuote = message.apiId != null && message.inboxConversation != null &&
            targets.any { it.inboxId != null && it.route.packageName == message.packageName } &&
            InboxHost.supports(context, message.packageName, InboxHost.featureReplyTo)
        EnyoSheet(stringResource(R.string.inbox_message_actions), { acting = null }) {
            if (react != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                quickReactions.forEach { emoji ->
                    val mine = message.reactions.any { it.emoji == emoji && it.sender.isEmpty() }
                    Box(Modifier.size(tokens.listAvatar).clip(CircleShape).background(if (mine) tokens.accentContainer else tokens.widgetSurface)
                        .appPress({ react(emoji, mine); acting = null }), contentAlignment = Alignment.Center) {
                        Text(emoji, style = tokens.rowTitle)
                    }
                }
            }
            ListCard {
                if (canQuote) SettingsAction(stringResource(R.string.inbox_reply_to_message), Glyph.Reply) {
                    quoting = message
                    replyVia = targets.firstOrNull { it.inboxId != null && it.route.packageName == message.packageName }?.route?.conversationId
                    acting = null
                }
                if (message.content?.once != true) SettingsAction(stringResource(R.string.inbox_copy), Glyph.Note) {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText(null, spoilerText(message.text, true)))
                    acting = null
                }
                SettingsAction(stringResource(R.string.thread_delete_message), Glyph.Trash) { deletingMessage = message; acting = null }
            }
        }
    }
    if (sendingFiles.isNotEmpty() || sendingPlace != null) {
        val target = selected?.takeIf { it.inboxId != null }
        val close = { sendingFiles = emptyList(); sendingPlace = null }
        EnyoSheet(stringResource(if (sendingPlace != null) R.string.inbox_send_location else R.string.inbox_attach), close) {
            if (target == null) ListNote(stringResource(R.string.thread_no_reply))
            sendingPlace?.let { place ->
                Text(stringResource(R.string.inbox_send_location_message, String.format(java.util.Locale.getDefault(), "%.5f, %.5f", place.lat, place.lng)), style = tokens.secondary)
            }
            if (sendingFiles.isNotEmpty()) Text(
                sendingFiles.joinToString("\n") { uri -> runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: uri.lastPathSegment.orEmpty() },
                style = tokens.secondary, maxLines = 6, overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                PillButton(stringResource(R.string.cancel)) { close() }
                PillButton(stringResource(R.string.inbox_send), filled = true, enabled = target != null) {
                    val request = target?.let {
                        InboxHost.sendRich(context, it.route.packageName, it.inboxId!!, "",
                            replyTo = quoting?.takeIf { quote -> quote.packageName == it.route.packageName }?.apiId,
                            attachments = sendingFiles, location = sendingPlace)
                    }
                    if (request != null) { pendingReplies = pendingReplies + request; quoting = null } else failed = true
                    close()
                }
            }
        }
    }
    viewing?.let { (message, attachment) -> InboxMediaViewer(message, attachment) { viewing = null } }
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
                            ArchiveData.mergePeople(context, personId, target)
                            merging = false
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

/** A small mark for where one of the person's messages is, like the ticks of chat apps. */
@Composable
internal fun StatusMark(status: MessageStatus) {
    val tokens = LocalTokens.current
    val (glyph, label) = when (status) {
        MessageStatus.Sending -> Glyph.Clock to R.string.status_sending
        MessageStatus.Failed -> Glyph.Alert to R.string.status_failed
        MessageStatus.Sent -> Glyph.Check to R.string.status_sent
        MessageStatus.Received -> Glyph.Checks to R.string.status_received
        MessageStatus.Read -> Glyph.Checks to R.string.status_read
    }
    val color = when (status) {
        MessageStatus.Read -> tokens.accent
        MessageStatus.Failed -> tokens.danger
        else -> tokens.quiet
    }
    val description = stringResource(label)
    EnyoGlyph(glyph, Modifier.size(tokens.threadAppIcon).semantics { contentDescription = description }, color = color)
}

/** Something the person sent from Enyo that the app has not confirmed yet; a failed one can be dismissed. */
@Composable
private fun PendingBubble(send: InboxHost.PendingSend, app: String, failure: String?, dismiss: () -> Unit) {
    val tokens = LocalTokens.current
    val failed = failure != null
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.widthIn(max = tokens.bubbleMaxWidth).clip(RoundedCornerShape(tokens.radius)).background(tokens.accentContainer)
                .then(if (failed) Modifier.appPress(dismiss) else Modifier)
                .padding(horizontal = tokens.padding, vertical = tokens.gap),
            horizontalAlignment = Alignment.End
        ) {
            val parts = listOfNotNull(
                send.text.takeIf { it.isNotBlank() },
                send.files.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.status_files, it, it) },
                if (send.place) stringResource(R.string.inbox_location) else null
            )
            ChatMarkdown(parts.joinToString("\n"), tokens.rowTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (failed) listOfNotNull(stringResource(R.string.reply_failed_app), failure?.ifBlank { null }).joinToString(" ") else app,
                    Modifier.weight(1f, fill = false), style = tokens.rowSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                StatusMark(if (failed) MessageStatus.Failed else MessageStatus.Sending)
            }
        }
    }
}

private val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** Reacting to a shared message through its app, when the app supports it. */
private fun reactAction(context: android.content.Context, message: ArchivedMessage): ((String, Boolean) -> Unit)? {
    val conversation = message.inboxConversation ?: return null
    val id = message.apiId ?: return null
    if (message.content?.system == true || !InboxHost.supports(context, message.packageName, InboxHost.featureReact)) return null
    return { emoji, remove -> InboxHost.react(context, message.packageName, conversation, id, emoji, remove) }
}

/** The phone's current place, for sending it to a chat; null without a fix within a few seconds. */
private suspend fun currentPlace(context: android.content.Context): InboxLocationInfo? = withContext(Dispatchers.IO) {
    val manager = context.getSystemService(android.location.LocationManager::class.java)
    val provider = listOf(android.location.LocationManager.FUSED_PROVIDER, android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
        .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return@withContext null
    val fix = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
        kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { done ->
            val cancel = android.os.CancellationSignal()
            done.invokeOnCancellation { cancel.cancel() }
            runCatching {
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) { location -> if (done.isActive) done.resumeWith(Result.success(location)) }
            }.onFailure { if (done.isActive) done.resumeWith(Result.success(null)) }
        }
    }
    fix?.let { InboxLocationInfo(it.latitude, it.longitude, it.accuracy.toDouble(), null, false, null) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ArchivedMessage,
    app: String,
    showSender: Boolean,
    quoted: ArchivedMessage?,
    react: ((String, Boolean) -> Unit)?,
    open: (InboxAttachmentInfo) -> Unit,
    actions: () -> Unit
) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val time = remember(message.postedAt) {
        DateUtils.formatDateTime(context, message.postedAt, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
    }
    val content = message.content
    // Notices such as someone joining sit in the middle of the chat, without a bubble.
    if (content?.system == true) {
        Text(message.text, Modifier.fillMaxWidth().combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = actions)
            .padding(vertical = tokens.gap / 2), style = tokens.caption, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        return
    }
    val spoilers = remember(message.text) { hasSpoiler(message.text) }
    var revealed by remember(message.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
        Column(
            Modifier.widthIn(max = tokens.bubbleMaxWidth)
                .clip(RoundedCornerShape(tokens.radius))
                .background(if (message.outgoing) tokens.accentContainer else tokens.widgetSurface)
                .combinedClickable(interactionSource = null, indication = null, onClick = { if (spoilers) revealed = !revealed }, onLongClick = actions)
                .padding(horizontal = tokens.padding, vertical = tokens.gap),
            horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start
        ) {
            if (showSender && !message.outgoing && message.sender != null) Text(message.sender, style = tokens.pillLabel.copy(color = tokens.accent))
            InboxMessageParts(message, quoted, revealed, open) {
                val described = content != null && content.description && !content.empty
                if (!described) ChatMarkdown(spoilerText(message.text, revealed), tokens.rowTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$app · $time", Modifier.weight(1f, fill = false), style = tokens.rowSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                message.status?.let { StatusMark(it) }
                if (content?.once != true) AiSummaryButton(message.text)
            }
        }
        ReactionRow(message.reactions, message.outgoing, react)
    }
}

/** Where a thread's reply goes: a UnifiedInbox connector, or the reply action of a live notification. */
private data class ReplyTarget(
    val route: ChatRoute,
    val choices: List<String>,
    val inboxId: String?,
    val notification: Pair<android.service.notification.StatusBarNotification, android.app.Notification.Action>? = null
)

/** A round icon action in the thread bar. */
@Composable
private fun ThreadIconButton(glyph: Glyph, label: String, filled: Boolean = false, run: () -> Unit) {
    val tokens = LocalTokens.current
    Box(
        Modifier.size(tokens.touch).clip(CircleShape).then(if (filled) Modifier.background(tokens.accent) else Modifier)
            .appPress(run).semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { EnyoGlyph(glyph, color = if (filled) tokens.onAccent else tokens.onAccentContainer) }
}
