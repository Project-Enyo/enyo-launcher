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
    const val featureReply = "reply"
    const val featureMarkRead = "mark_read"
    const val featureOpen = "open"
    private const val version = 1
    private const val preferences = "unified_inbox_host"

    val connectors = MutableStateFlow<List<InboxConnector>>(emptyList())
    /** Replies a connector could not send, by request id, with its reason. */
    val failedReplies = MutableStateFlow<Map<String, String?>>(emptyMap())
    @Volatile private var loaded = false

    private fun store(context: Context) = context.getSharedPreferences(preferences, Context.MODE_PRIVATE)

    fun enabled(context: Context) = store(context).getBoolean("enabled", true)

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
        if (connector(context, packageName)?.token != token) return
        val events = runCatching { JSONArray(json) }.getOrNull() ?: return
        MessageArchive.get(context).applyInboxEvents(packageName, events) { request, reason ->
            failedReplies.value = failedReplies.value + (request to reason)
        }
    }

    fun setEnabled(context: Context, on: Boolean) {
        store(context).edit().putBoolean("enabled", on).apply()
        if (!on) load(context).forEach { unregister(context, it.packageName, it.token, notify = true) }
    }

    /** Sends a reply through the connector. Returns the request id, or null when the app cannot reply. */
    fun reply(context: Context, packageName: String, conversation: String, text: String): String? {
        val entry = connector(context, packageName)?.takeIf { featureReply in it.features } ?: return null
        val request = java.util.UUID.randomUUID().toString()
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
    LaunchedEffect(Unit) { InboxHost.load(context) }
    SettingsPanel(stringResource(R.string.inbox_host_title)) {
        SettingsToggle(stringResource(R.string.inbox_host_enable), enabled, summary = stringResource(R.string.inbox_host_summary), glyph = Glyph.Messages) {
            enabled = it
            InboxHost.setEnabled(context, it)
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
