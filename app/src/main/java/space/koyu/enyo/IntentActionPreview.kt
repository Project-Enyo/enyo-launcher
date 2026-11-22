package space.koyu.enyo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

internal sealed interface LauncherSearchAction

private data class InboxDraftTarget(
    val person: String,
    val route: ChatRoute,
    val reply: (String) -> String?
)
private data class DraftOptions(val sms: List<Pair<String, String>>, val chats: List<InboxDraftTarget>)

@Composable
internal fun IntentActionPreview(action: LauncherSearchAction, proofread: Boolean = false, openIntent: (Intent) -> Boolean) {
    val context = LocalContext.current
    var status by remember(action) { mutableIntStateOf(0) }
    when (action) {
        is MessageDraft -> MessageDraftPreview(action, proofread, openIntent)
        is DeviceSearchAction -> ListCard {
            val (title, glyph) = when (action) {
                is DeviceSearchAction.Torch -> stringResource(if (action.enabled) R.string.search_torch_on else R.string.search_torch_off) to Glyph.Quick
                is DeviceSearchAction.Alarm -> {
                    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
                    val format = DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(context)) "Hm" else "hm")
                    stringResource(R.string.search_set_alarm, action.time.format(DateTimeFormatter.ofPattern(format, locale))) to Glyph.Alarm
                }
                is DeviceSearchAction.Timer -> stringResource(R.string.search_set_timer, action.seconds / 60, action.seconds % 60) to Glyph.Clock
            }
            SettingsAction(title, glyph) {
                val result = action.execute(context, openIntent)
                status = when {
                    !result -> R.string.action_unavailable
                    action is DeviceSearchAction.Torch && action.enabled -> R.string.enabled
                    action is DeviceSearchAction.Torch -> R.string.disabled
                    else -> 0
                }
            }
            if (status != 0) ListNote(stringResource(status))
        }
    }
}

@Composable
private fun MessageDraftPreview(draft: MessageDraft, autoProofread: Boolean, openIntent: (Intent) -> Boolean) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val active by EnyoNotificationService.entries.collectAsState()
    val connectors by InboxHost.connectors.collectAsState()
    val revision by remember(context) { MessageArchive.get(context).revision }.collectAsState()
    val options by key(draft.recipient) { produceState<DraftOptions?>(null, draft.recipient, active, connectors, revision) {
        value = withContext(Dispatchers.IO) {
            val recipient = draft.recipient
            val activeMessages = active.filter { it.isMessagingNotice() && it.notification.replyAction() != null }
            val keys = ArchiveData.notificationConversations(context, activeMessages.mapTo(mutableSetOf()) { it.key })
            val chats = ArchiveData.threads(context).filter { it.ref is ThreadRef.Person && it.name.contains(recipient, ignoreCase = true) }
                .take(10).flatMap { person ->
                    ArchiveData.routes(context, person.ref).mapNotNull { route ->
                        val providerId = route.inboxId
                        if (providerId != null && InboxHost.supports(context, route.packageName, InboxHost.featureReply)) {
                            InboxDraftTarget(person.name, route) { body ->
                                InboxHost.reply(context, route.packageName, providerId, body)
                            }
                        } else {
                            val live = activeMessages.firstOrNull { entry ->
                                entry.packageName == route.packageName && if (entry.notification.shortcutId != null) {
                                    entry.notification.shortcutId == route.shortcutId
                                } else keys[entry.key] == route.conversationId
                            } ?: return@mapNotNull null
                            val reply = live.notification.replyAction() ?: return@mapNotNull null
                            InboxDraftTarget(person.name, route) { body ->
                                if (sendReply(context, live, reply, body)) "" else null
                            }
                        }
                    }
                }.distinctBy { it.route.conversationId }
            val sms = when {
                recipient.matches(Regex("""[+\d][\d ().-]{2,}""")) -> listOf(recipient to recipient)
                context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED -> emptyList()
                else -> runCatching {
                    val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(recipient))
                    context.contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)
                        ?.use { cursor ->
                            buildList {
                                while (size < 20 && cursor.moveToNext()) {
                                    val number = cursor.getString(1) ?: continue
                                    add((cursor.getString(0) ?: number) to number)
                                }
                            }
                        }.orEmpty().distinctBy { it.second.filter(Char::isDigit) }
                }.getOrDefault(emptyList())
            }
            DraftOptions(sms, chats)
        }
    } }
    val ready = localAssistantReady()
    val scope = rememberCoroutineScope()
    var proofreading by remember(draft) { mutableStateOf(false) }
    var proofreadFailed by remember(draft) { mutableStateOf(false) }
    var corrected by remember(draft) { mutableStateOf<String?>(null) }
    suspend fun checkWording() {
        proofreading = true
        try {
            corrected = proofreadDraft(context, draft.body)
            proofreadFailed = corrected == null
        } finally {
            proofreading = false
        }
    }
    LaunchedEffect(draft, autoProofread, ready) {
        if (autoProofread && ready) checkWording()
    }
    val body = corrected ?: draft.body
    var selected by remember(draft.recipient) { mutableStateOf<InboxDraftTarget?>(null) }
    var missingHandler by remember(draft) { mutableStateOf(false) }
    SettingsPanel(stringResource(R.string.search_message_draft)) {
        ListNote(body)
        if (ready) SettingsAction(stringResource(if (proofreading) R.string.local_ai_working
            else if (corrected == null) R.string.search_proofread else R.string.search_original_wording),
            Glyph.Sparkles, enabled = !proofreading) {
            if (corrected != null) corrected = null
            else {
                scope.launch { checkWording() }
            }
        }
        if (proofreadFailed) ListNote(stringResource(R.string.local_ai_failed))
        val available = options
        if (available == null) ListNote(stringResource(R.string.search_finding_reply_routes))
        else {
            available.chats.forEach { target ->
                val provider = chatRouteLabel(context, target.route, available.chats.map { it.route },
                    sourceLabel(context, target.route.packageName))
                SettingsAction(stringResource(R.string.search_reply_to, target.person, provider), Glyph.Message) { selected = target }
            }
            available.sms.forEach { (name, number) ->
                SettingsAction(stringResource(R.string.search_sms_to, name), Glyph.Message, summary = number.takeIf { it != name }) {
                    missingHandler = !openIntent(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
                        .putExtra("sms_body", body))
                }
            }
            if (available.sms.isEmpty() && available.chats.isEmpty()) {
                ListNote(stringResource(R.string.search_draft_no_recipient, draft.recipient))
            }
        }
        if (missingHandler) ListNote(stringResource(R.string.action_unavailable))
    }
    selected?.let { target ->
        val provider = sourceLabel(context, target.route.packageName)
        var text by remember(target) { mutableStateOf(body) }
        var request by remember(target) { mutableStateOf<String?>(null) }
        var failed by remember(target) { mutableStateOf(false) }
        val failures by InboxHost.failedReplies.collectAsState()
        val error = request?.let { it in failures } == true
        EnyoSheet(stringResource(R.string.search_reply_to, target.person, provider), { selected = null }) {
            if (failed || error) Text(stringResource(R.string.reply_failed), style = tokens.caption, color = tokens.danger)
            if (request != null && !error) ListNote(stringResource(R.string.reply_sent))
            else MessageComposer(text, { text = it }, stringResource(R.string.message_reply)) {
                val current = options?.chats?.firstOrNull { it.route.conversationId == target.route.conversationId }
                request = runCatching { current?.reply?.invoke(text.trim()) }.getOrNull()
                failed = request == null
            }
        }
    }
}
