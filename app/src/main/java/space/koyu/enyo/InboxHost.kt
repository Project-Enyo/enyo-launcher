package space.koyu.enyo

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A chat app registered with Enyo through UnifiedInbox. */
internal data class InboxConnector(val packageName: String, val token: String, val features: Set<String>, val label: String?, val version: Int)

/**
 * Enyo as a UnifiedInbox host (github.com/Project-Enyo/UnifiedInbox, SPEC.md). Chat apps register
 * with Enyo and share their conversations; Enyo shows them in the unified inbox and sends replies,
 * read state and open requests back to the app that owns the chat.
 */
internal object InboxHost {
    private const val P = "com.github.projectenyo.unifiedinbox"
    const val actionRegister = "$P.host.REGISTER"
    const val actionUnregister = "$P.host.UNREGISTER"
    const val actionEvent = "$P.host.EVENT"
    private const val actionRegistered = "$P.connector.REGISTERED"
    private const val actionFailed = "$P.connector.REGISTRATION_FAILED"
    private const val actionUnregistered = "$P.connector.UNREGISTERED"
    private const val actionReply = "$P.connector.REPLY"
    private const val actionMarkRead = "$P.connector.MARK_READ"
    private const val actionOpen = "$P.connector.OPEN"
    private const val actionReact = "$P.connector.REACT"
    private const val actionFetchImage = "$P.connector.FETCH_IMAGE"
    private const val actionFetchFile = "$P.connector.FETCH_FILE"
    const val featureReply = "reply"
    const val featureMarkRead = "mark_read"
    const val featureOpen = "open"
    const val featureImages = "images"
    const val featureFiles = "files"
    const val featureReact = "react"
    const val featureReceipts = "receipts"
    const val featureReplyTo = "reply_to"
    const val featureSendAttachments = "send_attachments"
    const val featureSendLocation = "send_location"
    private const val version = 2
    private const val preferences = "unified_inbox_host"

    val connectors = MutableStateFlow<List<InboxConnector>>(emptyList())
    /** Replies a connector could not send, by request id, with its reason. */
    val failedReplies = MutableStateFlow<Map<String, String?>>(emptyMap())

    /** What the person sent through an app that has not come back as a message yet, shown as sending. */
    data class PendingSend(
        val request: String,
        val packageName: String,
        val conversation: String,
        val text: String,
        val files: Int,
        val place: Boolean,
        val time: Long
    )
    val pending = MutableStateFlow<List<PendingSend>>(emptyList())

    private fun track(request: String, packageName: String, conversation: String, text: String, files: Int = 0, place: Boolean = false) {
        pending.value = pending.value + PendingSend(request, packageName, conversation, text, files, place, System.currentTimeMillis())
    }

    /** One of the person's messages arrived from the app: it answers its request, or the oldest send of that chat. */
    private fun arrived(packageName: String, conversation: String, request: String?, time: Long) {
        val current = pending.value
        val match = current.firstOrNull { it.request == request }
            ?: current.filter { it.packageName == packageName && it.conversation == conversation && it.time - sendSlackMillis <= time }.minByOrNull { it.time }
        if (match != null) pending.value = current - match
    }

    private const val sendSlackMillis = 60_000L
    @Volatile private var loaded = false

    private fun store(context: Context) = context.getSharedPreferences(preferences, Context.MODE_PRIVATE)

    fun enabled(context: Context) = store(context).getBoolean("enabled", true)

    @Volatile private var groups: MutableStateFlow<Boolean>? = null

    /** Whether Inbox lists group chats, from every app. Off by default. */
    @Synchronized
    fun groupsShown(context: Context): MutableStateFlow<Boolean> =
        groups ?: MutableStateFlow(store(context).getBoolean("show_groups", false)).also { groups = it }

    fun setGroupsShown(context: Context, shown: Boolean) {
        store(context).edit().putBoolean("show_groups", shown).apply()
        groupsShown(context).value = shown
    }

    @Synchronized
    fun load(context: Context): List<InboxConnector> {
        if (!loaded) {
            connectors.value = runCatching {
                val saved = JSONArray(store(context).getString("connectors", "[]"))
                List(saved.length()) { index ->
                    saved.getJSONObject(index).let { row ->
                        val features = row.optJSONArray("features")
                        InboxConnector(row.getString("package"), row.getString("token"),
                            List(features?.length() ?: 0) { features!!.getString(it) }.toSet(),
                            row.optString("label").ifEmpty { null }, row.optInt("version", 1))
                    }
                }
            }.getOrDefault(emptyList())
            loaded = true
        }
        return connectors.value
    }

    @Synchronized
    private fun save(context: Context, next: List<InboxConnector>) {
        connectors.value = next
        store(context).edit().putString("connectors", JSONArray(next.map {
            JSONObject().put("package", it.packageName).put("token", it.token).put("features", JSONArray(it.features.toList()))
                .put("label", it.label).put("version", it.version)
        }).toString()).apply()
    }

    fun connector(context: Context, packageName: String) = load(context).firstOrNull { it.packageName == packageName }

    fun isConnector(context: Context, packageName: String) = connector(context, packageName) != null

    fun supports(context: Context, packageName: String, feature: String) = connector(context, packageName)?.features?.contains(feature) == true

    fun register(context: Context, packageName: String, intent: Intent) {
        val token = intent.getStringExtra("token")?.takeIf { it.length >= 16 } ?: return
        if (!enabled(context)) {
            send(context, packageName, Intent(actionFailed).putExtra("token", token).putExtra("reason", "ACTION_REQUIRED"))
            return
        }
        val features = intent.getStringArrayExtra("features").orEmpty().toSet()
        val current = load(context)
        val previous = current.firstOrNull { it.packageName == packageName }
        // A new token from the same app replaces its old registration; the history stays.
        val entry = InboxConnector(packageName, token, features, intent.getStringExtra("label"), intent.getIntExtra("version", 1))
        save(context, current.filterNot { it.packageName == packageName } + entry)
        if (previous == null) MessageArchive.get(context).revision.value = System.nanoTime()
        send(context, packageName, Intent(actionRegistered).putExtra("token", token).putExtra("version", version))
    }

    fun unregister(context: Context, packageName: String, token: String?, notify: Boolean) {
        val current = load(context)
        val entry = current.firstOrNull { it.packageName == packageName && (token == null || it.token == token) } ?: return
        save(context, current - entry)
        MessageArchive.get(context).forgetConnector(packageName)
        if (notify) send(context, packageName, Intent(actionUnregistered).putExtra("token", entry.token))
    }

    fun events(context: Context, packageName: String, token: String, json: String) {
        val entry = connector(context, packageName)?.takeIf { it.token == token } ?: return
        val all = runCatching { JSONArray(json) }.getOrNull() ?: return
        // Files answer a request of this process; everything else is stored.
        val events = JSONArray()
        for (index in 0 until all.length()) {
            val event = all.optJSONObject(index) ?: continue
            if (event.optString("type") == "file") fileArrived(packageName, event) else events.put(event)
        }
        MessageArchive.get(context).applyInboxEvents(packageName, events, featureImages in entry.features,
            fetchImage = { image -> fetchImage(context, packageName, image) },
            arrived = { conversation, request, time -> arrived(packageName, conversation, request, time) }) { request, reason ->
            failedReplies.value = failedReplies.value + (request to reason)
        }
    }

    /** Image ids already asked for in this process, so an image the app cannot send is not asked for on every message. */
    private val requestedImages = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Asks the app for the image [image] names; it answers with an image event. */
    private fun fetchImage(context: Context, packageName: String, image: String) {
        val entry = connector(context, packageName)?.takeIf { featureImages in it.features } ?: return
        if (!requestedImages.add("$packageName|$image")) return
        send(context, packageName, Intent(actionFetchImage).putExtra("token", entry.token).putExtra("image", image))
    }

    private val pendingFiles = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<org.json.JSONObject>>()
    private val fileLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    private fun fileArrived(packageName: String, event: org.json.JSONObject) {
        val request = event.optString("request").takeIf { it.isNotEmpty() } ?: return
        // Only the app a request went to may answer it.
        if (request.substringBefore('|') == packageName) pendingFiles.remove(request)?.complete(event)
    }

    /**
     * A message's attachment as a local file: asked from the app once, copied into Enyo's cache and
     * kept there for later views. Null when the app cannot provide it in time.
     */
    suspend fun file(context: Context, packageName: String, conversation: String, message: String, attachment: InboxAttachmentInfo): java.io.File? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val entry = connector(context, packageName)?.takeIf { featureFiles in it.features } ?: return@withContext null
            val name = java.security.MessageDigest.getInstance("SHA-256").digest("$packageName|$conversation|$message|${attachment.id}".toByteArray())
                .take(16).joinToString("") { "%02x".format(it) }
            val extension = attachment.name?.substringAfterLast('.', "")?.filter { it.isLetterOrDigit() }?.take(8)?.ifEmpty { null }
                ?: attachment.mime?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
            val target = java.io.File(context.cacheDir, "inbox_files").apply { mkdirs() }.resolve("$name.$extension")
            fileLocks.getOrPut(name) { kotlinx.coroutines.sync.Mutex() }.lock()
            try {
                if (target.isFile && target.length() > 0) return@withContext target
                val request = "$packageName|${java.util.UUID.randomUUID()}"
                val answer = kotlinx.coroutines.CompletableDeferred<org.json.JSONObject>()
                pendingFiles[request] = answer
                send(context, packageName, Intent(actionFetchFile).putExtra("token", entry.token).putExtra("conversation", conversation)
                    .putExtra("message", message).putExtra("attachment", attachment.id).putExtra("request", request))
                val event = kotlinx.coroutines.withTimeoutOrNull(fileTimeoutMillis) { answer.await() }
                pendingFiles.remove(request)
                val uri = event?.optString("uri")?.takeIf { it.startsWith("content://") }?.let(android.net.Uri::parse) ?: return@withContext null
                // The app's own provider must serve it, so another app's files cannot be passed off as this one's.
                val provider = context.packageManager.resolveContentProvider(uri.authority ?: return@withContext null, 0)
                if (provider?.packageName != packageName) return@withContext null
                val partial = java.io.File(target.path + ".part")
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                check(total <= maxFileBytes)
                                output.write(buffer, 0, read)
                            }
                        }
                    } ?: error("unavailable")
                    check(partial.renameTo(target))
                }.onFailure { partial.delete() }.getOrNull() ?: return@withContext null
                target
            } finally {
                fileLocks[name]?.unlock()
            }
        }

    private const val fileTimeoutMillis = 120_000L
    private const val maxFileBytes = 1_000_000_000L

    /** Adds or takes back the person's reaction through the app. */
    fun react(context: Context, packageName: String, conversation: String, message: String, emoji: String, remove: Boolean): Boolean {
        val entry = connector(context, packageName)?.takeIf { featureReact in it.features } ?: return false
        send(context, packageName, Intent(actionReact).putExtra("token", entry.token).putExtra("conversation", conversation)
            .putExtra("message", message).putExtra("emoji", emoji).putExtra("remove", remove))
        return true
    }

    /**
     * Sends files or a place through the app, optionally quoting [replyTo], with [text] as caption.
     * Returns the request id, or null when the app cannot send this.
     */
    fun sendRich(
        context: Context,
        packageName: String,
        conversation: String,
        text: String,
        replyTo: String? = null,
        attachments: List<android.net.Uri> = emptyList(),
        location: InboxLocationInfo? = null
    ): String? {
        val entry = connector(context, packageName) ?: return null
        if (attachments.isNotEmpty() && featureSendAttachments !in entry.features) return null
        if (location != null && featureSendLocation !in entry.features) return null
        if (attachments.isEmpty() && location == null && featureReply !in entry.features) return null
        val request = java.util.UUID.randomUUID().toString()
        attachments.forEach { uri -> runCatching { context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        track(request, packageName, conversation, text, attachments.size, location != null)
        send(context, packageName, Intent(actionReply).putExtra("token", entry.token).putExtra("conversation", conversation)
            .putExtra("text", text).putExtra("request", request)
            .apply {
                if (replyTo != null && featureReplyTo in entry.features) putExtra("replyTo", replyTo)
                if (attachments.isNotEmpty()) putParcelableArrayListExtra("attachments", ArrayList(attachments))
                location?.let { putExtra("location", JSONObject().put("lat", it.lat).put("lng", it.lng).putOpt("name", it.name).toString()) }
            })
        return request
    }

    fun setEnabled(context: Context, on: Boolean) {
        store(context).edit().putBoolean("enabled", on).apply()
        if (!on) load(context).forEach { unregister(context, it.packageName, it.token, notify = true) }
    }

    /** Sends a reply through the connector. Returns the request id, or null when the app cannot reply. */
    fun reply(context: Context, packageName: String, conversation: String, text: String): String? {
        val entry = connector(context, packageName)?.takeIf { featureReply in it.features } ?: return null
        val request = java.util.UUID.randomUUID().toString()
        track(request, packageName, conversation, text)
        send(context, packageName, Intent(actionReply).putExtra("token", entry.token)
            .putExtra("conversation", conversation).putExtra("text", text).putExtra("request", request))
        return request
    }

    fun markRead(context: Context, packageName: String, conversation: String, time: Long = System.currentTimeMillis()) {
        val entry = connector(context, packageName)?.takeIf { featureMarkRead in it.features } ?: return
        send(context, packageName, Intent(actionMarkRead).putExtra("token", entry.token).putExtra("conversation", conversation).putExtra("time", time))
    }

    /** Asks the app to show [conversation]; only in answer to the person, while Enyo is in front. */
    fun open(context: Context, packageName: String, conversation: String): Boolean {
        val entry = connector(context, packageName)?.takeIf { featureOpen in it.features } ?: return false
        return context.launchSafely(Intent(actionOpen).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("token", entry.token).putExtra("conversation", conversation))
    }

    private fun send(context: Context, packageName: String, intent: Intent) {
        context.sendBroadcast(intent.setPackage(packageName).addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
    }
}

/** Receives registrations and events from connectors. The sender is the identity Android vouches for. */
class InboxHostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sender = if (Build.VERSION.SDK_INT >= 34) sentFromPackage else null
        val caller = sender ?: runCatching { intent.getParcelableExtra("pi", PendingIntent::class.java)?.creatorPackage }.getOrNull() ?: return
        if (caller == context.packageName) return
        when (intent.action) {
            InboxHost.actionRegister -> InboxHost.register(context, caller, intent)
            InboxHost.actionUnregister -> InboxHost.unregister(context, caller, intent.getStringExtra("token"), notify = false)
            InboxHost.actionEvent -> InboxHost.events(context, caller, intent.getStringExtra("token") ?: return, intent.getStringExtra("events") ?: return)
        }
    }
}

/** Answers a connector's link request with a PendingIntent that names Enyo as the host. */
class InboxLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pi = PendingIntent.getBroadcast(this, 1, Intent("space.koyu.enyo.inbox.link").setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        setResult(RESULT_OK, Intent().putExtra("pi", pi))
        finish()
    }
}

/** Settings: whether apps may share chats with Enyo, and the apps that do. */
@Composable
internal fun InboxHostPreference() {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    var enabled by remember { mutableStateOf(InboxHost.enabled(context)) }
    val connectors by InboxHost.connectors.collectAsState()
    val groupsShown by remember(context) { InboxHost.groupsShown(context) }.collectAsState()
    LaunchedEffect(Unit) { InboxHost.load(context) }
    SettingsPanel(stringResource(R.string.inbox_host_title)) {
        SettingsToggle(stringResource(R.string.inbox_host_enable), enabled, summary = stringResource(R.string.inbox_host_summary), glyph = Glyph.Messages) {
            enabled = it
            InboxHost.setEnabled(context, it)
        }
        SettingsToggle(stringResource(R.string.inbox_groups), groupsShown, summary = stringResource(R.string.inbox_groups_summary), glyph = Glyph.People) {
            InboxHost.setGroupsShown(context, it)
        }
        if (enabled) {
            if (connectors.isEmpty()) ListNote(stringResource(R.string.inbox_host_none))
            connectors.forEach { connector ->
                ListRow(
                    sourceLabel(context, connector.packageName),
                    summary = connector.label ?: stringResource(R.string.inbox_host_sharing), summaryLines = 1,
                    leading = { EnyoGlyph(Glyph.Message, color = tokens.accent) },
                    trailing = { PillButton(stringResource(R.string.news_remove)) { InboxHost.unregister(context, connector.packageName, connector.token, notify = true) } }
                )
            }
        }
    }
}
