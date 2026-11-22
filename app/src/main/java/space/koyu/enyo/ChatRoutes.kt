package space.koyu.enyo

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.service.notification.StatusBarNotification

/** One way into a chat: an app's conversation with the person, and what the archive knows about it. */
internal data class ChatRoute(
    val packageName: String,
    val conversationKey: String,
    val identityKey: String?,
    val name: String?,
    val phone: String?,
    val uri: String?,
    val lastAt: Long
) {
    val shortcutId get() = conversationKey.removePrefix("shortcut:").takeIf { conversationKey.startsWith("shortcut:") }
}

/**
 * Opens a chat as directly as the app allows: the chat's live notification, then the conversation
 * shortcut the app published (Enyo may start it as the default launcher), then the app's own chat
 * link for the person, and the app itself last.
 */
internal fun Context.openChat(route: ChatRoute, live: StatusBarNotification? = null): Boolean {
    if (live != null && live.openFromForeground(this)) return true
    route.shortcutId?.let { id ->
        val apps = getSystemService(LauncherApps::class.java)
        val started = runCatching {
            apps.hasShortcutHostPermission() && apps.startShortcut(route.packageName, id, null, null, Process.myUserHandle()).let { true }
        }.getOrDefault(false)
        if (started) return true
    }
    chatLink(route)?.let { intent -> if (launchSafely(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return true }
    return launchNotifyingApp(route.packageName)
}

/** The app's documented link into a chat, from a phone number or the chat id its shortcut carries. */
private fun chatLink(route: ChatRoute): Intent? {
    val messenger = Messenger.of(route.packageName) ?: return null
    val phone = route.phone?.filter { it.isDigit() || it == '+' }?.takeIf { it.count(Char::isDigit) >= 5 }
    val digits = phone?.filter(Char::isDigit)
    val shortcut = route.shortcutId
    fun view(link: String) = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(route.packageName)
    return when (messenger) {
        Messenger.WhatsApp -> digits?.let { view("https://wa.me/$it") }
        Messenger.Signal, Messenger.Molly -> phone?.takeIf { it.startsWith("+") }?.let { view("https://signal.me/#p/$it") }
        Messenger.Telegram -> digits?.let { view("tg://resolve?phone=$it") }
        Messenger.Viber -> digits?.let { view("viber://chat?number=%2B$it") }
        Messenger.Sms -> phone?.let { Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", it, null)).setPackage(route.packageName) }
        Messenger.Discord -> shortcut?.takeIf { id -> id.all(Char::isDigit) }?.let { view("https://discord.com/channels/@me/$it") }
        Messenger.Anura -> shortcut?.removePrefix("chat:")?.takeIf { it.isNotBlank() && shortcut.startsWith("chat:") }
            ?.let { view("https://anura.social/messages/$it") }
        else -> null
    }
}
