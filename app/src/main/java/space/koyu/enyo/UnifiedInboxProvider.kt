package space.koyu.enyo

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * The UnifiedInbox API. Messaging apps hand their conversations to Enyo so the unified inbox shows
 * them without depending on notifications, and can read back what Enyo holds for them.
 *
 * Authority `<Enyo package>.inbox`, guarded by the normal permission `<Enyo package>.permission.UNIFIED_INBOX`.
 * Every row is owned by the package of the calling app, which the provider takes from the binder;
 * an app can never write or read as another app. Enyo itself and privileged system apps (installed
 * in a priv-app directory of the system image) may read every app's chats.
 *
 * - `content://<authority>/messages` insert: [UnifiedInboxContract] columns for one message.
 * - `content://<authority>/messages` query: messages, optionally `?conversation=<key>`.
 * - `content://<authority>/conversations` query: one row per conversation.
 * - `content://<authority>/conversations/<key>` delete: removes that conversation's messages.
 * - `call("capabilities")`: whether the caller may read every app's chats.
 */
class UnifiedInboxProvider : ContentProvider() {
    private lateinit var matcher: UriMatcher

    override fun onCreate(): Boolean {
        val authority = context!!.packageName + ".inbox"
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, "messages", messagesCode)
            addURI(authority, "conversations", conversationsCode)
            addURI(authority, "conversations/*", conversationCode)
        }
        return true
    }

    /** The calling app's package; a shared uid with several packages is refused. */
    private fun caller(): String {
        val uid = Binder.getCallingUid()
        val context = context!!
        if (uid == Process.myUid()) return context.packageName
        return context.packageManager.getPackagesForUid(uid)?.singleOrNull() ?: throw SecurityException("Unknown caller")
    }

    /** Enyo and privileged system apps may read every chat; every other app reads only its own. */
    private fun readsEverything(packageName: String): Boolean {
        val context = context!!
        if (packageName == context.packageName) return true
        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return false
        if (info.flags and ApplicationInfo.FLAG_SYSTEM == 0) return false
        return privilegedDirectories.any { info.sourceDir.orEmpty().startsWith(it) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (matcher.match(uri) != messagesCode || values == null) throw IllegalArgumentException("Unsupported uri $uri")
        val owner = caller()
        val notice = UnifiedInboxContract.notice(owner, values)
        val stored = MessageArchive.get(context!!).ingest(notice)
        context!!.contentResolver.notifyChange(uri, null)
        return if (stored > 0) uri.buildUpon().appendPath(notice.conversationKey).build() else null
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val owner = caller()
        val everything = readsEverything(owner)
        val archive = MessageArchive.get(context!!)
        val ownerFilter = if (everything) "" else " AND c.package = ?"
        val ownerArgs: Array<Any> = if (everything) emptyArray() else arrayOf(owner)
        return when (matcher.match(uri)) {
            conversationsCode -> {
                val result = MatrixCursor(UnifiedInboxContract.conversationColumns)
                archive.read { db ->
                    db.query(
                        """SELECT c.package, c.key, COALESCE(c.title, i.name, c.key), c.is_group, c.last_at, c.read_at,
                               (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id AND m.outgoing = 0 AND m.posted_at > c.read_at AND m.duplicate_of IS NULL)
                           FROM conversations c LEFT JOIN identities i ON i.id = c.identity_id WHERE c.last_at > 0$ownerFilter ORDER BY c.last_at DESC""",
                        ownerArgs
                    ).use { cursor ->
                        while (cursor.moveToNext()) result.addRow(arrayOf<Any?>(
                            cursor.getString(0), cursor.getString(1), cursor.stringOrNull(2), cursor.getInt(3), cursor.getLong(4), cursor.getLong(5), cursor.getInt(6)
                        ))
                    }
                }
                result
            }
            messagesCode -> {
                val result = MatrixCursor(UnifiedInboxContract.messageColumns)
                val conversation = uri.getQueryParameter(UnifiedInboxContract.CONVERSATION)
                val conversationFilter = if (conversation != null) " AND c.key = ?" else ""
                val args: Array<Any> = (ownerArgs.toList() + listOfNotNull<Any>(conversation)).toTypedArray()
                archive.read { db ->
                    db.query(
                        """SELECT c.package, c.key, m.id, m.sender_name, m.text, m.posted_at, m.outgoing FROM messages m
                           JOIN conversations c ON c.id = m.conversation_id
                           WHERE m.duplicate_of IS NULL$ownerFilter$conversationFilter ORDER BY m.posted_at DESC LIMIT $queryLimit""",
                        args
                    ).use { cursor ->
                        while (cursor.moveToNext()) result.addRow(arrayOf<Any?>(
                            cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.stringOrNull(3), cursor.getString(4), cursor.getLong(5), cursor.getInt(6)
                        ))
                    }
                }
                result
            }
            else -> throw IllegalArgumentException("Unsupported uri $uri")
        }.apply { setNotificationUri(context!!.contentResolver, uri) }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (matcher.match(uri) != conversationCode) throw IllegalArgumentException("Unsupported uri $uri")
        val owner = caller()
        val key = uri.lastPathSegment ?: return 0
        MessageArchive.get(context!!).write { db ->
            db.execSQL(
                "DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE package = ? AND key = ?)",
                arrayOf(owner, key)
            )
            db.execSQL("UPDATE conversations SET last_at = 0 WHERE package = ? AND key = ?", arrayOf(owner, key))
        }
        context!!.contentResolver.notifyChange(uri, null)
        return 1
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        UnifiedInboxContract.METHOD_CAPABILITIES -> Bundle().apply {
            putBoolean(UnifiedInboxContract.READS_EVERYTHING, readsEverything(caller()))
            putInt(UnifiedInboxContract.VERSION, UnifiedInboxContract.API_VERSION)
        }
        else -> null
    }

    override fun getType(uri: Uri): String? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val messagesCode = 1
        const val conversationsCode = 2
        const val conversationCode = 3
        const val queryLimit = 500
        val privilegedDirectories = listOf("/system/priv-app/", "/product/priv-app/", "/system_ext/priv-app/", "/vendor/priv-app/")
    }
}

/** Column names and helpers of the UnifiedInbox API, matching what client apps send and read. */
internal object UnifiedInboxContract {
    const val API_VERSION = 1
    const val METHOD_CAPABILITIES = "capabilities"
    const val READS_EVERYTHING = "reads_everything"
    const val VERSION = "version"

    const val PACKAGE = "package"
    /** Stable conversation id within the app. With [SHORTCUT_ID] it joins the notification's thread. */
    const val CONVERSATION = "conversation"
    const val SHORTCUT_ID = "shortcut_id"
    const val TITLE = "title"
    const val GROUP = "is_group"
    const val MESSAGE_ID = "message_id"
    const val SENDER_KEY = "sender_key"
    const val SENDER_NAME = "sender_name"
    const val SENDER_PHONE = "sender_phone"
    const val SENDER_URI = "sender_uri"
    const val TEXT = "text"
    const val POSTED_AT = "posted_at"
    const val OUTGOING = "outgoing"
    const val LAST_AT = "last_at"
    const val READ_AT = "read_at"
    const val UNREAD = "unread"

    val conversationColumns = arrayOf(PACKAGE, CONVERSATION, TITLE, GROUP, LAST_AT, READ_AT, UNREAD)
    val messageColumns = arrayOf(PACKAGE, CONVERSATION, "_id", SENDER_NAME, TEXT, POSTED_AT, OUTGOING)

    /** One inserted message as an archive notice owned by [owner]. */
    fun notice(owner: String, values: ContentValues): CapturedNotice {
        val text = values.getAsString(TEXT)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("text is required")
        val messageId = values.getAsString(MESSAGE_ID)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("message_id is required")
        val shortcut = values.getAsString(SHORTCUT_ID)?.takeIf { it.isNotBlank() }
        val conversation = values.getAsString(CONVERSATION)?.takeIf { it.isNotBlank() }
        val key = shortcut?.let { "shortcut:$it" } ?: conversation?.let { "api:$it" } ?: throw IllegalArgumentException("conversation is required")
        val group = values.getAsBoolean(GROUP) == true
        val outgoing = values.getAsBoolean(OUTGOING) == true
        // The sender of an incoming message, or the recipient of an outgoing one in a one to one chat.
        val person = values.getAsString(SENDER_KEY)?.takeIf { it.isNotBlank() }?.let { senderKey ->
            CapturedSender(
                senderKey, values.getAsString(SENDER_NAME), values.getAsString(SENDER_URI),
                values.getAsString(SENDER_PHONE)?.removePrefix("tel:"), null
            )
        }
        if (!outgoing && person == null) throw IllegalArgumentException("sender_key is required")
        val sender = if (outgoing) null else person
        val posted = values.getAsLong(POSTED_AT) ?: System.currentTimeMillis()
        val title = values.getAsString(TITLE)
        return CapturedNotice(
            key = "api", packageName = owner, conversationKey = key, title = title ?: person?.name, group = group,
            counterpart = if (group) null else person,
            messages = listOf(CapturedMessage(sender, text, posted, apiId = messageId))
        )
    }
}
