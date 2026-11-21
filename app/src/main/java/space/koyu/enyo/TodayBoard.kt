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
    fun resizeToday(widget: TodayWidget, expand: Boolean) {
        scope.launch {
            preferences.edit { values ->
                val current = values[widgetWidths]?.split(',')?.filter(String::isNotBlank)?.toSet() ?: default
                values[widgetWidths] = (if (expand) current + widget.name else current - widget.name).joinToString(",")
            }
        }
    }
    val columns = if (tokens.size == CardSize.Tiny) 1 else 2
    var editing by remember { mutableStateOf(false) }
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

    LaunchedEffect(visible, battery) { if (visible) publishTodayWidget(context, TodayWidget.Battery) }
    LaunchedEffect(visible, event, calendarAllowed) { if (visible) publishTodayWidget(context, TodayWidget.Calendar, event) }
    LaunchedEffect(visible, connection) { if (visible) publishTodayWidget(context, TodayWidget.Network) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val availableHeight = constraints.maxHeight
        val rowHeight = with(density) { tokens.cardHeight.roundToPx().coerceAtLeast(1) }
        val cellWidth = (maxWidth - tokens.margin * 2 - if (columns > 1) tokens.gap else 0.dp) / columns
        val cellWidthPx = with(density) { cellWidth.roundToPx() }
        val hosted = board.placements.associateBy { it.kind }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(tokens.margin),
            horizontalArrangement = Arrangement.spacedBy(tokens.gap),
            verticalArrangement = Arrangement.spacedBy(tokens.gap)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { picker = true }, enabled = !board.adding) {
                        Text(stringResource(R.string.widget_add), style = tokens.secondary)
                    }
                    val label = stringResource(if (editing) R.string.widget_done else R.string.widget_edit)
                    Box(
                        Modifier.heightIn(min = tokens.touch).appPress({ editing = !editing })
                            .padding(horizontal = tokens.gap),
                        contentAlignment = Alignment.Center
                    ) { Text(label, style = tokens.secondary) }
                }
            }
            if (board.migrated.size < TodayWidget.entries.size) item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { board.migrateDefaults(prompt = true) }, enabled = !board.adding) {
                    Text(stringResource(R.string.widget_convert_defaults), style = tokens.secondary)
                }
            }
            TodayWidget.entries.filter { it != TodayWidget.Calendar || calendarAllowed }.forEach { widget ->
                val placement = hosted[widget] ?: return@forEach
                val full = columns == 1 || widget.name in wide
                item(key = widget.name, span = { GridItemSpan(if (full) maxLineSpan else 1) }) {
                    Column {
                        HostedWidget(board, placement, board.info(placement.id), tokens.todayWidgetHeight)
                        if (editing) {
                            WidgetEditActions(
                                remove = { board.remove(placement) },
                                resizeWidth = if (columns > 1) ({ resizeToday(widget, !full) }) else null,
                                wide = full
                            )
                        }
                    }
                }
            }
            for (widget in board.placements.filter { it.kind == null }) {
                val info = board.info(widget.id)
                val isEnyoWidget = info != null && TodayWidget.entries.any { it.component(context) == info.provider }
                val minimumWidth = info?.let {
                    if (it.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0 && it.minResizeWidth > 0)
                        it.minResizeWidth else it.minWidth
                } ?: 0
                val full = columns == 1 || widget.columns == 2 || minimumWidth > cellWidthPx
                val rows = info?.widgetRows(rowHeight, availableHeight, widget.rows) ?: widget.rows
                item(key = widget.id, span = { GridItemSpan(if (full) maxLineSpan else 1) }) {
                    Column {
                        HostedWidget(board, widget, info, if (isEnyoWidget) tokens.todayWidgetHeight else tokens.cardHeight * rows)
                        if (editing) {
                            val canChangeWidth = columns > 1 && minimumWidth <= cellWidthPx &&
                                info != null && info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
                            val canChangeHeight = !isEnyoWidget && info != null && info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
                            WidgetEditActions(
                                remove = { board.remove(widget) },
                                resizeWidth = if (canChangeWidth) ({ board.resize(widget, columns = if (full) 1 else 2) }) else null,
                                wide = full,
                                grow = if (canChangeHeight && info?.widgetRows(rowHeight, availableHeight, rows + 1) != rows)
                                    ({ board.resize(widget, rows = rows + 1) }) else null,
                                shrink = if (canChangeHeight && info?.widgetRows(rowHeight, availableHeight, rows - 1) != rows)
                                    ({ board.resize(widget, rows = rows - 1) }) else null,
                                configure = if (info?.configure != null) ({ board.configure(widget) }) else null
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetEditActions(
    remove: () -> Unit,
    resizeWidth: (() -> Unit)?,
    wide: Boolean,
    grow: (() -> Unit)? = null,
    shrink: (() -> Unit)? = null,
    configure: (() -> Unit)? = null
) {
    Row {
        if (resizeWidth != null) TextButton(onClick = resizeWidth) {
            Text(stringResource(if (wide) R.string.widget_shrink else R.string.widget_expand))
        }
        TextButton(onClick = remove) { Text(stringResource(R.string.widget_remove)) }
    }
    if (grow != null || shrink != null || configure != null) Row {
        if (grow != null) TextButton(onClick = grow) { Text(stringResource(R.string.widget_grow)) }
        if (shrink != null) TextButton(onClick = shrink) { Text(stringResource(R.string.widget_reduce)) }
        if (configure != null) TextButton(onClick = configure) { Text(stringResource(R.string.widget_configure)) }
    }
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
