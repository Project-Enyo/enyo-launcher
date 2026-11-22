package space.koyu.enyo

import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
import android.graphics.Bitmap
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The persistent Messages archive. Only content received through authorized notifications is
 * stored, in a profile local SQLCipher database whose random key is wrapped by an Android Keystore
 * key. History is kept until the person deletes it; dismissing a notification never removes it.
 * Every write runs on one background thread, and [revision] changes after each write so screens
 * can query again.
 */
internal class MessageArchive private constructor(private val context: Context) {
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    val revision = MutableStateFlow(0L)

    private val db: SupportSQLiteDatabase by lazy {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(databaseKey())
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(schemaVersion) {
                override fun onCreate(db: SupportSQLiteDatabase) { createSchema(db); BeeperDuplicates.createSchema(db); Notes.createSchema(db); createInboxSchema(db); AssistantChats.createSchema(db); SemanticFiles.createSchema(db) }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = migrate(db, oldVersion)
                override fun onConfigure(db: SupportSQLiteDatabase) { db.execSQL("PRAGMA foreign_keys = ON") }
            })
            .build()
        factory.create(config).writableDatabase
    }

    /** Stores the messages of one messaging notification. */
    /**
     * Stores the messages of one messaging notification. [stored] receives true when every message
     * in it is a confident duplicate of a Beeper message, so its Enyo banner can be skipped.
     */
    fun capture(entry: StatusBarNotification, stored: (Boolean) -> Unit = {}) {
        if (entry.user != Process.myUserHandle() || !entry.isMessagingNotice()) { stored(false); return }
        // An app that shares through UnifiedInbox already delivers its messages; its notifications would be copies.
        if (InboxHost.isConnector(context, entry.packageName)) { stored(false); return }
        if (entry.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) { stored(false); return }
        val captured = runCatching { CapturedNotice.from(context, entry) }.getOrNull() ?: run { stored(false); return }
        writes.launch {
            val duplicate = runCatching { store(captured) }.getOrDefault(false)
            revision.value = System.nanoTime()
            stored(duplicate)
        }
    }

    /**
     * Keeps a reply the person sent through a notification. Apps often echo the reply in their next
     * notification; that copy then replaces this one instead of appearing twice.
     */
    fun recordReply(entry: StatusBarNotification, text: String) {
        val notice = runCatching { CapturedNotice.from(context, entry) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        writes.launch {
            runCatching {
                db.beginTransaction()
                try {
                    val conversation = upsertConversation(notice)
                    db.insert("messages", CONFLICT_IGNORE, ContentValues().apply {
                        put("conversation_id", conversation)
                        put("package", notice.packageName)
                        put("text", text)
                        put("posted_at", now)
                        put("outgoing", 1)
                        put("fingerprint", "${notice.packageName}|${notice.conversationKey}|$localReply$now")
                        put("notification_key", notice.key)
                    })
                    db.execSQL("UPDATE conversations SET last_at = MAX(last_at, ?), read_at = MAX(read_at, ?) WHERE id = ?", arrayOf(now, now, conversation))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            revision.value = System.nanoTime()
        }
    }

    fun write(block: (SupportSQLiteDatabase) -> Unit) {
        writeResult({}, block)
    }

    fun <T> writeResult(finished: (T?) -> Unit, block: (SupportSQLiteDatabase) -> T) {
        writes.launch {
            val result = runCatching {
                val database = db
                database.beginTransaction()
                try {
                    val value = block(database)
                    database.setTransactionSuccessful()
                    value
                } finally {
                    database.endTransaction()
                }
            }.getOrNull()
            revision.value = System.nanoTime()
            finished(result)
        }
    }

    fun <T> read(block: (SupportSQLiteDatabase) -> T): T? = runCatching { block(db) }.getOrNull()

    private fun store(notice: CapturedNotice): Boolean {
        db.beginTransaction()
        try {
            val duplicate = storeInTransaction(notice)
            db.setTransactionSuccessful()
            return duplicate
        } finally {
            db.endTransaction()
        }
    }

    private fun storeInTransaction(notice: CapturedNotice): Boolean {
        run {
            val conversation = upsertConversation(notice)
            var fresh = 0
            var duplicates = 0
            for (message in notice.messages) {
                if (message.sender == null && adoptLocalReply(conversation, message, notice)) continue
                if (sameAsOtherSource(conversation, message, notice)) continue
                val identity = message.sender?.let { upsertIdentity(notice.packageName, it) }
                val values = ContentValues().apply {
                    put("conversation_id", conversation)
                    put("identity_id", identity)
                    put("package", notice.packageName)
                    put("sender_name", message.sender?.name)
                    put("text", message.text)
                    put("posted_at", message.postedAt)
                    put("outgoing", if (message.sender == null) 1 else 0)
                    put("fingerprint", message.fingerprint(notice))
                    put("notification_key", notice.key)
                }
                val inserted = db.insert("messages", CONFLICT_IGNORE, values)
                if (inserted != -1L && message.sender != null) {
                    fresh++
                    if (BeeperDuplicates.check(db, inserted)) duplicates++
                }
                if (inserted == -1L) {
                    // The same message again: an app edit replaces the stored text, anything else is a repost.
                    db.execSQL(
                        "UPDATE messages SET text = ? WHERE fingerprint = ? AND text != ?",
                        arrayOf(message.text, message.fingerprint(notice), message.text)
                    )
                }
            }
            notice.messages.maxOfOrNull { it.postedAt }?.let {
                db.execSQL("UPDATE conversations SET last_at = MAX(last_at, ?) WHERE id = ?", arrayOf(it, conversation))
            }
            return fresh > 0 && duplicates == fresh
        }
    }

    /**
     * A message an app sends through the inbox API usually also arrives as a notification. The API
     * copy wins: a notification copy is skipped, and an earlier notification copy takes the API id.
     */
    private fun sameAsOtherSource(conversation: Long, message: CapturedMessage, notice: CapturedNotice): Boolean {
        val api = message.apiId != null
        val other = db.query(
            """SELECT id FROM messages WHERE conversation_id = ? AND text = ? AND ABS(posted_at - ?) < ?
               AND fingerprint ${if (api) "NOT LIKE" else "LIKE"} ? AND fingerprint != ? LIMIT 1""",
            arrayOf(conversation, message.text, message.postedAt, sourceEchoWindow, "%|api:%", message.fingerprint(notice))
        ).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return false
        if (api) db.execSQL("UPDATE messages SET fingerprint = ? WHERE id = ?", arrayOf(message.fingerprint(notice), other))
        return true
    }

    /**
     * Applies UnifiedInbox events from a registered connector, in order. Messages are keyed by the
     * connector's own ids, so sending the same message again changes nothing and an edit replaces its text.
     */
    fun applyInboxEvents(packageName: String, events: org.json.JSONArray, replyFailed: (String, String?) -> Unit) {
        writes.launch {
            runCatching {
                db.beginTransaction()
                try {
                    for (index in 0 until events.length()) {
                        val event = events.optJSONObject(index) ?: continue
                        runCatching { applyInboxEvent(packageName, event, replyFailed) }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            revision.value = System.nanoTime()
        }
    }

    private fun inboxKey(packageName: String, id: String): String =
        db.query("SELECT key FROM inbox_conversations WHERE package = ? AND id = ?", arrayOf(packageName, id))
            .use { if (it.moveToFirst()) it.getString(0) else null } ?: "inbox:$id"

    private fun applyInboxEvent(packageName: String, event: org.json.JSONObject, replyFailed: (String, String?) -> Unit) {
        fun str(name: String) = event.optString(name).takeIf { event.has(name) && !event.isNull(name) && it.isNotEmpty() }
        when (event.optString("type")) {
            "conversation" -> {
                val id = str("id") ?: return
                val key = str("shortcut")?.let { "shortcut:$it" } ?: "inbox:$id"
                db.execSQL("INSERT OR REPLACE INTO inbox_conversations(package, id, key) VALUES (?, ?, ?)", arrayOf(packageName, id, key))
                val group = event.optBoolean("group")
                val members = event.optJSONArray("members")
                val counterpart = if (group) null else members?.optJSONObject(0)?.let { member ->
                    val memberId = member.optString("id").takeIf { it.isNotEmpty() } ?: return@let null
                    CapturedSender(memberId, member.optString("name").ifEmpty { null }, member.optString("uri").ifEmpty { null },
                        member.optString("phone").ifEmpty { null }, null)
                }
                upsertConversation(CapturedNotice("inbox", packageName, key, str("title") ?: counterpart?.name, group, counterpart, emptyList()))
                db.execSQL("UPDATE conversations SET is_group = ? WHERE package = ? AND key = ?", arrayOf(if (group) 1 else 0, packageName, key))
                // Members of groups are known people too, so their messages carry their numbers.
                if (group) for (index in 0 until (members?.length() ?: 0)) {
                    val member = members!!.optJSONObject(index) ?: continue
                    val memberId = member.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    upsertIdentity(packageName, CapturedSender(memberId, member.optString("name").ifEmpty { null },
                        member.optString("uri").ifEmpty { null }, member.optString("phone").ifEmpty { null }, null))
                }
            }
            "message" -> {
                val conversation = str("conversation") ?: return
                val id = str("id") ?: return
                val text = str("text") ?: return
                val key = inboxKey(packageName, conversation)
                val group = db.query("SELECT is_group FROM conversations WHERE package = ? AND key = ?", arrayOf(packageName, key))
                    .use { it.moveToFirst() && it.getInt(0) == 1 }
                val outgoing = event.optBoolean("outgoing")
                val sender = if (outgoing) null else str("sender")?.let { CapturedSender(it, str("senderName"), null, null, null) }
                if (!outgoing && sender == null) return
                val time = event.optLong("time").takeIf { it > 0 } ?: System.currentTimeMillis()
                storeInTransaction(CapturedNotice("inbox", packageName, key, null, group, null,
                    listOf(CapturedMessage(sender, text, time, apiId = id))))
            }
            "edit" -> {
                val key = inboxKey(packageName, str("conversation") ?: return)
                db.execSQL("UPDATE messages SET text = ? WHERE fingerprint = ?", arrayOf(str("text") ?: return, "$packageName|$key|api:${str("id") ?: return}"))
            }
            "delete" -> {
                val key = inboxKey(packageName, str("conversation") ?: return)
                db.execSQL("DELETE FROM messages WHERE fingerprint = ?", arrayOf("$packageName|$key|api:${str("id") ?: return}"))
            }
            "read" -> {
                val key = inboxKey(packageName, str("conversation") ?: return)
                db.execSQL("UPDATE conversations SET read_at = MAX(read_at, ?) WHERE package = ? AND key = ?", arrayOf(event.optLong("time"), packageName, key))
            }
            "conversation_delete" -> {
                val id = str("id") ?: return
                val key = inboxKey(packageName, id)
                db.execSQL("DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE package = ? AND key = ?)", arrayOf(packageName, key))
                db.execSQL("UPDATE conversations SET last_at = 0 WHERE package = ? AND key = ?", arrayOf(packageName, key))
                db.execSQL("DELETE FROM inbox_conversations WHERE package = ? AND id = ?", arrayOf(packageName, id))
            }
            "reply_failed" -> replyFailed(str("request") ?: return, str("reason"))
        }
    }

    /** Deletes everything a connector shared, after it or the person ended its registration. */
    fun forgetConnector(packageName: String) = write { db ->
        db.execSQL("DELETE FROM messages WHERE package = ? AND fingerprint LIKE ?", arrayOf(packageName, "%|api:%"))
        db.execSQL("DELETE FROM inbox_conversations WHERE package = ?", arrayOf(packageName))
        db.execSQL("UPDATE conversations SET last_at = COALESCE((SELECT MAX(posted_at) FROM messages WHERE conversation_id = conversations.id), 0) WHERE package = ?", arrayOf(packageName))
    }

    /** Gives a locally kept reply the identity of the app's own copy of it, when the app echoes it. */
    private fun adoptLocalReply(conversation: Long, message: CapturedMessage, notice: CapturedNotice): Boolean {
        val local = db.query(
            """SELECT id FROM messages WHERE conversation_id = ? AND outgoing = 1 AND text = ? AND fingerprint LIKE ?
               AND ABS(posted_at - ?) < ? LIMIT 1""",
            arrayOf(conversation, message.text, "%|$localReply%", message.postedAt, replyEchoWindow)
        ).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return false
        db.execSQL("UPDATE messages SET fingerprint = ?, posted_at = ? WHERE id = ?", arrayOf(message.fingerprint(notice), message.postedAt, local))
        return true
    }

    private fun upsertConversation(notice: CapturedNotice): Long {
        val values = ContentValues().apply {
            put("package", notice.packageName)
            put("key", notice.conversationKey)
            put("title", notice.title)
            put("is_group", if (notice.group) 1 else 0)
            put("last_at", 0L)
        }
        db.insert("conversations", CONFLICT_IGNORE, values)
        if (!notice.title.isNullOrBlank()) {
            db.execSQL("UPDATE conversations SET title = ? WHERE package = ? AND key = ?", arrayOf(notice.title, notice.packageName, notice.conversationKey))
        }
        val id = db.query("SELECT id FROM conversations WHERE package = ? AND key = ?", arrayOf(notice.packageName, notice.conversationKey))
            .use { if (it.moveToFirst()) it.getLong(0) else -1L }
        // A one to one chat belongs to its counterpart, so the conversation follows that identity.
        val counterpart = notice.counterpart?.let { upsertIdentity(notice.packageName, it) }
        if (!notice.group && counterpart != null) {
            db.execSQL("UPDATE conversations SET identity_id = ? WHERE id = ?", arrayOf(counterpart, id))
        }
        return id
    }

    private fun upsertIdentity(packageName: String, sender: CapturedSender): Long {
        val values = ContentValues().apply {
            put("package", packageName)
            put("key", sender.key)
            put("name", sender.name)
            put("uri", sender.uri)
            put("phone", sender.phone)
        }
        db.insert("identities", CONFLICT_IGNORE, values)
        db.execSQL(
            "UPDATE identities SET name = COALESCE(?, name), uri = COALESCE(?, uri), phone = COALESCE(?, phone) WHERE package = ? AND key = ?",
            arrayOf(sender.name, sender.uri, sender.phone, packageName, sender.key)
        )
        if (sender.avatar != null) {
            db.execSQL("UPDATE identities SET avatar = ? WHERE package = ? AND key = ?", arrayOf(sender.avatar, packageName, sender.key))
        }
        val id = db.query("SELECT id FROM identities WHERE package = ? AND key = ?", arrayOf(packageName, sender.key))
            .use { if (it.moveToFirst()) it.getLong(0) else -1L }
        PeopleResolver.resolve(context, db, id)
        return id
    }

    private fun databaseKey(): ByteArray {
        val file = File(context.noBackupFilesDir, keyFile)
        val wrapping = keystoreKey()
        if (file.exists()) {
            val stored = file.readBytes()
            val iv = stored.copyOfRange(0, ivLength)
            val cipher = Cipher.getInstance(keyCipher)
            cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(tagBits, iv))
            return cipher.doFinal(stored, ivLength, stored.size - ivLength)
        }
        val key = ByteArray(databaseKeyLength).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(keyCipher)
        cipher.init(Cipher.ENCRYPT_MODE, wrapping)
        file.writeBytes(cipher.iv + cipher.doFinal(key))
        return key
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance(keystoreProvider).apply { load(null) }
        (store.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keystoreProvider)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val databaseName = "messages.db"
        private const val keyFile = "messages.key"
        private const val keyAlias = "enyo_messages"
        private const val keystoreProvider = "AndroidKeyStore"
        private const val keyCipher = "AES/GCM/NoPadding"
        private const val ivLength = 12
        private const val tagBits = 128
        private const val databaseKeyLength = 32
        const val schemaVersion = 8
        private const val sourceEchoWindow = 3 * 60_000L
        private const val localReply = "local:"
        private const val replyEchoWindow = 10 * 60_000L

        @Volatile private var instance: MessageArchive? = null
        fun get(context: Context): MessageArchive =
            instance ?: synchronized(this) { instance ?: MessageArchive(context.applicationContext).also { instance = it } }

        private fun createSchema(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE people (
                    id INTEGER PRIMARY KEY,
                    name TEXT,
                    contact_lookup TEXT UNIQUE,
                    created_at INTEGER NOT NULL
                )"""
            )
            db.execSQL(
                """CREATE TABLE identities (
                    id INTEGER PRIMARY KEY,
                    package TEXT NOT NULL,
                    key TEXT NOT NULL,
                    name TEXT,
                    uri TEXT,
                    phone TEXT,
                    avatar BLOB,
                    person_id INTEGER REFERENCES people(id) ON DELETE SET NULL,
                    link_source TEXT,
                    UNIQUE(package, key)
                )"""
            )
            db.execSQL(
                """CREATE TABLE conversations (
                    id INTEGER PRIMARY KEY,
                    package TEXT NOT NULL,
                    key TEXT NOT NULL,
                    title TEXT,
                    is_group INTEGER NOT NULL DEFAULT 0,
                    identity_id INTEGER REFERENCES identities(id) ON DELETE SET NULL,
                    last_at INTEGER NOT NULL DEFAULT 0,
                    read_at INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(package, key)
                )"""
            )
            db.execSQL(
                """CREATE TABLE messages (
                    id INTEGER PRIMARY KEY,
                    conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
                    identity_id INTEGER REFERENCES identities(id) ON DELETE SET NULL,
                    package TEXT NOT NULL,
                    sender_name TEXT,
                    text TEXT NOT NULL,
                    posted_at INTEGER NOT NULL,
                    outgoing INTEGER NOT NULL DEFAULT 0,
                    fingerprint TEXT NOT NULL UNIQUE,
                    notification_key TEXT,
                    duplicate_of INTEGER REFERENCES messages(id) ON DELETE SET NULL
                )"""
            )
            db.execSQL("CREATE INDEX messages_conversation ON messages(conversation_id, posted_at)")
            createNotificationIndex(db)
            db.execSQL(
                """CREATE TABLE suggestions (
                    identity_id INTEGER NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
                    contact_lookup TEXT NOT NULL,
                    contact_name TEXT,
                    dismissed INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(identity_id, contact_lookup)
                )"""
            )
            db.execSQL("CREATE VIRTUAL TABLE messages_fts USING fts4(content=\"messages\", text, sender_name)")
            db.execSQL("CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN INSERT INTO messages_fts(docid, text, sender_name) VALUES (new.id, new.text, new.sender_name); END")
            db.execSQL("CREATE TRIGGER messages_bd BEFORE DELETE ON messages BEGIN DELETE FROM messages_fts WHERE docid = old.id; END")
            db.execSQL("CREATE TRIGGER messages_bu BEFORE UPDATE ON messages BEGIN DELETE FROM messages_fts WHERE docid = old.id; END")
            db.execSQL("CREATE TRIGGER messages_au AFTER UPDATE ON messages BEGIN INSERT INTO messages_fts(docid, text, sender_name) VALUES (new.id, new.text, new.sender_name); END")
        }

        /** Which archive conversation each UnifiedInbox connector conversation id maps to. */
        private fun createInboxSchema(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS inbox_conversations (
                    package TEXT NOT NULL,
                    id TEXT NOT NULL,
                    key TEXT NOT NULL,
                    PRIMARY KEY(package, id)
                )"""
            )
        }

        private fun createNotificationIndex(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS messages_notification ON messages(notification_key, posted_at, id)")
        }

        /** Each schema step runs once, in order, inside the open transaction. */
        private fun migrate(db: SupportSQLiteDatabase, from: Int) {
            if (from < 1) createSchema(db)
            if (from < 2) BeeperDuplicates.createSchema(db)
            if (from in 1..2) dropStatusNotices(db)
            if (from < 4) Notes.createSchema(db)
            if (from < 5) createInboxSchema(db)
            if (from < 6) AssistantChats.createSchema(db)
            if (from < 7) SemanticFiles.createSchema(db)
            if (from < 8) createNotificationIndex(db)
        }

        /**
         * Removes notices stored before they were told apart from chat messages: Discord's upload
         * progress, which has no MessagingStyle and so landed under a title-only identity, and Anura's
         * incoming and missed call notices in English and German.
         */
        private fun dropStatusNotices(db: SupportSQLiteDatabase) {
            db.execSQL(
                """DELETE FROM messages WHERE package = 'com.discord' AND identity_id IN
                   (SELECT id FROM identities WHERE package = 'com.discord' AND key LIKE 'name:%')
                   AND (text LIKE '%upload%' OR text LIKE '%hochlad%' OR text LIKE '%hochgelad%'
                       OR sender_name LIKE '%upload%' OR sender_name LIKE '%hochlad%' OR sender_name LIKE '%hochgelad%')"""
            )
            db.execSQL(
                """DELETE FROM messages WHERE package IN ('space.koyu.apollo', 'space.koyu.apollo.mini') AND
                   (text IN ('Anura call', 'Anura Anruf', 'Group call', 'Gruppenanruf')
                       OR sender_name IN ('Missed call', 'Verpasster Anruf'))"""
            )
            val packages = "('com.discord', 'space.koyu.apollo', 'space.koyu.apollo.mini')"
            db.execSQL("DELETE FROM conversations WHERE package IN $packages AND id NOT IN (SELECT conversation_id FROM messages)")
            db.execSQL(
                """DELETE FROM identities WHERE package IN $packages AND key LIKE 'name:%'
                   AND id NOT IN (SELECT identity_id FROM messages WHERE identity_id IS NOT NULL)
                   AND id NOT IN (SELECT identity_id FROM conversations WHERE identity_id IS NOT NULL)"""
            )
            db.execSQL("DELETE FROM people WHERE id NOT IN (SELECT person_id FROM identities WHERE person_id IS NOT NULL)")
            db.execSQL("UPDATE conversations SET last_at = COALESCE((SELECT MAX(posted_at) FROM messages WHERE conversation_id = conversations.id), 0)")
        }
    }
}

/** A sender as a messaging notification describes it. The key is stable within one app. */
internal data class CapturedSender(val key: String, val name: String?, val uri: String?, val phone: String?, val avatar: ByteArray?)

internal data class CapturedMessage(val sender: CapturedSender?, val text: String, val postedAt: Long, val apiId: String? = null) {
    fun fingerprint(notice: CapturedNotice) =
        if (apiId != null) "${notice.packageName}|${notice.conversationKey}|api:$apiId"
        else "${notice.packageName}|${notice.conversationKey}|$postedAt|${sender?.key ?: "self"}"
}

/** The archive view of one notification: its conversation, counterpart, and messages. */
internal data class CapturedNotice(
    val key: String,
    val packageName: String,
    val conversationKey: String,
    val title: String?,
    val group: Boolean,
    val counterpart: CapturedSender?,
    val messages: List<CapturedMessage>
) {
    companion object {
        fun from(context: Context, entry: StatusBarNotification): CapturedNotice? {
            val note = entry.notification
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(note)
            val people = note.extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, android.app.Person::class.java).orEmpty()
            val phones = people.mapNotNull { it.uri?.takeIf { uri -> uri.startsWith("tel:") }?.removePrefix("tel:") }
            fun sender(person: androidx.core.app.Person?): CapturedSender? {
                if (person == null) return null
                val name = person.name?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val uri = person.uri?.takeIf { it.isNotBlank() }
                val key = person.key?.takeIf { it.isNotBlank() } ?: uri ?: name?.let { "name:$it" } ?: return null
                val phone = uri?.takeIf { it.startsWith("tel:") }?.removePrefix("tel:")
                return CapturedSender(key, name, uri?.takeUnless { it.startsWith("tel:") }, phone, person.icon?.let { avatarBytes(context, it) })
            }
            if (style != null && style.messages.isNotEmpty()) {
                val group = style.isGroupConversation
                val senders = style.messages.mapNotNull { message -> message.person?.let { it.key to it } }.toMap()
                val counterpartPerson = if (group) null else style.messages.lastOrNull { it.person != null && it.person?.key != style.user.key }?.person
                // A one to one notification that lists exactly one phone number names the counterpart's number.
                val counterpart = sender(counterpartPerson)?.let { if (it.phone == null && phones.size == 1) it.copy(phone = phones.single()) else it }
                val conversationKey = note.shortcutId?.takeIf { it.isNotBlank() }?.let { "shortcut:$it" }
                    ?: if (group) "group:${style.conversationTitle ?: entry.tag ?: entry.id}" else counterpart?.key?.let { "person:$it" }
                    ?: "notice:${entry.tag}:${entry.id}"
                val messages = style.messages.mapNotNull { message ->
                    val text = message.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val person = message.person
                    val self = person == null || (person.key != null && person.key == style.user.key)
                    val captured = if (self) null else if (person?.key != null && person.key == counterpartPerson?.key) counterpart else sender(person)
                    CapturedMessage(captured, text, message.timestamp)
                }
                if (messages.isEmpty()) return null
                return CapturedNotice(entry.key, entry.packageName, conversationKey, style.conversationTitle?.toString(), group, counterpart, messages)
            }
            // Without MessagingStyle the title names the sender and the text is the message.
            val title = note.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val text = (note.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: note.extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val counterpart = CapturedSender("name:$title", title, null, phones.singleOrNull(), null)
            val conversationKey = note.shortcutId?.takeIf { it.isNotBlank() }?.let { "shortcut:$it" } ?: "person:${counterpart.key}"
            return CapturedNotice(entry.key, entry.packageName, conversationKey, title, false, counterpart,
                listOf(CapturedMessage(counterpart, text, entry.postTime)))
        }

        private fun avatarBytes(context: Context, icon: androidx.core.graphics.drawable.IconCompat): ByteArray? = runCatching {
            val bitmap = icon.loadDrawable(context)?.toBitmap(avatarPixels, avatarPixels, Bitmap.Config.ARGB_8888) ?: return null
            ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, avatarQuality, out); out.toByteArray() }
        }.getOrNull()

        private const val avatarPixels = 160
        private const val avatarQuality = 85
    }
}

internal fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
internal fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
