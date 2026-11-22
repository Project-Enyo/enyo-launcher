package space.koyu.enyo

import android.Manifest
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val widgetWidths = stringPreferencesKey("today_wide")

@Composable
internal fun TodayBoard(foreground: Int, visible: Boolean, weatherTap: Long) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val board = LocalWidgetBoard.current
    val scope = rememberCoroutineScope()
    val preferences = context.appearancePreferences
    val default = TodayWidget.entries.filter { it.startsWide }.map { it.name }.toSet()
    val wide by remember(preferences) {
        preferences.data.map { it[widgetWidths]?.split(',')?.toSet() ?: default }
    }.collectAsState(default)
    var picker by remember { mutableStateOf(false) }
    LaunchedEffect(visible, board.providers) {
        if (visible && board.providers.isNotEmpty()) board.migrateDefaults(prompt = false)
    }
    if (picker) WidgetPicker(board) { picker = false }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L - now % 60_000L)
        }
    }
    val calendarAllowed = remember(foreground) {
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }
    val event by produceState<String?>(null, foreground, calendarAllowed, now / 60_000L) {
        value = if (!calendarAllowed) null else withContext(Dispatchers.IO) {
            nextTodayEvent(context, now)
        }
    }
    val weatherProvider = TodayWidget.Weather.component(context)
    val hasWeather = board.placements.any {
        it.kind == TodayWidget.Weather || board.info(it.id)?.provider == weatherProvider
    }
    ObserveTodayWeather(foreground, visible && (hasWeather || weatherTap > 0), weatherTap)
    val battery = rememberBattery()
    val connection = rememberConnection()

    LaunchedEffect(visible, tokens.widgetSurface) { if (visible) publishTodayWidget(context, TodayWidget.Clock) }
    LaunchedEffect(visible, battery, tokens.widgetSurface, tokens.accent) { if (visible) publishTodayWidget(context, TodayWidget.Battery) }
    LaunchedEffect(visible, event, calendarAllowed, tokens.widgetSurface) { if (visible) publishTodayWidget(context, TodayWidget.Calendar, event) }
    LaunchedEffect(visible, connection, tokens.widgetSurface) { if (visible) publishTodayWidget(context, TodayWidget.Network) }
    LaunchedEffect(visible, tokens.widgetSurface) {
        if (visible) {
            NewsStore.load(context)
            publishTodayWidget(context, TodayWidget.News)
            NewsStore.refresh(context)
        }
    }

    // The saved width choice of Enyo's own widgets only seeds the one time conversion to the cell grid.
    WidgetGrid(
        board,
        hidden = { it.kind == TodayWidget.Calendar && !calendarAllowed },
        wideKinds = wide,
        openPicker = { picker = true },
        convertDefaults = if (board.migrated.size < TodayWidget.entries.size) ({ board.migrateDefaults(prompt = true) }) else null
    )
}

private data class BatteryReading(val level: Float, val charging: Boolean)

@Composable
private fun rememberBattery(): BatteryReading? {
    val context = LocalContext.current
    var status by remember { mutableStateOf<BatteryReading?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val power = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                    status = BatteryReading((level.toFloat() / scale).coerceIn(0f, 1f),
                        power == BatteryManager.BATTERY_STATUS_CHARGING || power == BatteryManager.BATTERY_STATUS_FULL)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val initial = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        initial?.let { receiver.onReceive(context, it) }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return status
}

@Composable
private fun rememberConnection(): Int {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    fun label(capabilities: NetworkCapabilities?) = when {
        capabilities == null -> R.string.disconnected
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.string.wifi_connected
        else -> R.string.connected
    }
    var state by remember(manager) { mutableIntStateOf(label(manager.getNetworkCapabilities(manager.activeNetwork))) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { state = label(capabilities) }
            override fun onLost(network: Network) { state = label(manager.getNetworkCapabilities(manager.activeNetwork)) }
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        onDispose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
    }
    return state
}
