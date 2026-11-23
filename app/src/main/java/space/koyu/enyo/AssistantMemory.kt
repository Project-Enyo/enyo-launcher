package space.koyu.enyo

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import kotlin.coroutines.resume

internal data class AssistantMemoryEntry(val id: Long, val text: String)

/**
 * What Local AI remembers about the person: facts they asked it to keep, and, with automatic
 * memory, lasting facts it noticed in their messages. Stored in the encrypted archive.
 */
internal object AssistantMemory {
    private val automaticKey = booleanPreferencesKey("local_assistant_auto_memory")
    private const val maxEntries = 200
    private const val maxLength = 200
    private const val perMessage = 3

    private val explicitRequest = Regex(
        """^\s*(?:please\s+|bitte\s+)?(?:remember|don'?t forget|merk\s+dir|merke\s+dir|vergiss\s+nicht)\s*(?:that|dass|,|:)?\s+(.+?)[.!]*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    /** Messages that say something about the person; others are not worth a memory pass. */
    private val firstPerson = Regex("""\b(?:i|i'm|im|i've|my|me|mine|ich|mein\w*|mir|mich)\b""", RegexOption.IGNORE_CASE)
    private val selfQuestion = Regex(
        """\b(?:who am i|what(?:'s| is) my name|what do you (?:know|remember) about me|wer bin ich|wie hei(?:ß|ss)e? ich|was wei(?:ß|ss)t du (?:über|von) mir)\b""",
        RegexOption.IGNORE_CASE
    )

    fun createSchema(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE assistant_memories (
            id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created_at INTEGER NOT NULL
        )""")
    }

    fun isSelfQuestion(text: String) = selfQuestion.containsMatchIn(text)

    fun explicitFact(text: String): String? = explicitRequest.matchEntire(text.trim())?.groupValues?.get(1)?.trim()
        ?.takeIf { it.length in 3..maxLength }

    fun automatic(context: Context) = context.searchPreferences.data.map { it[automaticKey] != false }

    suspend fun setAutomatic(context: Context, on: Boolean) = context.searchPreferences.edit { it[automaticKey] = on }

    /** Every memory, newest first. */
    fun list(context: Context): List<AssistantMemoryEntry> = MessageArchive.get(context).read { db ->
        db.query("SELECT id, text FROM assistant_memories ORDER BY created_at DESC, id DESC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(AssistantMemoryEntry(cursor.getLong(0), cursor.getString(1))) }
        }
    }.orEmpty()

    /** The memories for a prompt: all of them while they fit, or with [query] only those sharing its words. */
    fun promptBlock(context: Context, budget: Int, query: String? = null): String {
        val words = query?.let { words(it) }
        val chosen = list(context).filter { words == null || words(it.text).any { word -> word in words } }
        var used = 0
        return chosen.takeWhile { used += it.text.length + 3; used <= budget }.joinToString("\n") { "- ${it.text}" }
    }

    /** Saves new facts, skipping ones that are already known. Returns what was saved. */
    suspend fun add(context: Context, facts: List<String>): List<String> {
        val known = list(context).map { normalized(it.text) }
        val fresh = facts.map { it.trim().trimEnd('.').take(maxLength) }.filter { it.length >= 3 }
            .distinctBy { normalized(it) }
            .filter { fact -> val key = normalized(fact); known.none { it == key || it.contains(key) || key.contains(it) } }
        if (fresh.isEmpty()) return emptyList()
        val saved = suspendCancellableCoroutine { result ->
            MessageArchive.get(context).writeResult({ done: Unit? -> result.resume(done != null) }) { db ->
                val now = System.currentTimeMillis()
                fresh.forEachIndexed { index, fact ->
                    db.execSQL("INSERT INTO assistant_memories(text, created_at) VALUES (?, ?)", arrayOf<Any>(fact, now + index))
                }
                db.execSQL("DELETE FROM assistant_memories WHERE id NOT IN (SELECT id FROM assistant_memories ORDER BY created_at DESC LIMIT $maxEntries)")
            }
        }
        return if (saved) fresh else emptyList()
    }

    fun delete(context: Context, id: Long) = MessageArchive.get(context).write { db ->
        db.execSQL("DELETE FROM assistant_memories WHERE id = ?", arrayOf(id))
    }

    fun clear(context: Context) = MessageArchive.get(context).write { db -> db.execSQL("DELETE FROM assistant_memories") }

    /**
     * Lasting facts about the person in [message], found by the model. Each fact must reuse words of
     * the message, so the model cannot add things the person never said.
     */
    suspend fun extract(context: Context, message: String): List<String> {
        if (!firstPerson.containsMatchIn(message) || message.trim().endsWith('?')) return emptyList()
        val output = localTask(context, """
            Extract lasting facts the user states about themselves: name, family, home, work, preferences, plans, important dates.
            Write each fact briefly in the third person, starting with "User". Skip questions, requests and passing moods.
            Return a JSON object with one key "memories" holding a list, and no other text.
            Input: Hi, I'm Ada and I live in Lisbon
            Output: {"memories":["User's name is Ada","User lives in Lisbon"]}
            Input: Set a timer for ten minutes
            Output: {"memories":[]}
            Input: I'm vegetarian, can you suggest a dinner?
            Output: {"memories":["User is vegetarian"]}
            Input: Ich heiße Mara und arbeite als Ärztin
            Output: {"memories":["User heißt Mara","User arbeitet als Ärztin"]}
            Input: ${message.replace('\n', ' ').take(600)}
            Output:
        """.trimIndent()) ?: return emptyList()
        val list = modelIntentObject(output)?.optJSONArray("memories") ?: return emptyList()
        val source = words(message)
        return List(list.length()) { list.optString(it) }
            .map { it.trim() }
            .filter { fact -> fact.length in 6..maxLength && words(fact).any { it in source } }
            .take(perMessage)
    }

    private fun words(text: String) = Regex("""[\p{L}\p{N}]{3,}""").findAll(text.lowercase()).map { it.value }
        .filterNot { it in ignored }.toSet()

    private val ignored = setOf("user", "user's", "the", "and", "und", "der", "die", "das", "ist", "heißt")

    private fun normalized(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    /** A turn's saved memories, for storage with the chat. */
    fun encode(facts: List<String>): String? = facts.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() }
}

/** Settings: what Local AI remembers, automatic memory, and removing memories. */
@Composable
internal fun AssistantMemoryPreference() {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    val ready = localAssistantReady()
    val memories = rememberArchive(emptyList<AssistantMemoryEntry>()) { AssistantMemory.list(it) }
    val automatic by remember(context) { AssistantMemory.automatic(context) }.collectAsState(true)
    var confirmClear by remember { mutableStateOf(false) }
    if (!aiFeaturesEnabled() || (!ready && memories.isEmpty())) return
    SettingsPanel(stringResource(R.string.local_ai_memory)) {
        SettingsToggle(stringResource(R.string.local_ai_memory_auto), automatic,
            summary = stringResource(R.string.local_ai_memory_auto_summary), glyph = Glyph.Sparkles) { on ->
            scope.launch { AssistantMemory.setAutomatic(context, on) }
        }
        if (memories.isEmpty()) ListNote(stringResource(R.string.local_ai_memory_empty))
        memories.forEach { memory ->
            val label = stringResource(R.string.local_ai_memory_delete)
            ListRow(memory.text, summaryLines = 3, trailing = {
                IconButton(onClick = { AssistantMemory.delete(context, memory.id) },
                    modifier = Modifier.semantics { contentDescription = label }) { EnyoGlyph(Glyph.Trash) }
            })
        }
        if (memories.isNotEmpty()) SettingsAction(stringResource(R.string.local_ai_memory_clear), Glyph.Trash) { confirmClear = true }
    }
    if (confirmClear) {
        EnyoSheet(stringResource(R.string.local_ai_memory_clear), { confirmClear = false }) {
            Text(stringResource(R.string.local_ai_memory_clear_message), style = tokens.secondary)
            Row {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel), color = tokens.ink) }
                TextButton(onClick = {
                    confirmClear = false
                    AssistantMemory.clear(context)
                }) { Text(stringResource(R.string.local_ai_memory_clear), color = tokens.ink) }
            }
        }
    }
}
