package space.koyu.enyo

import android.app.Notification
import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import android.app.KeyguardManager
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class EnyoNotificationService : NotificationListenerService() {
    private var overlays: OverlayBanners? = null

    override fun onListenerConnected() {
        connected = this
        if (overlays == null) overlays = OverlayBanners(this)
        refresh()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val previous = entries.value.firstOrNull { it.key == sbn.key }
        refresh()
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val current = entries.value.firstOrNull { it.key == sbn.key } ?: return
        if (current.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (previous == null || previous.noticePayload() != current.noticePayload()) arrivals.tryEmit(current)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()
    override fun onListenerDisconnected() {
        if (connected === this) connected = null
        overlays?.close()
        overlays = null
        entries.value = emptyList()
        brandIcons.value = emptyMap()
        recentConversations.value = emptyList()
    }
    override fun onDestroy() {
        if (connected === this) connected = null
        overlays?.close()
        overlays = null
        entries.value = emptyList()
        brandIcons.value = emptyMap()
        recentConversations.value = emptyList()
        super.onDestroy()
    }

    private fun refresh() {
        entries.value = runCatching {
            val local = activeNotifications.orEmpty().filter { it.user == Process.myUserHandle() }
            brandIcons.value = local.mapNotNull { entry ->
                val icon = entry.notification.smallIcon
                if (entry.packageName in brandedNotificationApps && icon != null) entry.packageName to icon else null
            }.toMap()
            val visible = local.filterNot { it.isBackgroundNotice() }
            if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                recentConversations.value = (visible.mapNotNull { runCatching { it.recentConversation(this) }.getOrNull() } + recentConversations.value)
                    .sortedByDescending { it.postedAt }
                    .distinctBy { it.key }
                    .take(10)
            }
            visible
        }.getOrDefault(emptyList())
    }

    companion object {
        private var connected: EnyoNotificationService? = null

        internal fun dismiss(key: String): Boolean {
            val listener = connected ?: return false
            if (entries.value.none { it.key == key && it.isClearable }) return false
            return runCatching { listener.cancelNotification(key); true }.getOrDefault(false)
        }

        internal val brandIcons = MutableStateFlow<Map<String, android.graphics.drawable.Icon>>(emptyMap())
        internal val arrivals = MutableSharedFlow<StatusBarNotification>(extraBufferCapacity = 8)
        internal val recentConversations = MutableStateFlow<List<RecentConversation>>(emptyList())
        internal val entries = MutableStateFlow<List<StatusBarNotification>>(emptyList())
    }
}

internal data class RecentConversation(
    val key: String,
    val packageName: String,
    val name: String,
    val avatar: Icon?,
    val open: PendingIntent?,
    val postedAt: Long
)

private fun StatusBarNotification.recentConversation(context: android.content.Context): RecentConversation? {
    if (!isMessagingNotice()) return null
    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        ?: return null
    val latest = style.messages.lastOrNull() ?: return null
    val name = (if (style.isGroupConversation) style.conversationTitle else latest.person?.name)
        ?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val shortcut = notification.shortcutId?.takeIf { it.isNotBlank() }
    val identity = if (shortcut != null) "$packageName:shortcut:$shortcut" else "$packageName:notice:$key"
    return RecentConversation(
        identity, packageName, name,
        if (style.isGroupConversation) null else latest.person?.icon?.let { runCatching { it.toIcon(context) }.getOrNull() },
        notification.contentIntent, postTime
    )
}

private fun StatusBarNotification.isBackgroundNotice(): Boolean {
    if (packageName == "moe.zhs.caffeine" ||
        notification.category == Notification.CATEGORY_TRANSPORT ||
        notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    ) return true
    val flags = notification.flags
    val persistent = flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR) != 0
    if (!persistent) return false
    return when (packageName) {
        "org.unifiedpush.distributor.sunup" -> true
        "io.heckel.ntfy" -> notification.channelId == "ntfy-subscriber"
        "org.kde.kdeconnect_tp" -> notification.channelId == "media_control" || isOngoing
        "android" -> notification.channelId == "DEVELOPER_IMPORTANT" &&
            (notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.contains("ADB", ignoreCase = true) == true ||
                notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.contains("debug", ignoreCase = true) == true)
        else -> false
    }
}

internal val brandedNotificationApps = setOf("com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go")
