package space.koyu.enyo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow

/** A thread in the unified inbox: one person across messengers, or one group chat. */
internal sealed interface ThreadRef {
    data class Person(val id: Long) : ThreadRef
    data class Group(val conversationId: Long) : ThreadRef
}

internal data class ThreadSummary(
    val ref: ThreadRef,
    val name: String,
    val contactLookup: String?,
    val avatar: ByteArray?,
    val preview: String?,
    val lastAt: Long,
    val unread: Int,
    val packages: List<String>
)

internal data class ArchivedMessage(
    val id: Long,
    val packageName: String,
    val sender: String?,
    val text: String,
    val postedAt: Long,
    val outgoing: Boolean,
    val conversationId: Long,
    val notificationKey: String?
)

internal data class ThreadIdentity(val id: Long, val packageName: String, val name: String?, val linkSource: String?)

internal data class LinkSuggestion(val identityId: Long, val packageName: String, val lookup: String, val contactName: String?)

internal data class ContactDetails(val uri: Uri, val phones: List<String>, val emails: List<String>)

internal data class MessageHit(val message: ArchivedMessage, val thread: ThreadRef, val threadName: String)

/** Read queries and edits on the archive. Reads run on the caller's background dispatcher. */
internal object ArchiveData {
    /** A thread that another surface, such as search, asks the Inbox to open. */
    val requestedThread = MutableStateFlow<ThreadRef?>(null)

    private const val personSummary = """
        SELECT p.id, COALESCE(p.name, MAX(i.name)), p.contact_lookup,
            (SELECT i2.avatar FROM identities i2 WHERE i2.person_id = p.id AND i2.avatar IS NOT NULL LIMIT 1),
            (SELECT m2.text FROM messages m2 JOIN conversations c2 ON c2.id = m2.conversation_id JOIN identities i3 ON i3.id = c2.identity_id
                WHERE i3.person_id = p.id AND c2.is_group = 0 AND m2.duplicate_of IS NULL ORDER BY m2.posted_at DESC, m2.id DESC LIMIT 1),
            MAX(c.last_at),
            (SELECT COUNT(*) FROM messages m3 JOIN conversations c3 ON c3.id = m3.conversation_id JOIN identities i4 ON i4.id = c3.identity_id
                WHERE i4.person_id = p.id AND c3.is_group = 0 AND m3.outgoing = 0 AND m3.duplicate_of IS NULL AND m3.posted_at > c3.read_at),
            GROUP_CONCAT(DISTINCT c.package)
        FROM people p JOIN identities i ON i.person_id = p.id JOIN conversations c ON c.identity_id = i.id AND c.is_group = 0
        GROUP BY p.id HAVING MAX(c.last_at) > 0"""

    private const val groupSummary = """
        SELECT c.id, COALESCE(c.title, c.key), NULL, NULL,
            (SELECT m2.text FROM messages m2 WHERE m2.conversation_id = c.id AND m2.duplicate_of IS NULL ORDER BY m2.posted_at DESC, m2.id DESC LIMIT 1),
            c.last_at,
            (SELECT COUNT(*) FROM messages m3 WHERE m3.conversation_id = c.id AND m3.outgoing = 0 AND m3.duplicate_of IS NULL AND m3.posted_at > c.read_at),
            c.package
        FROM conversations c WHERE c.is_group = 1 AND c.last_at > 0"""

    fun threads(context: Context, limit: Int = 200): List<ThreadSummary> {
        val archive = MessageArchive.get(context)
        return archive.read { db ->
            val people = db.query("$personSummary ORDER BY MAX(c.last_at) DESC LIMIT $limit").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(ThreadSummary(
                        ThreadRef.Person(cursor.getLong(0)), cursor.stringOrNull(1) ?: "?", cursor.stringOrNull(2),
                        cursor.getBlobOrNull(3), cursor.stringOrNull(4), cursor.getLong(5), cursor.getInt(6),
                        cursor.stringOrNull(7)?.split(',').orEmpty()
                    ))
                }
            }
            val groups = db.query("$groupSummary ORDER BY c.last_at DESC LIMIT $limit").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(ThreadSummary(
                        ThreadRef.Group(cursor.getLong(0)), cursor.stringOrNull(1) ?: "?", null, null,
                        cursor.stringOrNull(4), cursor.getLong(5), cursor.getInt(6), listOfNotNull(cursor.stringOrNull(7))
                    ))
                }
            }
            (people + groups).sortedByDescending { it.lastAt }.take(limit)
        }.orEmpty()
    }

    /** The chat heads: the most recent people, groups excluded. */
    fun recentPeople(context: Context, count: Int = recentHeadCount): List<ThreadSummary> =
        threads(context, count * 2).filter { it.ref is ThreadRef.Person }.take(count)

    fun messages(context: Context, thread: ThreadRef): List<ArchivedMessage> = MessageArchive.get(context).read { db ->
        val (sql, args) = when (thread) {
            is ThreadRef.Person -> """SELECT m.id, m.package, m.sender_name, m.text, m.posted_at, m.outgoing, m.conversation_id, m.notification_key
                FROM messages m JOIN conversations c ON c.id = m.conversation_id JOIN identities i ON i.id = c.identity_id
                WHERE i.person_id = ? AND c.is_group = 0 AND m.duplicate_of IS NULL ORDER BY m.posted_at, m.id""" to arrayOf<Any>(thread.id)
            is ThreadRef.Group -> """SELECT m.id, m.package, m.sender_name, m.text, m.posted_at, m.outgoing, m.conversation_id, m.notification_key
                FROM messages m WHERE m.conversation_id = ? AND m.duplicate_of IS NULL ORDER BY m.posted_at, m.id""" to arrayOf<Any>(thread.conversationId)
        }
        db.query(sql, args).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.message()) } }
    }.orEmpty()

    fun summary(context: Context, thread: ThreadRef): ThreadSummary? = MessageArchive.get(context).read { db ->
        val (sql, arg) = when (thread) {
            is ThreadRef.Person -> "$personSummary AND p.id = ?" to thread.id
            is ThreadRef.Group -> "$groupSummary AND c.id = ?" to thread.conversationId
        }
        db.query(sql, arrayOf(arg)).use { cursor ->
            if (!cursor.moveToFirst()) null else ThreadSummary(
                thread, cursor.stringOrNull(1) ?: "?", cursor.stringOrNull(2), cursor.getBlobOrNull(3), cursor.stringOrNull(4),
                cursor.getLong(5), cursor.getInt(6), cursor.stringOrNull(7)?.split(',').orEmpty()
            )
        }
    }

    fun identities(context: Context, personId: Long): List<ThreadIdentity> = MessageArchive.get(context).read { db ->
        db.query("SELECT id, package, name, link_source FROM identities WHERE person_id = ? ORDER BY package", arrayOf(personId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ThreadIdentity(cursor.getLong(0), cursor.getString(1), cursor.stringOrNull(2), cursor.stringOrNull(3))) }
        }
    }.orEmpty()

    fun suggestions(context: Context, personId: Long): List<LinkSuggestion> = MessageArchive.get(context).read { db ->
        db.query(
            """SELECT s.identity_id, i.package, s.contact_lookup, s.contact_name FROM suggestions s JOIN identities i ON i.id = s.identity_id
               WHERE i.person_id = ? AND s.dismissed = 0 AND COALESCE(i.link_source, '') NOT IN (?, ?)""",
            arrayOf(personId, PeopleResolver.sourceManual, PeopleResolver.sourceContact)
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(LinkSuggestion(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.stringOrNull(3))) }
        }
    }.orEmpty()

    /** People other than [except], for merging an Enyo person without an address book entry. */
    fun people(context: Context, except: Long): List<ThreadSummary> = threads(context).filter { it.ref is ThreadRef.Person && (it.ref as ThreadRef.Person).id != except }

    fun search(context: Context, term: String, limit: Int = 20): List<MessageHit> {
        val query = Regex("""[\p{L}\p{N}]+""").findAll(term)
            .joinToString(" ") { word -> "text:${word.value}*" }
        if (query.isBlank()) return emptyList()
        return MessageArchive.get(context).read { db ->
            db.query(
                """SELECT m.id, m.package, m.sender_name, m.text, m.posted_at, m.outgoing, m.conversation_id, m.notification_key,
                       c.is_group, i.person_id, COALESCE(CASE WHEN c.is_group = 1 THEN c.title END, p.name, i.name, c.title, c.key)
                   FROM messages_fts f JOIN messages m ON m.id = f.docid JOIN conversations c ON c.id = m.conversation_id
                   LEFT JOIN identities i ON i.id = c.identity_id LEFT JOIN people p ON p.id = i.person_id
                   WHERE messages_fts MATCH ? AND m.duplicate_of IS NULL ORDER BY m.posted_at DESC LIMIT $limit""",
                arrayOf(query)
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val group = cursor.getInt(8) == 1
                        val person = cursor.longOrNull(9)
                        val ref = if (group || person == null) ThreadRef.Group(cursor.getLong(6)) else ThreadRef.Person(person)
                        add(MessageHit(cursor.message(), ref, cursor.stringOrNull(10) ?: "?"))
                    }
                }
            }
        }.orEmpty()
    }

    fun markRead(context: Context, thread: ThreadRef) = MessageArchive.get(context).write { db ->
        val now = System.currentTimeMillis()
        when (thread) {
            is ThreadRef.Person -> db.execSQL(
                "UPDATE conversations SET read_at = ? WHERE is_group = 0 AND identity_id IN (SELECT id FROM identities WHERE person_id = ?)",
                arrayOf(now, thread.id)
            )
            is ThreadRef.Group -> db.execSQL("UPDATE conversations SET read_at = ? WHERE id = ?", arrayOf(now, thread.conversationId))
        }
    }

    fun deleteMessage(context: Context, id: Long) = MessageArchive.get(context).write { db ->
        val conversation = db.query("SELECT conversation_id FROM messages WHERE id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getLong(0) else null } ?: return@write
        db.execSQL("DELETE FROM messages WHERE id = ?", arrayOf(id))
        db.execSQL("""
            UPDATE conversations SET last_at = COALESCE(
                (SELECT MAX(posted_at) FROM messages WHERE conversation_id = conversations.id), 0
            ) WHERE id = ?
        """, arrayOf(conversation))
    }

    /** Deletes the stored history of a thread; identities and links stay so new messages land in place. */
    fun deleteThread(context: Context, thread: ThreadRef) = MessageArchive.get(context).write { db ->
        when (thread) {
            is ThreadRef.Person -> db.execSQL(
                """DELETE FROM messages WHERE conversation_id IN (SELECT c.id FROM conversations c JOIN identities i ON i.id = c.identity_id
                   WHERE i.person_id = ? AND c.is_group = 0)""", arrayOf(thread.id)
            )
            is ThreadRef.Group -> db.execSQL("DELETE FROM messages WHERE conversation_id = ?", arrayOf(thread.conversationId))
        }
        db.execSQL("UPDATE conversations SET last_at = COALESCE((SELECT MAX(posted_at) FROM messages WHERE conversation_id = conversations.id), 0)")
    }

    fun deleteAll(context: Context) = MessageArchive.get(context).write { db ->
        db.execSQL("DELETE FROM messages")
        db.execSQL("DELETE FROM suggestions")
        db.execSQL("DELETE FROM conversations")
        db.execSQL("DELETE FROM identities")
        db.execSQL("DELETE FROM people")
    }

    fun linkToContact(context: Context, identities: List<Long>, lookup: String, name: String?) =
        MessageArchive.get(context).writeResult({ id -> id?.let { requestedThread.value = ThreadRef.Person(it) } }) { db ->
            if (identities.isEmpty()) null else PeopleResolver.linkToContact(db, identities, lookup, name)
        }

    fun mergePeople(context: Context, from: Long, into: Long) =
        MessageArchive.get(context).writeResult({ id -> id?.let { requestedThread.value = ThreadRef.Person(it) } }) { db ->
            PeopleResolver.mergePeople(db, from, into)
        }

    fun split(context: Context, identityId: Long) = MessageArchive.get(context).write { db -> PeopleResolver.split(db, identityId) }

    fun dismissSuggestion(context: Context, suggestion: LinkSuggestion) =
        MessageArchive.get(context).write { db -> PeopleResolver.dismissSuggestion(db, suggestion.identityId, suggestion.lookup) }

    fun duplicateProposals(context: Context, messages: List<ArchivedMessage>): List<DuplicateProposal> =
        MessageArchive.get(context).read { db -> BeeperDuplicates.proposals(db, messages.map { it.id }) }.orEmpty()

    /** Confirms a Beeper duplicate; the Beeper account also joins the person of the app account. */
    fun confirmDuplicate(context: Context, proposal: DuplicateProposal) = MessageArchive.get(context).write { db ->
        BeeperDuplicates.confirm(db, proposal)
        val identities = db.query(
            "SELECT b.identity_id, i.person_id FROM messages b, messages p JOIN identities i ON i.id = p.identity_id WHERE b.id = ? AND p.id = ?",
            arrayOf(proposal.beeperMessage, proposal.providerMessage)
        ).use { if (it.moveToFirst()) it.longOrNull(0) to it.longOrNull(1) else null }
        val (beeperIdentity, person) = identities ?: return@write
        if (beeperIdentity != null && person != null) PeopleResolver.linkToPerson(db, beeperIdentity, person)
    }

    fun keepBothDuplicates(context: Context, proposal: DuplicateProposal) =
        MessageArchive.get(context).write { db -> BeeperDuplicates.keepBoth(db, proposal) }

    fun resolveAll(context: Context) = MessageArchive.get(context).write { db -> PeopleResolver.resolveAll(context, db) }

    /** The contact photo when the person is in the address book, otherwise the avatar the app supplied. */
    fun avatar(context: Context, summary: ThreadSummary): ImageBitmap? {
        summary.contactLookup?.takeIf {
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        }?.let { lookup ->
            runCatching {
                val uri = ContactsContract.Contacts.lookupContact(
                    context.contentResolver, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookup)
                ) ?: return@runCatching null
                ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, uri, false)
                    ?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()?.let { return it }
        }
        return summary.avatar?.let { bytes -> runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() }
    }

    /** Contact lookup key and name for a contact URI returned by the system picker. */
    fun contactOf(context: Context, uri: Uri): PeopleResolver.ContactMatch? = runCatching {
        context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) PeopleResolver.ContactMatch(it.getString(0), it.stringOrNull(1)) else null }
    }.getOrNull()

    fun replacementThread(context: Context, previous: ThreadRef.Person, conversation: Long): ThreadRef.Person? =
        MessageArchive.get(context).read { db ->
            db.query("""
                SELECT i.person_id FROM conversations c JOIN identities i ON i.id = c.identity_id
                WHERE c.id = ? AND c.is_group = 0 AND i.person_id IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM people WHERE id = ?)
            """, arrayOf(conversation, previous.id)).use {
                if (it.moveToFirst()) ThreadRef.Person(it.getLong(0)) else null
            }
        }

    /** A recurring notification key belongs to the most recent conversation that used it. */
    fun notificationConversations(context: Context, keys: Set<String>): Map<String, Long> =
        MessageArchive.get(context).read { db ->
            buildMap {
                keys.chunked(500).forEach { batch ->
                    val placeholders = batch.joinToString(",") { "?" }
                    db.query(
                        "SELECT notification_key, conversation_id FROM messages WHERE notification_key IN ($placeholders) ORDER BY posted_at DESC, id DESC",
                        batch.toTypedArray()
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val key = cursor.getString(0)
                            if (!containsKey(key)) put(key, cursor.getLong(1))
                        }
                    }
                }
            }
        }.orEmpty()

    /** Every app conversation behind a thread, most recent first. */
    fun routes(context: Context, thread: ThreadRef): List<ChatRoute> = MessageArchive.get(context).read { db ->
        val (sql, arg) = when (thread) {
            is ThreadRef.Person -> """SELECT c.package, c.key, i.key, COALESCE(i.name, c.title), i.phone, i.uri, c.last_at, ic.id, c.id
                FROM conversations c JOIN identities i ON i.id = c.identity_id
                LEFT JOIN inbox_conversations ic ON ic.package = c.package AND ic.key = c.key
                WHERE i.person_id = ? AND c.is_group = 0 ORDER BY c.last_at DESC""" to thread.id
            is ThreadRef.Group -> """SELECT c.package, c.key, NULL, c.title, NULL, NULL, c.last_at, ic.id, c.id FROM conversations c
                LEFT JOIN inbox_conversations ic ON ic.package = c.package AND ic.key = c.key WHERE c.id = ?""" to thread.conversationId
        }
        db.query(sql, arrayOf(arg)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ChatRoute(
                    cursor.getString(0), cursor.getString(1), cursor.stringOrNull(2), cursor.stringOrNull(3),
                    cursor.stringOrNull(4), cursor.stringOrNull(5), cursor.getLong(6), cursor.stringOrNull(7), cursor.getLong(8)
                ))
            }
        }
    }.orEmpty()

    /** Phone numbers and e-mail addresses of a linked contact, when contacts access is granted. */
    fun contactDetails(context: Context, lookup: String): ContactDetails? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val contact = ContactsContract.Contacts.lookupContact(
                context.contentResolver, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookup)
            ) ?: return null
            val id = contact.lastPathSegment ?: return null
            val phones = mutableListOf<String>()
            val emails = mutableListOf<String>()
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(id), null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val value = cursor.getString(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    when (cursor.getString(0)) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> phones += value
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> emails += value
                    }
                }
            }
            ContactDetails(contact, phones.distinctBy { it.filter(Char::isDigit) }, emails.distinct())
        }.getOrNull()
    }

    private fun android.database.Cursor.message() = ArchivedMessage(
        getLong(0), getString(1), stringOrNull(2), getString(3), getLong(4), getInt(5) == 1, getLong(6), stringOrNull(7)
    )

    private fun android.database.Cursor.getBlobOrNull(index: Int): ByteArray? = if (isNull(index)) null else getBlob(index)

    const val recentHeadCount = 10
}
