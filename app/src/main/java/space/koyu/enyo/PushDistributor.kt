package space.koyu.enyo

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/** One app registered with Enyo as its UnifiedPush distributor, and the ntfy topic behind it. */
internal data class PushRegistration(val token: String, val packageName: String, val topic: String, val description: String?)

/**
 * Enyo as a UnifiedPush distributor backed by ntfy. Each registration gets its own topic named
 * `up` and 12 random characters on the configured ntfy server (ntfy.sh by default), and its
 * endpoint is that topic's URL. One streaming connection follows every topic and hands each push
 * to its app unchanged; the app's connector decrypts it.
 */
internal object PushDistributor {
    const val defaultServer = "https://ntfy.sh"
    private const val preferences = "unified_push"
    private const val topicAlphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    const val actionRegister = "org.unifiedpush.android.distributor.REGISTER"
    const val actionUnregister = "org.unifiedpush.android.distributor.UNREGISTER"
    const val actionAck = "org.unifiedpush.android.distributor.MESSAGE_ACK"
    private const val actionNewEndpoint = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    private const val actionFailed = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    private const val actionUnregistered = "org.unifiedpush.android.connector.UNREGISTERED"
    private const val actionMessage = "org.unifiedpush.android.connector.MESSAGE"

    val registrations = MutableStateFlow<List<PushRegistration>>(emptyList())
    val connected = MutableStateFlow(false)
    @Volatile private var loaded = false

    private fun store(context: Context) = context.getSharedPreferences(preferences, Context.MODE_PRIVATE)

    fun server(context: Context): String = store(context).getString("server", null) ?: defaultServer

    fun enabled(context: Context): Boolean = store(context).getBoolean("enabled", true)

    @Synchronized
    fun load(context: Context): List<PushRegistration> {
        if (!loaded) {
            registrations.value = runCatching {
                val saved = JSONArray(store(context).getString("registrations", "[]"))
                List(saved.length()) { index ->
                    saved.getJSONObject(index).let {
                        PushRegistration(it.getString("token"), it.getString("package"), it.getString("topic"), it.optString("description").ifBlank { null })
                    }
                }
            }.getOrDefault(emptyList())
            loaded = true
        }
        return registrations.value
    }

    @Synchronized
    private fun save(context: Context, next: List<PushRegistration>) {
        registrations.value = next
        store(context).edit().putString("registrations", JSONArray(next.map {
            JSONObject().put("token", it.token).put("package", it.packageName).put("topic", it.topic).put("description", it.description)
        }).toString()).apply()
    }

    fun endpoint(context: Context, registration: PushRegistration) = "${server(context).trimEnd('/')}/${registration.topic}?up=1"

    fun register(context: Context, packageName: String, token: String, description: String?) {
        if (!enabled(context)) {
            send(context, packageName, Intent(actionFailed).putExtra("token", token).putExtra("reason", "ACTION_REQUIRED"))
            return
        }
        val current = load(context)
        val existing = current.firstOrNull { it.token == token && it.packageName == packageName }
        val registration = existing ?: PushRegistration(token, packageName, newTopic(), description).also { save(context, current + it) }
        send(context, packageName, Intent(actionNewEndpoint).putExtra("token", token).putExtra("endpoint", endpoint(context, registration)))
        PushConnection.start(context)
    }

    fun unregister(context: Context, packageName: String, token: String, notify: Boolean = true) {
        val current = load(context)
        val removed = current.filter { it.token == token && it.packageName == packageName }
        if (removed.isEmpty()) return
        save(context, current - removed.toSet())
        if (notify) send(context, packageName, Intent(actionUnregistered).putExtra("token", token))
        PushConnection.restart(context)
    }

    /** Removes an app from the Settings list; the app learns that it has to pick a distributor again. */
    fun remove(context: Context, registration: PushRegistration) = unregister(context, registration.packageName, registration.token)

    fun setServer(context: Context, url: String) {
        val clean = url.trim().trimEnd('/').ifBlank { defaultServer }
        if (clean == server(context)) return
        store(context).edit().putString("server", clean).apply()
        // Every app gets a new endpoint on the new server.
        load(context).forEach { registration ->
            send(context, registration.packageName, Intent(actionNewEndpoint).putExtra("token", registration.token).putExtra("endpoint", endpoint(context, registration)))
        }
        PushConnection.restart(context)
    }

    fun setEnabled(context: Context, on: Boolean) {
        store(context).edit().putBoolean("enabled", on).apply()
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        listOf(PushRegisterReceiver::class.java, PushLinkActivity::class.java).forEach { component ->
            context.packageManager.setComponentEnabledSetting(ComponentName(context, component), state, PackageManager.DONT_KILL_APP)
        }
        if (on) PushConnection.start(context)
        else {
            load(context).forEach { unregister(context, it.packageName, it.token) }
            PushConnection.stop(context)
        }
    }

    fun deliver(context: Context, topic: String, id: String, body: ByteArray) {
        val registration = load(context).firstOrNull { it.topic == topic } ?: return
        send(context, registration.packageName, Intent(actionMessage)
            .putExtra("token", registration.token).putExtra("bytesMessage", body).putExtra("id", id))
    }

    private fun send(context: Context, packageName: String, intent: Intent) {
        context.sendBroadcast(intent.setPackage(packageName))
    }

    private fun newTopic(): String {
        val random = SecureRandom()
        return "up" + (1..12).map { topicAlphabet[random.nextInt(topicAlphabet.length)] }.joinToString("")
    }
}

/** Receives REGISTER and UNREGISTER from apps' UnifiedPush connectors. */
class PushRegisterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token")?.takeIf { it.isNotBlank() } ?: return
        val caller = callerPackage(intent) ?: return
        when (intent.action) {
            PushDistributor.actionRegister -> PushDistributor.register(context, caller, token, intent.getStringExtra("message"))
            PushDistributor.actionUnregister -> PushDistributor.unregister(context, caller, token)
            PushDistributor.actionAck -> Unit
        }
    }

    /**
     * The app that sent the request: the identity Android shares since API 34, otherwise the creator
     * of the PendingIntent the connector attaches. The plain application extra alone is not trusted.
     */
    private fun callerPackage(intent: Intent): String? {
        if (Build.VERSION.SDK_INT >= 34) sentFromPackage?.let { return it }
        val pi = runCatching { intent.getParcelableExtra("pi", PendingIntent::class.java) }.getOrNull()
        return pi?.creatorPackage
    }
}

/** Answers a connector's link request with a PendingIntent that names Enyo as the distributor. */
class PushLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pi = PendingIntent.getBroadcast(this, 0, Intent("space.koyu.enyo.push.link").setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        setResult(RESULT_OK, Intent().putExtra("pi", pi))
        finish()
    }
}

/** Restarts the push connection after a reboot or an update when apps are registered. */
class PushBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (PushDistributor.enabled(context) && PushDistributor.load(context).isNotEmpty()) PushConnection.start(context)
    }
}

/**
 * Keeps one ntfy JSON stream open for every registered topic while apps are registered. Runs as a
 * foreground service so Android keeps it alive; reconnects with backoff and resumes after the last
 * message it delivered, so nothing published during a reconnect is lost.
 */
class PushConnection : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val registrations = PushDistributor.load(this)
        if (!PushDistributor.enabled(this) || registrations.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(notificationId, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(notificationId, notification())
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == actionRestart || job?.isActive != true) {
            job?.cancel()
            job = scope.launch { follow() }
        }
        if (networkCallback == null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // A new network ends a stalled stream at once instead of after the read timeout.
                    job?.cancel()
                    job = scope.launch { follow() }
                }
            }
            runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }
                .onSuccess { networkCallback = callback }
        }
        return START_STICKY
    }

    private suspend fun follow() {
        var backoff = minBackoffMillis
        val state = getSharedPreferences("unified_push_stream", Context.MODE_PRIVATE)
        while (scope.isActive) {
            val topics = PushDistributor.load(this).map { it.topic }.distinct()
            if (topics.isEmpty()) break
            val since = state.getString("since", null)
            val server = PushDistributor.server(this).trimEnd('/')
            val url = URL("$server/${topics.joinToString(",")}/json" + if (since != null) "?since=${URLEncoder.encode(since, "UTF-8")}" else "")
            val connection = runCatching { url.openConnection() as HttpURLConnection }.getOrNull()
            if (connection != null) try {
                connection.connectTimeout = 20_000
                connection.readTimeout = streamTimeoutMillis
                connection.setRequestProperty("User-Agent", "Enyo")
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    PushDistributor.connected.value = true
                    backoff = minBackoffMillis
                    connection.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!scope.isActive) break
                            val event = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            if (event.optString("event") != "message") continue
                            val id = event.optString("id")
                            val topic = event.optString("topic")
                            val text = event.optString("message")
                            val body = if (event.optString("encoding") == "base64") runCatching { Base64.decode(text, Base64.DEFAULT) }.getOrNull()
                                else text.toByteArray()
                            if (body != null && id.isNotEmpty()) PushDistributor.deliver(this, topic, id, body)
                            if (id.isNotEmpty()) state.edit().putString("since", id).apply()
                        }
                    }
                }
            } catch (_: java.io.IOException) {
            } finally {
                connection.disconnect()
                PushDistributor.connected.value = false
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(maxBackoffMillis)
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel, getString(R.string.push_channel), NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
        })
        return Notification.Builder(this, channel)
            .setSmallIcon(Glyph.Push.drawable)
            .setContentTitle(getString(R.string.push_running))
            .setContentText(getString(R.string.push_running_detail, PushDistributor.load(this).size))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        networkCallback?.let { callback -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) } }
        scope.cancel()
        PushDistributor.connected.value = false
        super.onDestroy()
    }

    companion object {
        private const val channel = "push"
        private const val notificationId = 4100
        private const val actionRestart = "restart"
        private const val minBackoffMillis = 2_000L
        private const val maxBackoffMillis = 5 * 60_000L
        private const val streamTimeoutMillis = 120_000

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, PushConnection::class.java)) }
        }

        fun restart(context: Context) {
            if (PushDistributor.load(context).isEmpty()) stop(context)
            else runCatching { context.startForegroundService(Intent(context, PushConnection::class.java).setAction(actionRestart)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushConnection::class.java))
        }
    }
}
