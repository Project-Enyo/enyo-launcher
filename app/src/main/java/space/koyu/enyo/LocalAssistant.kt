package space.koyu.enyo

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal const val localAssistantId = "local"
private val localAssistantEnabled = booleanPreferencesKey("local_assistant_enabled")
internal val localInference = Mutex()
private val localModelImport = Mutex()
private const val maxModelBytes = 1_500_000_000L
private const val maxPromptLength = 1200

private fun modelFile(context: Context) = File(context.noBackupFilesDir, "assistant.litertlm")
internal fun supportsLocalAssistant(context: Context): Boolean {
    val memory = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
    return "arm64-v8a" in Build.SUPPORTED_64_BIT_ABIS && !memory.lowMemory &&
        memory.totalMem >= 5L * 1024 * 1024 * 1024
}

@Composable
internal fun localAssistantReady(): Boolean {
    val context = LocalContext.current
    val store = context.searchPreferences
    val enabled by remember(store) { store.data.map { it[localAssistantEnabled] == true } }.collectAsState(false)
    return enabled && modelFile(context).isFile
}

@Composable
internal fun LocalAssistantPreference() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = context.searchPreferences
    val ready = localAssistantReady()
    val supported = remember(context) { supportsLocalAssistant(context) }
    var importing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importing) {
            importing = true
            failed = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        localModelImport.withLock {
                            localInference.withLock {
                                val model = AtomicFile(modelFile(context))
                                val input = context.contentResolver.openInputStream(uri) ?: error("Model unavailable")
                                input.use {
                                    val output = model.startWrite()
                                    try {
                                        val buffer = ByteArray(256 * 1024)
                                        var total = 0L
                                        while (true) {
                                            ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            total += count
                                            check(total <= maxModelBytes)
                                            output.write(buffer, 0, count)
                                        }
                                        check(total > 0)
                                        model.finishWrite(output)
                                    } catch (error: Throwable) {
                                        model.failWrite(output)
                                        throw error
                                    }
                                }
                            }
                            store.edit { it[localAssistantEnabled] = true }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                } finally {
                    importing = false
                }
            }
        }
    }
    SettingsPanel(stringResource(R.string.local_ai)) {
        if (!supported) ListNote(stringResource(R.string.local_ai_unsupported))
        else {
            SettingsAction(stringResource(R.string.local_ai_import), Glyph.Sparkles, enabled = !importing,
                summary = stringResource(if (importing) R.string.local_ai_importing else R.string.local_ai_summary)) {
                picker.launch(arrayOf("*/*"))
            }
            if (ready) SettingsAction(stringResource(R.string.local_ai_remove), Glyph.Trash, enabled = !importing) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        localModelImport.withLock {
                            store.edit { it.remove(localAssistantEnabled) }
                            localInference.withLock { AtomicFile(modelFile(context)).delete() }
                        }
                    }
                }
            }
        }
        if (failed) ListNote(stringResource(R.string.local_ai_failed))
    }
}

private data class AssistantTurn(val prompt: String, val reply: String)
private data class SavedAssistantChat(val id: String, val title: String)

internal object AssistantChats {
    fun createSchema(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE assistant_chats (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, transcript TEXT NOT NULL, updated_at INTEGER NOT NULL
        )""")
    }

    private fun list(context: Context) = MessageArchive.get(context).read { db ->
        db.query("SELECT id, title FROM assistant_chats ORDER BY updated_at DESC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(SavedAssistantChat(cursor.getString(0), cursor.getString(1))) }
        }
    }.orEmpty()

    private fun load(context: Context, id: String): List<AssistantTurn> =
        MessageArchive.get(context).read { db ->
            db.query("SELECT transcript FROM assistant_chats WHERE id = ?", arrayOf(id)).use { cursor ->
                if (!cursor.moveToFirst()) emptyList() else {
                    val json = JSONArray(cursor.getString(0))
                    List(json.length()) { index ->
                        val turn = json.getJSONObject(index)
                        AssistantTurn(turn.getString("prompt"), turn.getString("reply"))
                    }
                }
            }
        }.orEmpty()

    private fun save(context: Context, id: String, turns: List<AssistantTurn>) {
        val title = turns.first().prompt.lineSequence().first().take(120)
        val json = JSONArray().apply {
            turns.forEach { turn -> put(JSONObject().put("prompt", turn.prompt).put("reply", turn.reply)) }
        }
        MessageArchive.get(context).write { db ->
            db.execSQL("INSERT OR REPLACE INTO assistant_chats VALUES (?, ?, ?, ?)",
                arrayOf(id, title, json.toString(), System.currentTimeMillis()))
        }
    }

    @Composable
    fun Section() {
        val context = LocalContext.current
        val ready = localAssistantReady()
        val saved = rememberArchive(emptyList<SavedAssistantChat>()) { list(it) }
        var chat by remember { mutableStateOf<String?>(null) }
        if (ready || saved.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(LocalTokens.current.gap)) {
            InboxHeading(stringResource(R.string.local_ai_conversations))
            ListCard {
                saved.forEach { entry ->
                    ListRow(entry.title, Modifier.appPress({ chat = entry.id }),
                        leading = { EnyoGlyph(Glyph.Sparkles) },
                        trailing = {
                            val label = stringResource(R.string.local_ai_delete_chat)
                            androidx.compose.material3.IconButton(onClick = {
                                MessageArchive.get(context).write { db ->
                                    db.execSQL("DELETE FROM assistant_chats WHERE id = ?", arrayOf(entry.id))
                                }
                            }, modifier = Modifier.semantics { contentDescription = label }) { EnyoGlyph(Glyph.Trash) }
                        })
                }
                if (saved.isEmpty()) ListNote(stringResource(R.string.local_ai_no_chats))
            }
        }
        chat?.let { Chat(it, null) { chat = null } }
    }

    @Composable
    fun Chat(id: String?, initial: String?, close: () -> Unit) {
        val context = LocalContext.current
        val tokens = LocalTokens.current
        val scope = rememberCoroutineScope()
        val ready = localAssistantReady()
        val chatId = remember(id) { id ?: UUID.randomUUID().toString() }
        var turns by remember(chatId) { mutableStateOf<List<AssistantTurn>>(emptyList()) }
        var loaded by remember(chatId) { mutableStateOf(id == null) }
        LaunchedEffect(chatId) {
            if (id != null) {
                turns = withContext(Dispatchers.IO) { load(context, id) }
                loaded = true
            }
        }
        var prompt by remember(chatId) { mutableStateOf(initial.orEmpty().take(maxPromptLength)) }
        var pending by remember(chatId) { mutableStateOf<String?>(null) }
        var partial by remember(chatId) { mutableStateOf("") }
        var failed by remember(chatId) { mutableStateOf(false) }
        fun send() {
            val text = prompt.trim()
            if (text.isEmpty() || pending != null || !ready) return
            pending = text
            prompt = ""
            partial = ""
            failed = false
            val history = turns
            scope.launch {
                try {
                    val reply = generateReply(context, history, text) { partial = it }
                    val updated = history + AssistantTurn(text, reply)
                    turns = updated
                    save(context, chatId, updated)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    prompt = text
                    failed = true
                } catch (_: LinkageError) {
                    prompt = text
                    failed = true
                } finally {
                    pending = null
                    partial = ""
                }
            }
        }
        EnyoSheet(stringResource(R.string.local_ai), close) {
            val scroll = rememberLazyListState()
            val count = (turns.size + if (pending != null) 1 else 0)
            LaunchedEffect(count) { if (count > 0) scroll.scrollToItem(count - 1) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = scroll,
                verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                items(turns) { turn ->
                    AssistantBubble(turn.prompt, true)
                    AssistantBubble(turn.reply, false)
                }
                pending?.let { text -> item {
                    AssistantBubble(text, true)
                    AssistantBubble(partial.ifBlank { stringResource(R.string.local_ai_working) }, false)
                } }
            }
            if (failed) ListNote(stringResource(R.string.local_ai_failed))
            if (ready && loaded) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                OutlinedTextField(prompt, { prompt = it.take(maxPromptLength) }, Modifier.weight(1f),
                    label = { Text(stringResource(R.string.local_ai_prompt)) }, maxLines = 4, enabled = pending == null)
                PillButton(stringResource(R.string.local_ai_send), filled = true) { send() }
            }
            else if (loaded && !ready) ListNote(stringResource(R.string.local_ai_summary))
        }
    }
}

@Composable
private fun AssistantBubble(text: String, outgoing: Boolean) {
    val tokens = LocalTokens.current
    Box(Modifier.fillMaxWidth().padding(vertical = tokens.gap / 2),
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(text, Modifier.fillMaxWidth(0.92f).clip(RoundedCornerShape(tokens.radius))
            .background(if (outgoing) tokens.widgetTrack else tokens.widgetSurface).padding(tokens.padding),
            style = tokens.body)
    }
}

private suspend fun generateReply(
    context: Context,
    history: List<AssistantTurn>,
    prompt: String,
    update: (String) -> Unit
): String = withContext(Dispatchers.IO) {
    localInference.withLock {
        check(supportsLocalAssistant(context))
        val engineConfig = EngineConfig(modelPath = modelFile(context).absolutePath,
            backend = Backend.CPU(threadCount = 2), maxNumTokens = 2048, cacheDir = context.cacheDir.absolutePath)
        val messages = history.takeLast(2).flatMap {
            listOf(Message.user(it.prompt.take(480)), Message.model(it.reply.take(480)))
        }
        Engine(engineConfig).use { engine ->
            engine.initialize()
            coroutineContext.ensureActive()
            engine.createConversation(ConversationConfig(initialMessages = messages, maxOutputToken = 384)).use { chat ->
                val response = StringBuilder()
                chat.sendMessageAsync(prompt).collect { chunk ->
                    chunk.contents.contents.filterIsInstance<Content.Text>().forEach { response.append(it.text) }
                    withContext(Dispatchers.Main) { update(response.toString()) }
                }
                response.toString().trim().also { check(it.isNotEmpty()) }
            }
        }
    }
}
