package space.koyu.enyo

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

internal const val localAssistantId = "local"
private val localAssistantEnabled = booleanPreferencesKey("local_assistant_enabled")
internal val localInference = Mutex()
private val localModelImport = Mutex()
private const val maxModelBytes = 1_500_000_000L
private const val maxPromptLength = 1200

private fun modelFile(context: Context) = File(context.noBackupFilesDir, "assistant.litertlm")
private fun memoryInfo(context: Context): ActivityManager.MemoryInfo =
    ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }

internal fun supportsLocalAssistant(context: Context): Boolean =
    "arm64-v8a" in Build.SUPPORTED_64_BIT_ABIS && memoryInfo(context).totalMem >= 5L * 1024 * 1024 * 1024

internal fun canRunLocalModels(context: Context): Boolean =
    supportsLocalAssistant(context) && !memoryInfo(context).lowMemory

private val recommendedAssistant = ModelAsset(
    "litert-community/Qwen3-0.6B-int4/resolve/01d8620f3233a158edc2aa2c84f5d5e3fa0c02c0/",
    "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm", "qwen3.litertlm", 347251840,
    "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"
)

internal suspend fun installRecommendedAssistant(context: Context, progress: suspend (Int) -> Unit) =
    withContext(Dispatchers.IO) {
        localModelImport.withLock {
            val staging = File(context.noBackupFilesDir, "model_downloads")
            downloadModels(staging, listOf(recommendedAssistant), progress)
            localInference.withLock {
                Files.move(File(staging, recommendedAssistant.name).toPath(), modelFile(context).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            context.searchPreferences.edit {
                it[localAssistantEnabled] = true
                it[defaultAssistant] = localAssistantId
            }
        }
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
    val download = modelDownloadState(ModelDownloadKind.Chat)
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
            if (!ready && !download.active) SettingsAction(stringResource(R.string.local_ai_download), Glyph.Sparkles,
                enabled = !importing, summary = stringResource(R.string.local_ai_download_summary), run = download.start)
            SettingsAction(stringResource(R.string.local_ai_import), Glyph.Folder, enabled = !importing && !download.active,
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
        ModelDownloadStatus(ModelDownloadKind.Chat, download)
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

    private suspend fun save(context: Context, id: String, turns: List<AssistantTurn>) {
        val title = turns.first().prompt.lineSequence().first().take(120)
        val json = JSONArray().apply {
            turns.forEach { turn -> put(JSONObject().put("prompt", turn.prompt).put("reply", turn.reply)) }
        }
        val saved = suspendCancellableCoroutine { result ->
            MessageArchive.get(context).writeResult({ committed: Unit? -> result.resume(committed != null) }) { db ->
                db.execSQL("INSERT OR REPLACE INTO assistant_chats VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(id, title, json.toString(), System.currentTimeMillis()))
            }
        }
        check(saved)
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
            val history = turns.let { previous ->
                if (previous.lastOrNull() == AssistantTurn(text, "")) previous.dropLast(1) else previous
            }
            val submitted = history + AssistantTurn(text, "")
            turns = submitted
            scope.launch {
                try {
                    save(context, chatId, submitted)
                    val reply = generateReply(context, history.filter { it.reply.isNotEmpty() }, text) { partial = it }
                    val updated = history + AssistantTurn(text, reply)
                    save(context, chatId, updated)
                    turns = updated
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
        var initialSubmitted by remember(chatId) { mutableStateOf(false) }
        LaunchedEffect(ready, loaded) {
            if (id == null && ready && loaded && !initialSubmitted && !initial.isNullOrBlank()) {
                initialSubmitted = true
                send()
            }
        }
        val title = turns.firstOrNull()?.prompt?.lineSequence()?.firstOrNull()?.take(120)
        val appName = stringResource(R.string.local_ai)
        ConversationView(title ?: appName, appName, close) {
            val scroll = rememberLazyListState()
            val count = turns.size
            LaunchedEffect(count, pending != null) { if (count > 0) scroll.scrollToItem(0) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = scroll, reverseLayout = true,
                contentPadding = PaddingValues(tokens.margin),
                verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                pending?.let { text -> item(key = "pending") {
                    Column(verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                        ConversationBubble(text, true)
                        ConversationBubble(partial.ifBlank { stringResource(R.string.local_ai_working) }, false)
                    }
                } }
                val history = if (pending != null) turns.dropLast(1) else turns
                items(history.asReversed()) { turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                        ConversationBubble(turn.prompt, true)
                        if (turn.reply.isNotBlank()) ConversationBubble(turn.reply, false)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = tokens.margin, vertical = tokens.gap),
                verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                if (failed) Text(stringResource(R.string.local_ai_failed), style = tokens.caption, color = tokens.danger)
                if (ready && loaded) MessageComposer(prompt, { prompt = it.take(maxPromptLength) },
                    stringResource(R.string.local_ai_prompt), enabled = pending == null, send = ::send)
                else if (loaded && !ready) ListNote(stringResource(R.string.local_ai_summary))
            }
        }
    }
}

internal suspend fun interpretMessageDraft(context: Context, query: String): MessageDraft? {
    val instruction = """
        Extract the recipient name and exact SMS text from the last input. Reply with JSON only.
        Input: Can you get a message over to Alice with the words I am running late
        Output: {"recipient":"Alice","body":"I am running late"}
        Input: Write to Marco and let him know the meeting is cancelled
        Output: {"recipient":"Marco","body":"the meeting is cancelled"}
        Input: Find the last message from Bob
        Output: {}
        Copy the recipient and message text verbatim. If there is no request to draft a message, return {}.
    """.trimIndent()
    return try {
        val result = generateReply(context, emptyList(), "$instruction\nInput: $query\nOutput:") {}
        modelMessageDraft(query, result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }
}

private suspend fun generateReply(
    context: Context,
    history: List<AssistantTurn>,
    prompt: String,
    update: (String) -> Unit
): String = withContext(Dispatchers.IO) {
    localInference.withLock {
        check(canRunLocalModels(context))
        val engineConfig = EngineConfig(modelPath = modelFile(context).absolutePath,
            backend = Backend.CPU(threadCount = 2), maxNumTokens = 1280, cacheDir = context.cacheDir.absolutePath)
        val messages = history.takeLast(1).flatMap {
            listOf(Message.user(it.prompt.take(480)), Message.model(it.reply.take(480)))
        }
        Engine(engineConfig).use { engine ->
            engine.initialize()
            coroutineContext.ensureActive()
            val config = ConversationConfig(initialMessages = messages, maxOutputToken = 256,
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1))
            engine.createConversation(config).use { chat ->
                val response = StringBuilder()
                // Use LiteRT's callback API so the flow uses the app's coroutine runtime.
                callbackFlow {
                    val completed = AtomicBoolean()
                    chat.sendMessageAsync(prompt, object : MessageCallback {
                        override fun onMessage(message: Message) { trySend(message) }
                        override fun onDone() { completed.set(true); close() }
                        override fun onError(throwable: Throwable) { completed.set(true); close(throwable) }
                    })
                    awaitClose { if (!completed.get()) runCatching { chat.cancelProcess() } }
                }.buffer(Channel.UNLIMITED).collect { chunk ->
                    chunk.contents.contents.filterIsInstance<Content.Text>().forEach { response.append(it.text) }
                    withContext(Dispatchers.Main) { update(response.toString()) }
                }
                response.toString().trim().also { check(it.isNotEmpty()) }
            }
        }
    }
}
