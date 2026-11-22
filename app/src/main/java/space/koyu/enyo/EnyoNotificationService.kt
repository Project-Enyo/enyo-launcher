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
        listening.value = true
        if (overlays == null) overlays = OverlayBanners(this)
        refresh()
        // Messages still shown in the shade are archived once more; known ones are recognized and skipped.
        val archive = MessageArchive.get(this)
        entries.value.filter { it.packageName in messagingPackages }.forEach(archive::capture)
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val previous = entries.value.firstOrNull { it.key == sbn.key }
        if (sbn.packageName in messagingPackages) MessageArchive.get(this).capture(sbn)
        refresh()
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val current = entries.value.firstOrNull { it.key == sbn.key } ?: return
        if (current.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (previous == null || previous.noticePayload() != current.noticePayload()) arrivals.tryEmit(current)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()
    override fun onListenerDisconnected() {
        if (connected === this) connected = null
        listening.value = false
        overlays?.close()
        overlays = null
        entries.value = emptyList()
        brandIcons.value = emptyMap()
    }
    override fun onDestroy() {
        if (connected === this) connected = null
        overlays?.close()
        overlays = null
        entries.value = emptyList()
        brandIcons.value = emptyMap()
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
        internal val entries = MutableStateFlow<List<StatusBarNotification>>(emptyList())
        /** Whether notification access is active, so new messages are being archived. */
        internal val listening = MutableStateFlow(false)
    }
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

/**
 * Sends an intent created by another app with Enyo's foreground visibility. Since Android 14 a
 * notification creator in the background may not start activities by itself, and the sender must
 * opt in, as the system shade does. Without this, opening a notification is silently blocked.
 */
internal fun PendingIntent.sendFromForeground(context: android.content.Context): Boolean {
    val options = android.app.ActivityOptions.makeBasic().apply {
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
        } else if (android.os.Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
    }.toBundle()
    return runCatching { send(context, 0, null, null, null, null, options) }.isSuccess
}

/** Opens a notification like the shade does, including auto cancel when the app asks for it. */
internal fun StatusBarNotification.openFromForeground(context: android.content.Context): Boolean {
    val sent = notification.contentIntent?.sendFromForeground(context) == true
    if (sent && notification.flags and Notification.FLAG_AUTO_CANCEL != 0) EnyoNotificationService.dismiss(key)
    return sent
}

/** Starts the main activity of the app that posted a notification, in the current profile. */
internal fun android.content.Context.launchNotifyingApp(packageName: String): Boolean = runCatching {
    val launcher = getSystemService(android.content.pm.LauncherApps::class.java)
    val activity = launcher.getActivityList(packageName, Process.myUserHandle()).firstOrNull() ?: return false
    launcher.startMainActivity(activity.componentName, activity.user, null, null)
    true
}.getOrDefault(false)
