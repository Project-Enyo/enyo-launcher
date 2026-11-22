package space.koyu.enyo

import android.app.KeyguardManager
import android.app.Notification
import android.content.pm.LauncherActivityInfo
import android.service.notification.StatusBarNotification
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

internal data class NoticeGroup(val messaging: Boolean, val packageName: String?)

internal fun StatusBarNotification.noticeGroup(): NoticeGroup =
    if (isMessagingNotice()) NoticeGroup(true, null) else NoticeGroup(false, packageName)

internal data class NoticePayload(val sender: String?, val body: String?, val timestamp: Long)

internal fun StatusBarNotification.noticePayload(): NoticePayload {
    val latest = runCatching {
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)?.messages?.lastOrNull()
    }.getOrNull()
    val extra = notification.extras
    return NoticePayload(
        latest?.person?.name?.toString() ?: extra.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        latest?.text?.toString() ?: (extra.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extra.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
        latest?.timestamp ?: notification.`when`
    )
}

@Composable
internal fun NotificationBanners(apps: List<LauncherActivityInfo>, open: (LauncherActivityInfo) -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active by EnyoNotificationService.entries.collectAsState()
    var current by remember { mutableStateOf<StatusBarNotification?>(null) }
    var expanded by remember { mutableStateOf<NoticeGroup?>(null) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                EnyoNotificationService.arrivals.collect { incoming ->
                    if (!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                        expanded != incoming.noticeGroup()
                    ) current = incoming
                }
            } finally {
                current = null
                expanded = null
            }
        }
    }
    LaunchedEffect(current) {
        if (current != null) {
            delay(bannerLifetimeMillis)
            current = null
        }
    }
    LaunchedEffect(active) {
        current?.let { banner -> if (active.none { it.key == banner.key }) current = null }
        expanded?.let { group -> if (active.none { it.noticeGroup() == group }) expanded = null }
    }

    val shown = current
    var exiting by remember { mutableStateOf<StatusBarNotification?>(null) }
    SideEffect { if (shown != null) exiting = shown }
    if (expanded == null) AnimatedVisibility(
        visible = shown != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        (shown ?: exiting)?.let { entry ->
            val group = entry.noticeGroup()
            val label = if (group.messaging) stringResource(R.string.messages) else
                apps.firstOrNull { it.componentName.packageName == entry.packageName }?.label?.toString()
                    ?: sourceLabel(context, entry.packageName)
            val payload = entry.noticePayload()
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.margin, vertical = tokens.gap),
                color = tokens.widgetSurface,
                contentColor = tokens.onAccentContainer,
                shape = RoundedCornerShape(tokens.sheetRadius),
                shadowElevation = tokens.elevation[1]
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = tokens.touch).appPress({ expanded = group; current = null })
                        .padding(tokens.padding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(tokens.gap)
                ) {
                    EnyoGlyph(if (group.messaging) Glyph.Messages else Glyph.Inbox, color = tokens.accent)
                    Column(Modifier.weight(1f)) {
                        Text(label, style = tokens.state, color = tokens.onAccentContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        payload.sender?.let { Text(it, style = tokens.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        payload.body?.let { Text(it, style = tokens.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    EnyoGlyph(Glyph.Forward, color = tokens.quiet)
                }
            }
        }
    }
    expanded?.let { group ->
        val title = if (group.messaging) stringResource(R.string.messages) else stringResource(R.string.inbox)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.margin, vertical = tokens.gap),
            color = tokens.widgetSurface,
            contentColor = tokens.onAccentContainer,
            shape = RoundedCornerShape(tokens.sheetRadius),
            shadowElevation = tokens.elevation[2]
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = tokens.padding), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = tokens.state, color = tokens.onAccentContainer)
                    val label = stringResource(R.string.back)
                    Box(Modifier.size(tokens.touch).appPress({ expanded = null })
                        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                        EnyoGlyph(Glyph.Close)
                    }
                }
                Box(Modifier.fillMaxWidth().height(tokens.notificationTrayHeight)) {
                    InboxPage(apps, open, group)
                }
            }
        }
    }
}

internal const val bannerLifetimeMillis = 6000L
