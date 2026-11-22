package space.koyu.enyo.inbox

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** A message an app hands to Enyo's unified inbox. */
data class InboxMessage(
    /** The app's own stable id for this message; sending it again updates nothing and adds nothing. */
    val id: String,
    /** The app's stable id for the conversation. */
    val conversation: String,
    val text: String,
    val postedAt: Long = System.currentTimeMillis(),
    /** True for a message the person sent from this app. */
    val outgoing: Boolean = false,
    /** Stable id of the other person: the sender of an incoming message, the recipient of an outgoing one in a one to one chat. */
    val senderKey: String? = null,
    val senderName: String? = null,
    val senderPhone: String? = null,
    /** A contact or profile URI of the person, used to link them to the address book. */
    val senderUri: String? = null,
    /** The conversation title, such as a group name. */
    val title: String? = null,
    val group: Boolean = false,
    /**
     * The conversation shortcut id the app publishes with its notifications. With it, messages from
     * the API and from notifications land in the same thread, and Enyo can open the chat directly.
     */
    val shortcutId: String? = null
)

data class InboxConversation(
    val packageName: String,
    val key: String,
    val title: String?,
    val group: Boolean,
    val lastAt: Long,
    val readAt: Long,
    val unread: Int
)

data class InboxEntry(
    val packageName: String,
    val conversation: String,
    val id: Long,
    val senderName: String?,
    val text: String,
    val postedAt: Long,
    val outgoing: Boolean
)

/**
 * Client for Enyo's UnifiedInbox API. Every call is safe without Enyo installed: writes return
 * false and reads return empty lists. Enyo stores rows under the calling app's package, so an app
 * reads back its own conversations; Enyo and privileged system apps read every app's.
 *
 * Calls do disk and IPC work, so run them off the main thread.
 */
object EnyoInbox {
    private val enyoPackages = listOf("space.koyu.enyo", "space.koyu.enyo.debug")

    /** The provider authority of the installed Enyo, release preferred, or null. */
    fun authority(context: Context): String? = enyoPackages.map { "$it.inbox" }.firstOrNull { authority ->
        context.packageManager.resolveContentProvider(authority, 0) != null
    }

    fun isAvailable(context: Context): Boolean = authority(context) != null

    /** Whether this app may read every app's chats, not only its own. */
    fun readsEverything(context: Context): Boolean {
        val authority = authority(context) ?: return false
        return runCatching {
            context.contentResolver.call(Uri.parse("content://$authority"), "capabilities", null, null)?.getBoolean("reads_everything")
        }.getOrNull() == true
    }

    /** Adds one message to the unified inbox. Returns whether Enyo accepted it. */
    fun post(context: Context, message: InboxMessage): Boolean {
        val authority = authority(context) ?: return false
        val values = ContentValues().apply {
            put("message_id", message.id)
            put("conversation", message.conversation)
            put("text", message.text)
            put("posted_at", message.postedAt)
            put("outgoing", message.outgoing)
            put("is_group", message.group)
            message.senderKey?.let { put("sender_key", it) }
            message.senderName?.let { put("sender_name", it) }
            message.senderPhone?.let { put("sender_phone", it) }
            message.senderUri?.let { put("sender_uri", it) }
            message.title?.let { put("title", it) }
            message.shortcutId?.let { put("shortcut_id", it) }
        }
        return runCatching { context.contentResolver.insert(Uri.parse("content://$authority/messages"), values) != null }.getOrDefault(false)
    }

    fun conversations(context: Context): List<InboxConversation> {
        val authority = authority(context) ?: return emptyList()
        return runCatching {
            context.contentResolver.query(Uri.parse("content://$authority/conversations"), null, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(InboxConversation(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3) == 1,
                        cursor.getLong(4), cursor.getLong(5), cursor.getInt(6)
                    ))
                }
            }
        }.getOrNull().orEmpty()
    }

    /** The newest messages, of one conversation key as [conversations] reports it, or of all. */
    fun messages(context: Context, conversation: String? = null): List<InboxEntry> {
        val authority = authority(context) ?: return emptyList()
        val uri = Uri.parse("content://$authority/messages").buildUpon()
            .apply { if (conversation != null) appendQueryParameter("conversation", conversation) }.build()
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(InboxEntry(
                        cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3),
                        cursor.getString(4), cursor.getLong(5), cursor.getInt(6) == 1
                    ))
                }
            }
        }.getOrNull().orEmpty()
    }

    /** Removes the stored messages of one of this app's conversations. */
    fun deleteConversation(context: Context, key: String): Boolean {
        val authority = authority(context) ?: return false
        return runCatching {
            context.contentResolver.delete(Uri.parse("content://$authority/conversations/${Uri.encode(key)}"), null, null) > 0
        }.getOrDefault(false)
    }

    /** Calls [changed] on the main thread whenever the inbox changes. Returns a function that stops observing. */
    fun observe(context: Context, changed: () -> Unit): () -> Unit {
        val authority = authority(context) ?: return {}
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = changed()
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(Uri.parse("content://$authority"), true, observer)
        }.isSuccess
        return { if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }
}
