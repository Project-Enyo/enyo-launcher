package space.koyu.enyo

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts

/**
 * Saves the chat accounts Enyo linked to a contact into the address book, as instant messaging
 * entries: the service is the app's name and the handle is the app's stable id for the person.
 * They travel with the contact, through contact sync too, and link the person again later.
 * Accounts that link on their own, by phone number or contact URI, are not saved.
 */
internal object ContactConnections {
    private val whatsAppNumber = Regex("""^(?:shortcut:|person:)?\d{6,15}@s\.whatsapp\.net$""")

    data class Connection(val packageName: String, val key: String, val name: String?)

    fun canRead(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun canWrite(context: Context) = context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** The service name an app's entries carry: its name, so contact apps show something people know. */
    private fun service(context: Context, packageName: String): String = sourceLabel(context, packageName)

    /** The accounts of a person worth saving: a stable id that does not already lead to the contact. */
    fun connections(context: Context, personId: Long): List<Connection> = MessageArchive.get(context).read { db ->
        db.query("SELECT package, key, name, uri, phone FROM identities WHERE person_id = ?", arrayOf(personId)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val key = cursor.getString(1)
                    val uri = cursor.stringOrNull(3)
                    val linksAlready = cursor.stringOrNull(4) != null || whatsAppNumber.matches(key) ||
                        uri?.let { runCatching { Uri.parse(it).authority == ContactsContract.AUTHORITY }.getOrDefault(false) } == true
                    // A title only notification has no stable id to save.
                    if (!linksAlready && !key.startsWith("name:") && key.length <= 512) add(Connection(cursor.getString(0), key, cursor.stringOrNull(2)))
                }
            }
        }
    }.orEmpty()

    private fun contactId(resolver: ContentResolver, lookup: String): Long? = runCatching {
        val uri = ContactsContract.Contacts.lookupContact(resolver, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookup)) ?: return null
        resolver.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }.getOrNull()

    /** A raw contact of the contact that its account lets apps change: a local one, or one whose sync uploads. */
    private fun writableRawContact(context: Context, contactId: Long): Long? {
        val uploading = ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }.map { it.accountType }.toSet()
        val raws = context.contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE),
            "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0", arrayOf(contactId.toString()), null
        )?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.stringOrNull(1)) } }.orEmpty()
        return (raws.firstOrNull { it.second != null && it.second in uploading } ?: raws.firstOrNull { it.second == null })?.first
    }

    /** Existing entries of the contact, as service to handle, so nothing is saved twice. */
    private fun saved(resolver: ContentResolver, contactId: Long): Set<Pair<String, String>> =
        resolver.query(Data.CONTENT_URI, arrayOf(Im.CUSTOM_PROTOCOL, Im.DATA),
            "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Im.PROTOCOL} = ?",
            arrayOf(contactId.toString(), Im.CONTENT_ITEM_TYPE, Im.PROTOCOL_CUSTOM.toString()), null
        )?.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.stringOrNull(0).orEmpty() to cursor.stringOrNull(1).orEmpty()) } }.orEmpty()

    /** Saves the person's connections into the contact [lookup] names. Returns how many were added, or null when it could not. */
    fun save(context: Context, personId: Long, lookup: String): Int? = runCatching {
        if (!canWrite(context) || !canRead(context)) return null
        val resolver = context.contentResolver
        val contact = contactId(resolver, lookup) ?: return null
        val raw = writableRawContact(context, contact) ?: return null
        val existing = saved(resolver, contact)
        val operations = ArrayList(connections(context, personId).mapNotNull { connection ->
            val service = service(context, connection.packageName)
            if ((service to connection.key) in existing || (connection.packageName to connection.key) in existing) return@mapNotNull null
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValue(Data.RAW_CONTACT_ID, raw)
                .withValue(Data.MIMETYPE, Im.CONTENT_ITEM_TYPE)
                .withValue(Im.PROTOCOL, Im.PROTOCOL_CUSTOM)
                .withValue(Im.CUSTOM_PROTOCOL, service)
                .withValue(Im.DATA, connection.key)
                .withValue(Im.TYPE, Im.TYPE_CUSTOM)
                .withValue(Im.LABEL, connection.name?.take(100) ?: service)
                .build()
        })
        if (operations.isNotEmpty()) resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        operations.size
    }.getOrNull()

    /** Removes the entries Enyo saved for these connections from the contact [lookup] names. */
    fun remove(context: Context, connections: List<Connection>, lookup: String) {
        if (!canWrite(context) || connections.isEmpty()) return
        runCatching {
            val resolver = context.contentResolver
            val contact = contactId(resolver, lookup) ?: return
            val operations = ArrayList(connections.flatMap { connection ->
                listOf(service(context, connection.packageName), connection.packageName).distinct().map { service ->
                    ContentProviderOperation.newDelete(Data.CONTENT_URI).withSelection(
                        "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${Im.PROTOCOL} = ? AND ${Im.CUSTOM_PROTOCOL} = ? AND ${Im.DATA} = ?",
                        arrayOf(contact.toString(), Im.CONTENT_ITEM_TYPE, Im.PROTOCOL_CUSTOM.toString(), service, connection.key)
                    ).build()
                }
            })
            resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        }
    }

    /** The contact a saved entry names for this app's account, when exactly one contact has it. */
    fun find(context: Context, packageName: String, key: String): PeopleResolver.ContactMatch? = runCatching {
        if (!canRead(context) || key.startsWith("name:")) return null
        val services = listOf(service(context, packageName), packageName).distinct()
        context.contentResolver.query(Data.CONTENT_URI, arrayOf(Data.LOOKUP_KEY, Data.DISPLAY_NAME),
            "${Data.MIMETYPE} = ? AND ${Im.PROTOCOL} = ? AND ${Im.DATA} = ? AND ${Im.CUSTOM_PROTOCOL} IN (${services.joinToString(",") { "?" }})",
            arrayOf(Im.CONTENT_ITEM_TYPE, Im.PROTOCOL_CUSTOM.toString(), key) + services, null
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(PeopleResolver.ContactMatch(cursor.getString(0), cursor.stringOrNull(1))) }
                .distinctBy { it.lookup }.singleOrNull()
        }
    }.getOrNull()
}
