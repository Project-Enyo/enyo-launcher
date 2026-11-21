package space.koyu.enyo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.CalendarContract
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

private enum class TodayWidget(val title: Int, val startsWide: Boolean = false) {
    Clock(R.string.widget_clock, true),
    Calendar(R.string.widget_calendar, true),
    Battery(R.string.widget_battery),
    Network(R.string.wireless)
}

private val widgetWidths = stringPreferencesKey("today_wide")

@Composable
internal fun TodayBoard(foreground: Int) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    val preferences = context.appearancePreferences
    val default = TodayWidget.entries.filter { it.startsWide }.map { it.name }.toSet()
    val wide by remember(preferences) {
        preferences.data.map { it[widgetWidths]?.split(',')?.toSet() ?: default }
    }.collectAsState(default)
    val columns = if (tokens.size == CardSize.Tiny) 1 else 2
    var editing by remember { mutableStateOf(false) }
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
            runCatching {
                CalendarContract.Instances.query(
                    context.contentResolver, arrayOf(CalendarContract.Instances.TITLE), now.time,
                    now.time + 30L * 24 * 60 * 60 * 1000
                )?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
        }
    }
    val battery = rememberBattery()
    val connection = rememberConnection()

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(tokens.margin),
        horizontalArrangement = Arrangement.spacedBy(tokens.gap),
        verticalArrangement = Arrangement.spacedBy(tokens.gap)
    ) {
        if (columns > 1) item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val label = stringResource(if (editing) R.string.widget_done else R.string.widget_edit)
                Box(
                    Modifier.heightIn(min = tokens.touch).appPress({ editing = !editing })
                        .padding(horizontal = tokens.gap),
                    contentAlignment = Alignment.Center
                ) { Text(label, style = tokens.secondary) }
            }
        }
        TodayWidget.entries.filter { it != TodayWidget.Calendar || calendarAllowed }.forEach { widget ->
            val full = columns == 1 || widget.name in wide
            item(key = widget.name, span = { GridItemSpan(if (full) maxLineSpan else 1) }) {
                val label = stringResource(widget.title)
                Surface(
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
                            fun resize(expand: Boolean) {
                                scope.launch {
                                    preferences.edit { values ->
                                        val current = values[widgetWidths]?.split(',')?.filter(String::isNotBlank)?.toSet() ?: default
                                        values[widgetWidths] = (if (expand) current + widget.name else current - widget.name).joinToString(",")
                                    }
                                }
                            }
                            val change by rememberUpdatedState<(Boolean) -> Unit>(::resize)
                            val action = stringResource(if (full) R.string.widget_shrink else R.string.widget_expand)
                            Box(
                                Modifier.align(Alignment.End).heightIn(min = tokens.touch)
                                    .appPress({ resize(!full) })
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
