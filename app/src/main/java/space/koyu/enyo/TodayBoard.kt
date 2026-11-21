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
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
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
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlin.math.abs

private val widgetWidths = stringPreferencesKey("today_wide")

@Composable
internal fun TodayBoard(foreground: Int, visible: Boolean) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val board = LocalWidgetBoard.current
    val locale = LocalConfiguration.current.locales[0]
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
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(60_000L - now.time % 60_000L)
        }
    }
    val calendarAllowed = remember(foreground) {
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }
    val event by produceState<String?>(null, foreground, calendarAllowed, now.time / 60_000L) {
        value = if (!calendarAllowed) null else withContext(Dispatchers.IO) {
            nextTodayEvent(context, now.time)
        }
    }
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
            if (editing && board.migrated.size < TodayWidget.entries.size) item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { board.migrateDefaults(prompt = true) }, enabled = !board.adding) {
                    Text(stringResource(R.string.widget_convert_defaults), style = tokens.secondary)
                }
            }
            TodayWidget.entries.filter { it != TodayWidget.Calendar || calendarAllowed }.forEach { widget ->
                val placement = hosted[widget]
                if (placement == null && widget in board.migrated) return@forEach
                val full = columns == 1 || widget.name in wide
                item(key = widget.name, span = { GridItemSpan(if (full) maxLineSpan else 1) }) {
                    val label = stringResource(widget.title)
                    if (placement != null) {
                        val info = board.info(placement.id)
                        if (widget == TodayWidget.Weather) WeatherWidget(foreground, visible, render = false)
                        Column {
                            HostedWidget(board, placement, info, if (widget == TodayWidget.Weather) tokens.weatherWidgetHeight else tokens.cardHeight)
                            if (editing) {
                                WidgetEditActions(
                                    remove = { board.remove(placement) },
                                    resizeWidth = if (columns > 1) ({ resizeToday(widget, !full) }) else null,
                                wide = full
                                )
                            }
                        }
                    } else Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(min = tokens.cardHeight).animateContentSize(),
                        color = tokens.surface,
                        shape = RoundedCornerShape(tokens.radius),
                        shadowElevation = tokens.elevation[1]
                    ) {
                        Column(Modifier.padding(tokens.padding), verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                            when (widget) {
                                TodayWidget.Clock -> {
                                    Text(DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(now), style = tokens.value)
                                    Text(DateFormat.getDateInstance(DateFormat.FULL, locale).format(now), style = tokens.secondary)
                                }
                                TodayWidget.Calendar -> {
                                    Text(label, style = tokens.label)
                                    Text(event ?: stringResource(R.string.calendar_empty), style = tokens.state, maxLines = 2)
                                }
                                TodayWidget.Weather -> WeatherWidget(foreground, visible)
                                TodayWidget.Battery -> {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(tokens.padding)) {
                                        if (full) BatteryGlyph(battery)
                                        Column {
                                            Text(label, style = tokens.label)
                                            Text(battery?.let { NumberFormat.getPercentInstance(locale).format(it.level.toDouble()) }
                                                ?: stringResource(R.string.torch_unavailable), style = tokens.value)
                                            if (battery?.charging == true) Text(stringResource(R.string.widget_charging), style = tokens.secondary)
                                        }
                                    }
                                }
                                TodayWidget.Network -> {
                                    Text(label, style = tokens.label)
                                    Text(stringResource(connection), style = tokens.state)
                                }
                            }
                            if (editing && columns > 1) {
                                val change by rememberUpdatedState<(Boolean) -> Unit> { resizeToday(widget, it) }
                                val action = stringResource(if (full) R.string.widget_shrink else R.string.widget_expand)
                                Box(
                                    Modifier.align(Alignment.End).heightIn(min = tokens.touch)
                                        .appPress({ resizeToday(widget, !full) })
                                        .pointerInput(widget) {
                                            var distance = 0f
                                            detectHorizontalDragGestures(
                                                onDragStart = { distance = 0f },
                                                onDragEnd = {
                                                    if (abs(distance) >= tokens.widgetResizeDistance.toPx()) change(distance > 0)
                                                }
                                            ) { event, delta -> event.consume(); distance += delta }
                                        },
                                    contentAlignment = Alignment.CenterEnd
                                ) { Text(action, style = tokens.secondary) }
                            }
                        }
                    }
                }
            }
            for (widget in board.placements.filter { it.kind == null }) {
                val info = board.info(widget.id)
                val minimumWidth = info?.let {
                    if (it.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0 && it.minResizeWidth > 0)
                        it.minResizeWidth else it.minWidth
                } ?: 0
                val full = columns == 1 || widget.columns == 2 || minimumWidth > cellWidthPx
                val rows = info?.widgetRows(rowHeight, availableHeight, widget.rows) ?: widget.rows
                item(key = widget.id, span = { GridItemSpan(if (full) maxLineSpan else 1) }) {
                    Column {
                        HostedWidget(board, widget, info, tokens.cardHeight * rows)
                        if (editing) {
                            val canChangeWidth = columns > 1 && minimumWidth <= cellWidthPx &&
                                info != null && info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
                            val canChangeHeight = info != null && info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
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

@Composable
private fun BatteryGlyph(battery: BatteryReading?) {
    val tokens = LocalTokens.current
    Box(Modifier.size(tokens.widgetBatteryWidth, tokens.widgetBatteryHeight)) {
        Canvas(Modifier.matchParentSize()) {
            // Coordinates follow the interior of the Tabler battery viewport.
            val unitX = size.width / 24f
            val unitY = size.height / 12f
            val fill = (battery?.level ?: 0f).coerceIn(0f, 1f)
            if (fill > 0f) drawRoundRect(
                if (battery?.charging == true) tokens.accent else tokens.accentContainer,
                topLeft = Offset(6f * unitX, 3f * unitY),
                size = Size(11f * unitX * fill, 6f * unitY),
                cornerRadius = CornerRadius(unitY)
            )
        }
        Image(
            painterResource(Glyph.Battery.drawable), null, Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(tokens.accent)
        )
    }
}
