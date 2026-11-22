package space.koyu.enyo

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.delay

internal fun Notification.replyAction(): Notification.Action? =
    actions.orEmpty().firstOrNull { it.actionIntent != null && it.remoteInputs.orEmpty().any(RemoteInput::getAllowFreeFormInput) }

/** The canned answers the app or the system offers with the reply action, such as smart replies. */
internal fun Notification.Action.replyChoices(): List<String> =
    remoteInputs.orEmpty().flatMap { it.choices.orEmpty().map(CharSequence::toString) }.filter { it.isNotBlank() }.distinct().take(3)

/**
 * Sends a reply through the notification's own RemoteInput action, only after the person submits it,
 * and keeps the reply in the archive so the thread shows it at once.
 */
internal fun sendReply(context: Context, entry: StatusBarNotification?, action: Notification.Action, text: String): Boolean = runCatching {
    val input = action.remoteInputs.first { it.allowFreeFormInput }
    val intent = Intent()
    RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, text) })
    RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
    action.actionIntent.send(context, 0, intent)
    if (entry != null) MessageArchive.get(context).recordReply(entry, text)
    true
}.getOrDefault(false)

internal data class ReplyLine(val sender: String?, val text: String, val outgoing: Boolean)

/** The last few lines of a messaging notification, oldest first, for context above a reply field. */
internal fun StatusBarNotification.recentLines(count: Int = 3): List<ReplyLine> {
    val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) }.getOrNull()
    if (style != null && style.messages.isNotEmpty()) return style.messages.takeLast(count).mapNotNull { message ->
        val text = message.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val self = message.person == null || message.person?.key?.let { it == style.user.key } == true
        ReplyLine(message.person?.name?.toString(), text, self)
    }
    val payload = noticePayload()
    return listOfNotNull(payload.body?.let { ReplyLine(payload.sender, it, false) })
}

/**
 * The shared reply field: suggested answers, a rounded input that sends with the keyboard's send
 * key, and a round send button. [send] returns whether the app accepted the reply.
 */
@Composable
internal fun ReplyComposer(
    hint: String,
    choices: List<String>,
    autofocus: Boolean,
    modifier: Modifier = Modifier,
    send: (String) -> Boolean
) {
    val tokens = LocalTokens.current
    var draft by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(status) {
        if (status == R.string.reply_sent) {
            delay(replyStatusMillis)
            status = null
        }
    }
    fun submit(text: String) {
        if (text.isBlank()) return
        if (send(text.trim())) {
            draft = ""
            status = R.string.reply_sent
        } else status = R.string.reply_failed
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
        if (choices.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(tokens.gap)
        ) { choices.forEach { choice -> PillButton(choice) { submit(choice) } } }
        MessageComposer(draft, { draft = it; status = null }, hint, autofocus = autofocus) { submit(draft) }
        status?.let {
            Text(stringResource(it), style = tokens.caption,
                color = if (it == R.string.reply_failed) tokens.danger else tokens.quiet)
        }
    }
}

@Composable
internal fun MessageComposer(
    draft: String,
    onDraft: (String) -> Unit,
    hint: String,
    enabled: Boolean = true,
    autofocus: Boolean = false,
    send: () -> Unit
) {
    val tokens = LocalTokens.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(autofocus) {
        if (autofocus) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    val canSend = enabled && draft.isNotBlank()
    val shape = RoundedCornerShape(tokens.radius)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.gap),
        verticalAlignment = Alignment.Bottom) {
        BasicTextField(
            value = draft, onValueChange = onDraft, enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = tokens.touch).focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .clip(shape).background(tokens.widgetTrack)
                .border(tokens.focusStroke / 2, if (focused) tokens.accent else Color.Transparent, shape)
                .padding(horizontal = tokens.padding, vertical = tokens.gap)
                .semantics { contentDescription = hint },
            textStyle = tokens.body.copy(color = tokens.ink),
            maxLines = 5,
            cursorBrush = SolidColor(tokens.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) send() }),
            decorationBox = { field ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) Text(hint, style = tokens.body.copy(color = tokens.quiet),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                }
            }
        )
        val sendLabel = stringResource(R.string.message_send)
        IconButton(onClick = send, enabled = canSend, modifier = Modifier.size(tokens.touch).clip(CircleShape)
            .background(if (canSend) tokens.accent else tokens.widgetTrack).semantics { contentDescription = sendLabel }) {
            EnyoGlyph(Glyph.Send, Modifier.size(tokens.glyphSize), color = if (canSend) tokens.onAccent else tokens.quiet)
        }
    }
}

@Composable
internal fun ConversationView(
    title: String,
    subtitle: String? = null,
    close: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalTokens.current
    Dialog(onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = tokens.settingsBackground, contentColor = tokens.ink) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = tokens.margin, vertical = tokens.gap),
                    horizontalArrangement = Arrangement.spacedBy(tokens.gap), verticalAlignment = Alignment.CenterVertically) {
                    val back = stringResource(R.string.back)
                    IconButton(onClick = close, modifier = Modifier.size(tokens.touch)
                        .semantics { contentDescription = back }) { EnyoGlyph(Glyph.Back) }
                    Column(Modifier.weight(1f)) {
                        Text(title, Modifier.semantics { heading() }, style = tokens.state,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!subtitle.isNullOrBlank() && subtitle != title) Text(subtitle, style = tokens.caption,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    action?.invoke()
                }
                content()
            }
        }
    }
}

@Composable
internal fun ConversationBubble(text: String, outgoing: Boolean, sender: String? = null, maxLines: Int = Int.MAX_VALUE) {
    val tokens = LocalTokens.current
    Box(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(Modifier.widthIn(max = tokens.bubbleMaxWidth).clip(RoundedCornerShape(tokens.radius))
            .background(if (outgoing) tokens.accentContainer else tokens.widgetTrack).padding(tokens.padding)) {
            sender?.let { Text(it, style = tokens.caption, color = tokens.accent, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(text, style = tokens.body, color = if (outgoing) tokens.onAccentContainer else tokens.ink,
                maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Displays the notification conversation while its composer stays above the keyboard. */
@Composable
internal fun NotificationReplyScreen(
    entry: StatusBarNotification,
    appLabel: String,
    close: () -> Unit,
    openApp: () -> Unit
) {
    val tokens = LocalTokens.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val action = entry.notification.replyAction() ?: return
    val payload = remember(entry) { entry.noticePayload() }
    val title = entry.notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        ?: payload.sender ?: appLabel
    val lines = remember(entry) { entry.recentLines() }
    ConversationView(title, appLabel, close, action = {
        val open = stringResource(R.string.message_open)
        IconButton(onClick = { close(); openApp() }, modifier = Modifier.size(tokens.touch)
            .semantics { contentDescription = open }) { EnyoGlyph(Glyph.External) }
    }) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f), reverseLayout = true,
            contentPadding = PaddingValues(tokens.margin),
            verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
            items(lines.asReversed()) { line ->
                ConversationBubble(line.text, line.outgoing, line.sender?.takeUnless { line.outgoing || it == title })
            }
        }
        ReplyComposer(stringResource(R.string.message_reply), action.replyChoices(), autofocus = true,
            modifier = Modifier.padding(horizontal = tokens.margin, vertical = tokens.gap)) { text ->
            sendReply(context, entry, action, text).also { if (it) close() }
        }
    }
}

private const val replyStatusMillis = 2500L
