package space.koyu.enyo

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
import android.net.Uri
import android.provider.ContactsContract
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Links message identities to people. Reliable identifiers link automatically: a contact URI the
 * app attached to the sender (Signal, Molly), a phone number (Telegram tel entries), or a WhatsApp
 * chat id that carries a number. A name that matches exactly one address book contact who also has
 * that messenger's sync account only becomes a suggestion for the person to confirm. Anyone else
 * starts as their own person. Manual links and splits are never overridden.
 */
internal object PeopleResolver {
    const val sourceContact = "contact"
    const val sourceManual = "manual"
    const val sourceOwn = "own"

    private val whatsAppNumber = Regex("""^(?:shortcut:|person:)?(\d{6,15})@s\.whatsapp\.net$""")

    fun resolve(context: Context, db: SupportSQLiteDatabase, identityId: Long) {
        if (identityId < 0) return
        val row = db.query("SELECT package, key, name, uri, phone, person_id, link_source FROM identities WHERE id = ?", arrayOf(identityId))
            .use { if (it.moveToFirst()) Identity(it.getString(0), it.getString(1), it.stringOrNull(2), it.stringOrNull(3), it.stringOrNull(4), it.longOrNull(5), it.stringOrNull(6)) else null }
            ?: return
        if (row.linkSource == sourceManual || row.linkSource == sourceContact) return
        val contact = if (contactsAllowed(context)) findContact(context, row) else null
        if (contact != null) {
            link(db, identityId, personFor(db, contact.lookup, contact.name), sourceContact, row.personId)
            return
        }
        if (row.personId == null) link(db, identityId, ownPerson(db, row.name), sourceOwn, null)
        if (contactsAllowed(context)) suggest(context, db, identityId, row)
    }

    /** Runs again for every identity, for example after contacts access was granted. */
    fun resolveAll(context: Context, db: SupportSQLiteDatabase) {
        val ids = db.query("SELECT id FROM identities").use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        ids.forEach { resolve(context, db, it) }
    }

    fun linkToContact(db: SupportSQLiteDatabase, identityId: Long, lookup: String, name: String?) {
        val previous = personOf(db, identityId)
        link(db, identityId, personFor(db, lookup, name), sourceManual, previous)
        db.execSQL("DELETE FROM suggestions WHERE identity_id = ?", arrayOf(identityId))
    }

    fun linkToPerson(db: SupportSQLiteDatabase, identityId: Long, personId: Long) {
        link(db, identityId, personId, sourceManual, personOf(db, identityId))
    }

    /** Separates one identity from its person; it keeps its own person from now on. */
    fun split(db: SupportSQLiteDatabase, identityId: Long) {
        val name = db.query("SELECT name FROM identities WHERE id = ?", arrayOf(identityId)).use { if (it.moveToFirst()) it.stringOrNull(0) else null }
        link(db, identityId, ownPerson(db, name), sourceManual, personOf(db, identityId))
    }

    fun dismissSuggestion(db: SupportSQLiteDatabase, identityId: Long, lookup: String) {
        db.execSQL("UPDATE suggestions SET dismissed = 1 WHERE identity_id = ? AND contact_lookup = ?", arrayOf(identityId, lookup))
    }

    private fun link(db: SupportSQLiteDatabase, identityId: Long, personId: Long, source: String, previous: Long?) {
        db.execSQL("UPDATE identities SET person_id = ?, link_source = ? WHERE id = ?", arrayOf(personId, source, identityId))
        if (previous != null && previous != personId) dropIfOrphan(db, previous)
    }

    private fun personOf(db: SupportSQLiteDatabase, identityId: Long): Long? =
        db.query("SELECT person_id FROM identities WHERE id = ?", arrayOf(identityId)).use { if (it.moveToFirst()) it.longOrNull(0) else null }

    private fun dropIfOrphan(db: SupportSQLiteDatabase, personId: Long) {
        db.execSQL("DELETE FROM people WHERE id = ? AND NOT EXISTS (SELECT 1 FROM identities WHERE person_id = ?)", arrayOf(personId, personId))
    }

    private fun personFor(db: SupportSQLiteDatabase, lookup: String, name: String?): Long {
        db.insert("people", CONFLICT_IGNORE, ContentValues().apply {
            put("contact_lookup", lookup); put("name", name); put("created_at", System.currentTimeMillis())
        })
        if (name != null) db.execSQL("UPDATE people SET name = ? WHERE contact_lookup = ?", arrayOf(name, lookup))
        return db.query("SELECT id FROM people WHERE contact_lookup = ?", arrayOf(lookup)).use { it.moveToFirst(); it.getLong(0) }
    }

    private fun ownPerson(db: SupportSQLiteDatabase, name: String?): Long =
        db.insert("people", CONFLICT_IGNORE, ContentValues().apply { put("name", name); put("created_at", System.currentTimeMillis()) })

    private fun contactsAllowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private data class Identity(
        val packageName: String, val key: String, val name: String?, val uri: String?, val phone: String?,
        val personId: Long?, val linkSource: String?
    )

    internal data class ContactMatch(val lookup: String, val name: String?)

    private fun findContact(context: Context, identity: Identity): ContactMatch? {
        val resolver = context.contentResolver
        identity.uri?.let { raw ->
            runCatching {
                val uri = Uri.parse(raw)
                if (uri.authority == ContactsContract.AUTHORITY) {
                    val contactUri = ContactsContract.Contacts.lookupContact(resolver, uri) ?: return@runCatching null
                    resolver.query(contactUri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) ContactMatch(it.getString(0), it.stringOrNull(1)) else null }
                } else null
            }.getOrNull()?.let { return it }
        }
        val phone = identity.phone ?: whatsAppNumber.find(identity.key)?.groupValues?.get(1)?.let { "+$it" }
        if (phone != null) {
            runCatching {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
                resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY, ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) ContactMatch(it.getString(0), it.stringOrNull(1)) else null }
            }.getOrNull()?.let { return it }
        }
        return null
    }

    /** A unique exact name match among contacts that also carry this messenger's sync account. */
    private fun suggest(context: Context, db: SupportSQLiteDatabase, identityId: Long, identity: Identity) {
        val name = identity.name ?: return
        val accounts = Messenger.of(identity.packageName)?.accountTypes.orEmpty()
        if (accounts.isEmpty()) return
        val placeholders = accounts.joinToString(",") { "?" }
        val matches = runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} IN ($placeholders) AND ${ContactsContract.RawContacts.DELETED} = 0",
                accounts.toTypedArray(), null
            )?.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }.orEmpty()
        }.getOrDefault(emptySet())
        if (matches.isEmpty()) return
        val named = runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts.DISPLAY_NAME} = ? COLLATE NOCASE", arrayOf(name), null
            )?.use { cursor ->
                buildList { while (cursor.moveToNext()) if (cursor.getLong(0) in matches) add(ContactMatch(cursor.getString(1), cursor.stringOrNull(2))) }
            }.orEmpty()
        }.getOrDefault(emptyList())
        val only = named.singleOrNull() ?: return
        db.insert("suggestions", CONFLICT_IGNORE, ContentValues().apply {
            put("identity_id", identityId); put("contact_lookup", only.lookup); put("contact_name", only.name)
        })
    }
}
