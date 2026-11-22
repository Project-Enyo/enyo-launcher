package space.koyu.enyo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.UserManager
import android.provider.DocumentsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.core.DataStore
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal enum class BackupCategory(val id: String, val label: Int) {
    Messages("messages", R.string.backup_category_messages),
    Links("links", R.string.backup_category_links),
    Settings("settings", R.string.backup_category_settings),
    Layout("layout", R.string.backup_category_layout),
    Notes("notes", R.string.backup_category_notes)
}

internal data class BackupPreview(
    val created: Long,
    val sameProfile: Boolean,
    val categories: Set<BackupCategory>,
    val messages: Int,
    val people: Int,
    val content: JSONObject
)

/**
 * Encrypted portable backups. A file is a versioned header followed by AES 256 GCM over gzipped
 * JSON; the header is authenticated too. The key comes from the person's password through PBKDF2
 * with the salt in the header, so any device can restore a file with the password alone. For
 * automatic snapshots the derived key is kept on this device, wrapped by an Android Keystore key.
 * Live database files are never copied; each backup is a consistent snapshot written under a
 * temporary name and renamed when complete, so Syncthing never picks up half a file.
 */
internal object Backups {
    private const val magic = "ENYOBK"
    private const val formatVersion = 1
    private const val iterations = 310_000
    private const val saltLength = 16
    private const val ivLength = 12
    private const val tagBits = 128
    private const val keyAlias = "enyo_backup"
    private const val prefsName = "backup"
    private const val autoPrefix = "enyo-auto-"
    private const val manualPrefix = "enyo-"
    const val extension = ".enyobackup"
    private const val automaticKept = 3
    const val syncthingPackage = "com.github.catfriend1.syncthingandroid"
    private const val workName = "enyo_backup"

    private fun prefs(context: Context) = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun hasPassword(context: Context) = prefs(context).contains("key")
    fun folder(context: Context): Uri? = prefs(context).getString("folder", null)?.let(Uri::parse)
    fun automatic(context: Context) = prefs(context).getBoolean("automatic", false)
    fun lastAutomatic(context: Context): Long = prefs(context).getLong("last", 0L)
    fun lastError(context: Context): Boolean = prefs(context).getBoolean("failed", false)

    fun setFolder(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        prefs(context).edit().putString("folder", uri.toString()).apply()
    }

    fun folderName(context: Context): String? = folder(context)?.let { tree ->
        runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    /** Sets the backup password: a new salt, the derived key, and that key wrapped for automatic snapshots. */
    fun setPassword(context: Context, password: CharArray) {
        val salt = ByteArray(saltLength).also { SecureRandom().nextBytes(it) }
        val key = derive(password, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val wrapped = cipher.iv + cipher.doFinal(key.encoded)
        prefs(context).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("key", Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .apply()
        password.fill(' ')
    }

    fun setAutomatic(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("automatic", enabled).apply()
        val work = WorkManager.getInstance(context)
        if (enabled) work.enqueueUniquePeriodicWork(
            workName, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS).build()
        ) else work.cancelUniqueWork(workName)
    }

    private fun storedKey(context: Context): Pair<SecretKey, ByteArray>? {
        val p = prefs(context)
        val salt = p.getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val wrapped = p.getString("key", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(tagBits, wrapped.copyOfRange(0, ivLength)))
        return SecretKeySpec(cipher.doFinal(wrapped, ivLength, wrapped.size - ivLength), "AES") to salt
    }

    private fun derive(password: CharArray, salt: ByteArray, rounds: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, rounds, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun profileSerial(context: Context): Long =
        context.getSystemService(UserManager::class.java).getSerialNumberForUser(android.os.Process.myUserHandle())

    /** Builds and encrypts a backup with the stored key. Returns null without a password. */
    suspend fun create(context: Context, categories: Set<BackupCategory>): ByteArray? {
        val (key, salt) = storedKey(context) ?: return null
        val content = BackupContent.collect(context, categories).apply {
            put("format", formatVersion)
            put("created", System.currentTimeMillis())
            put("profile", profileSerial(context))
            put("categories", JSONArray(categories.map { it.id }))
        }
        val plain = ByteArrayOutputStream().use { out -> GZIPOutputStream(out).use { it.write(content.toString().toByteArray()) }; out.toByteArray() }
        val iv = ByteArray(ivLength).also { SecureRandom().nextBytes(it) }
        val header = ByteBuffer.allocate(magic.length + 1 + saltLength + 4 + ivLength)
            .put(magic.toByteArray()).put(formatVersion.toByte()).put(salt).putInt(iterations).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(tagBits, iv))
            updateAAD(header)
        }
        return header + cipher.doFinal(plain)
    }

    /** Decrypts and checks a backup; wrong passwords and damaged files both fail authentication. */
    fun open(context: Context, bytes: ByteArray, password: CharArray): BackupPreview? = runCatching {
        val headerLength = magic.length + 1 + saltLength + 4 + ivLength
        require(bytes.size > headerLength && String(bytes, 0, magic.length) == magic)
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(magic.length)
        require(buffer.get().toInt() <= formatVersion)
        val salt = ByteArray(saltLength).also { buffer.get(it) }
        val rounds = buffer.getInt()
        val iv = ByteArray(ivLength).also { buffer.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, derive(password, salt, rounds), GCMParameterSpec(tagBits, iv))
            updateAAD(bytes, 0, headerLength)
        }
        val plain = cipher.doFinal(bytes, headerLength, bytes.size - headerLength)
        val json = JSONObject(GZIPInputStream(ByteArrayInputStream(plain)).use { String(it.readBytes()) })
        val ids = json.optJSONArray("categories")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty()
        BackupPreview(
            created = json.optLong("created"),
            sameProfile = json.optLong("profile", -1) == profileSerial(context),
            categories = BackupCategory.entries.filter { it.id in ids }.toSet(),
            messages = json.optJSONObject("messages")?.optJSONArray("messages")?.length() ?: 0,
            people = json.optJSONObject("links")?.optJSONArray("people")?.length() ?: 0,
            content = json
        )
    }.getOrNull().also { password.fill(' ') }

    suspend fun restore(context: Context, preview: BackupPreview, categories: Set<BackupCategory>, board: WidgetBoard?) =
        BackupContent.apply(context, preview.content, categories, board)

    /** Writes into the chosen folder: a temporary file first, then the final name. */
    fun writeToFolder(context: Context, bytes: ByteArray, automatic: Boolean): Boolean = runCatching {
        val tree = folder(context) ?: return false
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val name = "${if (automatic) autoPrefix else manualPrefix}${profileSerial(context)}-$stamp$extension"
        val partial = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", "$name.partial") ?: return false
        resolver.openOutputStream(partial, "w")?.use { it.write(bytes) } ?: return false
        DocumentsContract.renameDocument(resolver, partial, name) ?: return false
        if (automatic) pruneAutomatic(context, tree)
        true
    }.getOrDefault(false)

    /** Keeps the newest automatic snapshots of this profile; manual exports stay until deleted by hand. */
    private fun pruneAutomatic(context: Context, tree: Uri) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val prefix = "$autoPrefix${profileSerial(context)}-"
        val autos = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> buildList { while (cursor.moveToNext()) { val n = cursor.getString(1); if (n.startsWith(prefix) && n.endsWith(extension)) add(cursor.getString(0) to n) } } }
            .orEmpty().sortedByDescending { it.second }
        autos.drop(automaticKept).forEach { (id, _) -> runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) } }
    }

    /** One automatic snapshot; skipped while mobile data carries the connection. */
    suspend fun automaticSnapshot(context: Context): Boolean {
        if (!automatic(context) || !hasPassword(context) || folder(context) == null) return false
        val network = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = network.getNetworkCapabilities(network.activeNetwork)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) return false
        val bytes = runCatching { create(context, BackupCategory.entries.toSet()) }.getOrNull()
        val ok = bytes != null && writeToFolder(context, bytes, automatic = true)
        prefs(context).edit().putBoolean("failed", !ok).apply { if (ok) putLong("last", System.currentTimeMillis()) }.apply()
        return ok
    }

    fun syncthingStatus(context: Context): String? {
        val notice = EnyoNotificationService.entries.value.filter { it.packageName == syncthingPackage }.maxByOrNull { it.postTime } ?: return null
        val extras = notice.notification.extras
        return listOfNotNull(
            extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        ).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { null }
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Backups.automaticSnapshot(applicationContext)
        return Result.success()
    }
}

/** What a backup holds, keyed by stable identifiers so a restore merges instead of overwriting. */
internal object BackupContent {
    private val layoutKeys = setOf("dock_apps")

    suspend fun collect(context: Context, categories: Set<BackupCategory>): JSONObject {
        val json = JSONObject()
        val archive = MessageArchive.get(context)
        if (BackupCategory.Messages in categories || BackupCategory.Links in categories) archive.read { db ->
            db.beginTransaction()
            try {
                if (BackupCategory.Messages in categories) json.put("messages", JSONObject().apply {
                    put("identities", rows(db.query("SELECT package, key, name, uri, phone, avatar FROM identities")))
                    put("conversations", rows(db.query(
                        "SELECT c.package, c.key, c.title, c.is_group, c.last_at, c.read_at, i.package, i.key FROM conversations c LEFT JOIN identities i ON i.id = c.identity_id"
                    )))
                    put("messages", rows(db.query(
                        """SELECT c.package, c.key, i.package, i.key, m.package, m.sender_name, m.text, m.posted_at, m.outgoing, m.fingerprint, d.fingerprint
                           FROM messages m JOIN conversations c ON c.id = m.conversation_id LEFT JOIN identities i ON i.id = m.identity_id
                           LEFT JOIN messages d ON d.id = m.duplicate_of"""
                    )))
                })
                if (BackupCategory.Links in categories) json.put("links", JSONObject().apply {
                    put("people", rows(db.query("SELECT id, name, contact_lookup FROM people")))
                    put("identities", rows(db.query("SELECT package, key, person_id, link_source FROM identities WHERE person_id IS NOT NULL")))
                    put("suggestions", rows(db.query(
                        "SELECT i.package, i.key, s.contact_lookup, s.contact_name, s.dismissed FROM suggestions s JOIN identities i ON i.id = s.identity_id"
                    )))
                    put("beeper", rows(db.query(
                        "SELECT b.package, b.key, p.package, p.key FROM beeper_links l JOIN identities b ON b.id = l.beeper_identity JOIN identities p ON p.id = l.provider_identity"
                    )))
                })
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        if (BackupCategory.Notes in categories) archive.read { db ->
            json.put("notes", rows(db.query("SELECT uid, kind, text, done, pinned, position, created_at, updated_at FROM notes")))
        }
        if (BackupCategory.Settings in categories) json.put("settings", JSONObject().apply {
            put("appearance", preferences(context.appearancePreferences.data.first(), exclude = layoutKeys))
            put("search", preferences(context.searchPreferences.data.first(), exclude = emptySet()))
        })
        if (BackupCategory.Layout in categories) json.put("layout", JSONObject().apply {
            put("dock", context.appearancePreferences.data.first()[dockApps].orEmpty())
            put("widgets", context.getSharedPreferences("today_widgets", Context.MODE_PRIVATE).getString("placements", "[]"))
        })
        return json
    }

    suspend fun apply(context: Context, json: JSONObject, categories: Set<BackupCategory>, board: WidgetBoard?) {
        val archive = MessageArchive.get(context)
        if (BackupCategory.Messages in categories) json.optJSONObject("messages")?.let { messages ->
            archive.write { db -> restoreMessages(db, messages) }
        }
        if (BackupCategory.Links in categories) json.optJSONObject("links")?.let { links ->
            archive.write { db -> restoreLinks(db, links) }
        }
        if (BackupCategory.Notes in categories) json.optJSONArray("notes")?.let { notes ->
            archive.write { db ->
                notes.forEachRow { r ->
                    // The newer edit of the same note wins.
                    db.execSQL(
                        """INSERT INTO notes(uid, kind, text, done, pinned, position, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                           ON CONFLICT(uid) DO UPDATE SET text = excluded.text, done = excluded.done, pinned = excluded.pinned,
                           position = excluded.position, updated_at = excluded.updated_at WHERE excluded.updated_at > notes.updated_at""",
                        arrayOf(r.str(0), r.str(1), r.str(2), r.long(3), r.long(4), r.long(5), r.long(6), r.long(7))
                    )
                }
            }
        }
        if (BackupCategory.Settings in categories) json.optJSONObject("settings")?.let { settings ->
            settings.optJSONObject("appearance")?.let { restorePreferences(context.appearancePreferences, it, keep = layoutKeys) }
            settings.optJSONObject("search")?.let { restorePreferences(context.searchPreferences, it, keep = emptySet()) }
        }
        if (BackupCategory.Layout in categories) json.optJSONObject("layout")?.let { layout ->
            context.appearancePreferences.edit { it[dockApps] = layout.optString("dock") }
            board?.restoreKindLayout(layout.optString("widgets", "[]"))
        }
    }

    private fun restoreMessages(db: androidx.sqlite.db.SupportSQLiteDatabase, json: JSONObject) {
        json.optJSONArray("identities")?.forEachRow { r ->
            db.execSQL("INSERT OR IGNORE INTO identities(package, key, name, uri, phone) VALUES (?, ?, ?, ?, ?)",
                arrayOf(r.str(0), r.str(1), r.str(2), r.str(3), r.str(4)))
            r.blob(5)?.let { db.execSQL("UPDATE identities SET avatar = COALESCE(avatar, ?) WHERE package = ? AND key = ?", arrayOf(it, r.str(0), r.str(1))) }
        }
        json.optJSONArray("conversations")?.forEachRow { r ->
            db.execSQL("INSERT OR IGNORE INTO conversations(package, key, title, is_group, last_at, read_at) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(r.str(0), r.str(1), r.str(2), r.long(3), r.long(4), r.long(5)))
            db.execSQL("UPDATE conversations SET last_at = MAX(last_at, ?), read_at = MAX(read_at, ?) WHERE package = ? AND key = ?",
                arrayOf(r.long(4), r.long(5), r.str(0), r.str(1)))
            if (r.str(6) != null) db.execSQL(
                "UPDATE conversations SET identity_id = COALESCE(identity_id, (SELECT id FROM identities WHERE package = ? AND key = ?)) WHERE package = ? AND key = ?",
                arrayOf(r.str(6), r.str(7), r.str(0), r.str(1))
            )
        }
        json.optJSONArray("messages")?.forEachRow { r ->
            db.execSQL(
                """INSERT OR IGNORE INTO messages(conversation_id, identity_id, package, sender_name, text, posted_at, outgoing, fingerprint)
                   VALUES ((SELECT id FROM conversations WHERE package = ? AND key = ?), (SELECT id FROM identities WHERE package = ? AND key = ?), ?, ?, ?, ?, ?, ?)""",
                arrayOf(r.str(0), r.str(1), r.str(2), r.str(3), r.str(4), r.str(5), r.str(6), r.long(7), r.long(8), r.str(9))
            )
        }
        json.optJSONArray("messages")?.forEachRow { r ->
            if (r.str(10) != null) db.execSQL(
                "UPDATE messages SET duplicate_of = (SELECT id FROM messages WHERE fingerprint = ?) WHERE fingerprint = ? AND duplicate_of IS NULL",
                arrayOf(r.str(10), r.str(9))
            )
        }
    }

    private fun restoreLinks(db: androidx.sqlite.db.SupportSQLiteDatabase, json: JSONObject) {
        val people = mutableMapOf<Long, Long>()
        json.optJSONArray("people")?.forEachRow { r ->
            val lookup = r.str(2)
            val local = if (lookup != null) {
                db.execSQL("INSERT OR IGNORE INTO people(name, contact_lookup, created_at) VALUES (?, ?, ?)", arrayOf(r.str(1), lookup, System.currentTimeMillis()))
                db.query("SELECT id FROM people WHERE contact_lookup = ?", arrayOf(lookup)).use { it.moveToFirst(); it.getLong(0) }
            } else {
                db.insert("people", android.database.sqlite.SQLiteDatabase.CONFLICT_NONE, android.content.ContentValues().apply {
                    put("name", r.str(1)); put("created_at", System.currentTimeMillis())
                })
            }
            people[r.long(0)] = local
        }
        // A link only applies where this device has no manual or contact link for that account yet.
        json.optJSONArray("identities")?.forEachRow { r ->
            val person = people[r.long(2)] ?: return@forEachRow
            db.execSQL(
                """UPDATE identities SET person_id = ?, link_source = ? WHERE package = ? AND key = ?
                   AND COALESCE(link_source, '') NOT IN (?, ?)""",
                arrayOf(person, r.str(3), r.str(0), r.str(1), PeopleResolver.sourceManual, PeopleResolver.sourceContact)
            )
        }
        db.execSQL("DELETE FROM people WHERE NOT EXISTS (SELECT 1 FROM identities WHERE person_id = people.id)")
        json.optJSONArray("suggestions")?.forEachRow { r ->
            db.execSQL(
                """INSERT OR IGNORE INTO suggestions(identity_id, contact_lookup, contact_name, dismissed)
                   SELECT id, ?, ?, ? FROM identities WHERE package = ? AND key = ?""",
                arrayOf(r.str(2), r.str(3), r.long(4), r.str(0), r.str(1))
            )
        }
        json.optJSONArray("beeper")?.forEachRow { r ->
            db.execSQL(
                """INSERT OR IGNORE INTO beeper_links(beeper_identity, provider_identity)
                   SELECT b.id, p.id FROM identities b, identities p WHERE b.package = ? AND b.key = ? AND p.package = ? AND p.key = ?""",
                arrayOf(r.str(0), r.str(1), r.str(2), r.str(3))
            )
        }
    }

    private fun rows(cursor: android.database.Cursor): JSONArray = cursor.use {
        JSONArray().apply {
            while (it.moveToNext()) put(JSONArray().apply {
                for (i in 0 until it.columnCount) put(when (it.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    android.database.Cursor.FIELD_TYPE_INTEGER -> it.getLong(i)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> it.getDouble(i)
                    android.database.Cursor.FIELD_TYPE_BLOB -> JSONObject().put("b", Base64.encodeToString(it.getBlob(i), Base64.NO_WRAP))
                    else -> it.getString(i)
                })
            })
        }
    }

    private inline fun JSONArray.forEachRow(block: (JSONArray) -> Unit) { for (i in 0 until length()) optJSONArray(i)?.let(block) }
    private fun JSONArray.str(i: Int): String? = if (isNull(i)) null else opt(i)?.toString()
    private fun JSONArray.long(i: Int): Long = optLong(i, 0)
    private fun JSONArray.blob(i: Int): ByteArray? = optJSONObject(i)?.optString("b")?.let { Base64.decode(it, Base64.NO_WRAP) }

    private fun preferences(prefs: Preferences, exclude: Set<String>) = JSONObject().apply {
        prefs.asMap().forEach { (key, value) ->
            if (key.name in exclude) return@forEach
            put(key.name, JSONObject().apply {
                when (value) {
                    is Boolean -> put("t", "b").put("v", value)
                    is Int -> put("t", "i").put("v", value)
                    is Long -> put("t", "l").put("v", value)
                    is Float -> put("t", "f").put("v", value.toDouble())
                    is String -> put("t", "s").put("v", value)
                    is Set<*> -> put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                    else -> return@forEach
                }
            })
        }
    }

    private suspend fun restorePreferences(store: DataStore<Preferences>, json: JSONObject, keep: Set<String>) {
        store.edit { prefs ->
            val kept = prefs.asMap().filterKeys { it.name in keep }
            prefs.clear()
            kept.forEach { (key, value) -> @Suppress("UNCHECKED_CAST") prefs.set(key as Preferences.Key<Any>, value) }
            json.keys().forEach { name ->
                if (name in keep) return@forEach
                val entry = json.getJSONObject(name)
                prefs.put(name, entry)
            }
        }
    }

    private fun MutablePreferences.put(name: String, entry: JSONObject) {
        when (entry.optString("t")) {
            "b" -> this[booleanPreferencesKey(name)] = entry.getBoolean("v")
            "i" -> this[intPreferencesKey(name)] = entry.getInt("v")
            "l" -> this[longPreferencesKey(name)] = entry.getLong("v")
            "f" -> this[floatPreferencesKey(name)] = entry.getDouble("v").toFloat()
            "s" -> this[stringPreferencesKey(name)] = entry.getString("v")
            "ss" -> this[stringSetPreferencesKey(name)] = entry.getJSONArray("v").let { a -> List(a.length()) { a.getString(it) }.toSet() }
        }
    }
}
