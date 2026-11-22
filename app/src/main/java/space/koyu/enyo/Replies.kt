package space.koyu.enyo

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
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

/** The last few lines of a messaging notification, oldest first, for context above a reply field. */
internal fun StatusBarNotification.recentLines(count: Int = 3): List<Pair<String?, String>> {
    val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification) }.getOrNull()
    if (style != null && style.messages.isNotEmpty()) return style.messages.takeLast(count).mapNotNull { message ->
        val text = message.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val self = message.person == null || message.person?.key?.let { it == style.user.key } == true
        (if (self) null else message.person?.name?.toString()) to text
    }
    val payload = noticePayload()
    return listOfNotNull(payload.body?.let { payload.sender to it })
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
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(autofocus) {
        if (autofocus) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
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
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap), verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = draft, onValueChange = { value: String -> draft = value; status = null },
                modifier = Modifier.weight(1f).heightIn(min = tokens.touch).focusRequester(focus)
                    .clip(RoundedCornerShape(tokens.touch / 2)).background(tokens.widgetTrack)
                    .padding(horizontal = tokens.padding, vertical = tokens.gap * 1.5f)
                    .semantics { contentDescription = hint },
                textStyle = tokens.body,
                maxLines = 5,
                cursorBrush = SolidColor(tokens.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit(draft) }),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) Text(hint, style = tokens.body.copy(color = tokens.quiet), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        field()
                    }
                }
            )
            val sendLabel = stringResource(R.string.message_send)
            val enabled = draft.isNotBlank()
            Box(
                Modifier.size(tokens.touch).clip(CircleShape).background(tokens.accent)
                    .graphicsLayer { alpha = if (enabled) 1f else tokens.disabledOpacity }
                    .then(if (enabled) Modifier.appPress({ submit(draft) }) else Modifier)
                    .semantics { contentDescription = sendLabel },
                contentAlignment = Alignment.Center
            ) { EnyoGlyph(Glyph.Send, Modifier.size(tokens.glyphSize), color = tokens.onAccent) }
        }
        status?.let { Text(stringResource(it), style = tokens.caption) }
    }
}

/** A reply sheet for one notification: who it is, the latest lines, and the composer. */
@Composable
internal fun NotificationReplySheet(
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
    EnyoSheet(title, close) {
        Text(appLabel, style = tokens.caption)
        val lines = remember(entry) { entry.recentLines() }
        Column(verticalArrangement = Arrangement.spacedBy(tokens.gap / 2)) {
            lines.forEach { (sender, text) ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (sender == null) Alignment.CenterEnd else Alignment.CenterStart) {
                    Column(
                        Modifier.widthIn(max = tokens.bubbleMaxWidth).clip(RoundedCornerShape(tokens.radius))
                            .background(if (sender == null) tokens.accentContainer else tokens.widgetTrack)
                            .padding(horizontal = tokens.padding, vertical = tokens.gap)
                    ) {
                        if (sender != null && sender != title) Text(sender, style = tokens.caption.copy(color = tokens.accent))
                        Text(text, style = tokens.body, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        ReplyComposer(
            stringResource(R.string.thread_reply_hint, appLabel), action.replyChoices(), autofocus = true
        ) { text -> sendReply(context, entry, action, text).also { if (it) close() } }
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
            PillButton(stringResource(R.string.message_open)) { close(); openApp() }
        }
    }
}

private const val replyStatusMillis = 2500L
