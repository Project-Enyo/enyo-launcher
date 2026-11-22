package space.koyu.enyo

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Beeper bridges other networks, so the same message can arrive from Beeper and from the original
 * app. No public identifier links the two copies, so text and timing alone never merge them: a
 * matching pair becomes a proposal. Once the person confirms that a Beeper account and an app
 * account are the same chat, later identical pairs between them are confident duplicates, and the
 * original app's copy points to Beeper's, which stays canonical. Nothing is deleted either way.
 */
internal object BeeperDuplicates {
    private val beeper = Messenger.Beeper.packages
    private const val windowMillis = 2L * 60 * 1000

    fun createSchema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS beeper_links (
                beeper_identity INTEGER NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
                provider_identity INTEGER NOT NULL REFERENCES identities(id) ON DELETE CASCADE,
                PRIMARY KEY(beeper_identity, provider_identity)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS duplicate_proposals (
                beeper_message INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                provider_message INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                dismissed INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(beeper_message, provider_message)
            )"""
        )
    }

    /** Checks a freshly stored incoming message; returns true when it is a confident duplicate. */
    fun check(db: SupportSQLiteDatabase, messageId: Long): Boolean {
        val row = db.query("SELECT package, identity_id, text, posted_at FROM messages WHERE id = ?", arrayOf(messageId)).use {
            if (it.moveToFirst()) Triple(it.getString(0), it.longOrNull(1), it.getString(2) to it.getLong(3)) else null
        } ?: return false
        val (pkg, identity, payload) = row
        val (text, postedAt) = payload
        if (identity == null) return false
        val fromBeeper = pkg in beeper
        val placeholders = beeper.joinToString(",") { "?" }
        val sideFilter = if (fromBeeper) "package NOT IN ($placeholders)" else "package IN ($placeholders)"
        val candidates = db.query(
            """SELECT id, identity_id FROM messages WHERE $sideFilter AND outgoing = 0 AND text = ? AND duplicate_of IS NULL
               AND ABS(posted_at - ?) <= ? AND id != ? ORDER BY ABS(posted_at - ?) LIMIT 3""",
            (beeper.toList() + listOf(text, postedAt, windowMillis, messageId, postedAt)).toTypedArray()
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.longOrNull(1)) } }
        for ((other, otherIdentity) in candidates) {
            if (otherIdentity == null) continue
            val beeperMessage = if (fromBeeper) messageId else other
            val providerMessage = if (fromBeeper) other else messageId
            val beeperIdentity = if (fromBeeper) identity else otherIdentity
            val providerIdentity = if (fromBeeper) otherIdentity else identity
            if (linked(db, beeperIdentity, providerIdentity)) {
                db.execSQL("UPDATE messages SET duplicate_of = ? WHERE id = ?", arrayOf(beeperMessage, providerMessage))
                return true
            }
            db.insert("duplicate_proposals", CONFLICT_IGNORE, ContentValues().apply {
                put("beeper_message", beeperMessage); put("provider_message", providerMessage)
            })
        }
        return false
    }

    private fun linked(db: SupportSQLiteDatabase, beeperIdentity: Long, providerIdentity: Long) =
        db.query("SELECT 1 FROM beeper_links WHERE beeper_identity = ? AND provider_identity = ?", arrayOf(beeperIdentity, providerIdentity))
            .use { it.moveToFirst() }

    /** Confirms a proposal: the two accounts are one chat, and this pair keeps Beeper's copy. */
    fun confirm(db: SupportSQLiteDatabase, proposal: DuplicateProposal) {
        db.execSQL(
            """INSERT OR IGNORE INTO beeper_links(beeper_identity, provider_identity)
               SELECT b.identity_id, p.identity_id FROM messages b, messages p WHERE b.id = ? AND p.id = ?
               AND b.identity_id IS NOT NULL AND p.identity_id IS NOT NULL""",
            arrayOf(proposal.beeperMessage, proposal.providerMessage)
        )
        db.execSQL("UPDATE messages SET duplicate_of = ? WHERE id = ?", arrayOf(proposal.beeperMessage, proposal.providerMessage))
        db.execSQL("DELETE FROM duplicate_proposals WHERE beeper_message = ? AND provider_message = ?", arrayOf(proposal.beeperMessage, proposal.providerMessage))
    }

    fun keepBoth(db: SupportSQLiteDatabase, proposal: DuplicateProposal) {
        db.execSQL("UPDATE duplicate_proposals SET dismissed = 1 WHERE beeper_message = ? AND provider_message = ?",
            arrayOf(proposal.beeperMessage, proposal.providerMessage))
    }

    /** Open proposals touching any of the given messages. */
    fun proposals(db: SupportSQLiteDatabase, messageIds: Collection<Long>): List<DuplicateProposal> {
        if (messageIds.isEmpty()) return emptyList()
        val ids = messageIds.joinToString(",")
        return db.query(
            """SELECT d.beeper_message, d.provider_message, p.package, p.text FROM duplicate_proposals d JOIN messages p ON p.id = d.provider_message
               WHERE d.dismissed = 0 AND (d.beeper_message IN ($ids) OR d.provider_message IN ($ids))"""
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(DuplicateProposal(cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3))) } }
    }
}

internal data class DuplicateProposal(val beeperMessage: Long, val providerMessage: Long, val providerPackage: String, val text: String)
