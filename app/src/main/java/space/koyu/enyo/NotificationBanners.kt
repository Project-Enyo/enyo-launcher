package space.koyu.enyo

import android.app.KeyguardManager
import android.app.Notification
import android.content.pm.LauncherActivityInfo
import android.service.notification.StatusBarNotification
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
    // The tray grows out of the banner in the same card; when it closes with no banner waiting, it leaves as a tray.
    var lastGroup by remember { mutableStateOf<NoticeGroup?>(null) }
    SideEffect { if (expanded != null) lastGroup = expanded }
    val target = expanded ?: if (shown == null) lastGroup else null
    val elevation by animateDpAsState(if (expanded != null) tokens.elevation[2] else tokens.elevation[1],
        tween(tokens.settingsTransitionMillis), label = "bannerElevation")
    AnimatedVisibility(
        visible = shown != null || expanded != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.margin, vertical = tokens.gap),
            color = tokens.widgetSurface,
            contentColor = tokens.onAccentContainer,
            shape = RoundedCornerShape(tokens.sheetRadius),
            shadowElevation = elevation
        ) {
            AnimatedContent(
                targetState = target,
                transitionSpec = {
                    val opening = targetState != null
                    val fadeIn = fadeIn(tween(tokens.sheetArrivalMillis, delayMillis = tokens.sheetArrivalMillis / 3)) +
                        scaleIn(tween(tokens.sheetArrivalMillis, delayMillis = tokens.sheetArrivalMillis / 3), initialScale = if (opening) 0.96f else 1.02f)
                    (fadeIn togetherWith fadeOut(tween(tokens.sheetArrivalMillis / 3)))
                        .using(SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow) })
                },
                contentAlignment = Alignment.BottomCenter,
                label = "bannerTray"
            ) { group ->
                if (group == null) (shown ?: exiting)?.let { initial ->
                    val entry = if (initial.isModelDownload(context.packageName)) {
                        active.firstOrNull { it.key == initial.key } ?: initial
                    } else initial
                    val bannerGroup = entry.noticeGroup()
                    val label = if (bannerGroup.messaging) stringResource(R.string.messages) else
                        apps.firstOrNull { it.componentName.packageName == entry.packageName }?.label?.toString()
                            ?: sourceLabel(context, entry.packageName)
                    val payload = entry.noticePayload()
                    // The chevron turns to point down as the banner opens into the tray.
                    val turn by animateFloatAsState(if (expanded != null) 90f else 0f, tween(tokens.sheetArrivalMillis), label = "bannerChevron")
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = tokens.touch).appPress({ expanded = bannerGroup; current = null })
                            .padding(tokens.padding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(tokens.gap)
                    ) {
                        EnyoGlyph(if (bannerGroup.messaging) Glyph.Messages else Glyph.Inbox, color = tokens.accent)
                        Column(Modifier.weight(1f)) {
                            Text(label, style = tokens.state, color = tokens.onAccentContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            payload.sender?.let { Text(it, style = tokens.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            payload.body?.let { Text(it, style = tokens.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            entry.modelDownloadProgress(context.packageName)?.let { progress ->
                                LinearProgressIndicator(progress = { progress },
                                    modifier = Modifier.fillMaxWidth().padding(top = tokens.gap / 2),
                                    color = tokens.accent, trackColor = tokens.widgetTrack)
                            }
                        }
                        EnyoGlyph(Glyph.Forward, Modifier.graphicsLayer { rotationZ = turn }, color = tokens.quiet)
                    }
                } else {
                    val title = if (group.messaging) stringResource(R.string.messages) else stringResource(R.string.inbox)
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
    }
}

internal const val bannerLifetimeMillis = 6000L
