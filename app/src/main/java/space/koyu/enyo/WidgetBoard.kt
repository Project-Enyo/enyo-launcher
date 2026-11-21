package space.koyu.enyo

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.SizeF
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

internal enum class TodayWidget(val title: Int, val provider: Class<out TodayProvider>, val startsWide: Boolean = false) {
    Clock(R.string.widget_clock, ClockWidget::class.java, true),
    Calendar(R.string.widget_calendar, CalendarWidget::class.java, true),
    Weather(R.string.widget_weather, WeatherAppWidget::class.java),
    Battery(R.string.widget_battery, BatteryWidget::class.java),
    Network(R.string.wireless, InternetWidget::class.java);

    fun component(context: Context) = ComponentName(context, provider)
}

internal data class WidgetPlacement(
    val id: Int,
    val kind: TodayWidget?,
    val columns: Int,
    val rows: Int
)

internal class WidgetBoard(private val activity: EnyoActivity) {
    private val manager = AppWidgetManager.getInstance(activity)
    private val store = activity.getSharedPreferences("today_widgets", Context.MODE_PRIVATE)
    private val host = object : AppWidgetHost(activity, 1) {
        override fun onProvidersChanged() {
            refreshProviders()
        }
    }
    var placements by mutableStateOf(readPlacements())
        private set
    var migrated: Set<TodayWidget> by mutableStateOf(readMigrated())
        private set
    var providers by mutableStateOf<List<AppWidgetProviderInfo>>(emptyList())
        private set
    private var triedSilent = false
    private var pending: WidgetPlacement? = readPending()
    var adding by mutableStateOf(pending != null)
        private set

    fun start() {
        runCatching { host.startListening() }
        refreshProviders()
    }

    fun stop() {
        runCatching { host.stopListening() }
    }

    fun refreshProviders() {
        providers = runCatching {
            val pm = activity.packageManager
            manager.getInstalledProvidersForProfile(Process.myUserHandle())
                .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
                .sortedBy { it.loadLabel(pm).orEmpty().lowercase() }
        }.getOrDefault(emptyList())
    }

    fun info(id: Int): AppWidgetProviderInfo? = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()

    fun makeView(context: Context, widget: WidgetPlacement, info: AppWidgetProviderInfo) =
        host.createView(context, widget.id, info).apply {
            if (info.provider.packageName == context.packageName) setPadding(0, 0, 0, 0)
        }

    fun migrateDefaults(prompt: Boolean) {
        if (adding || !prompt && triedSilent) return
        if (!prompt) triedSilent = true
        bindRemainingDefaults(prompt)
    }

    private fun bindRemainingDefaults(prompt: Boolean) {
        for (kind in TodayWidget.entries - migrated) {
            val info = providers.firstOrNull { it.provider == kind.component(activity) } ?: continue
            add(info, kind, prompt)
            if (adding) return
        }
    }

    fun add(provider: AppWidgetProviderInfo, kind: TodayWidget? = null, prompt: Boolean = true) {
        if (adding || provider.profile != Process.myUserHandle()) return
        val id = runCatching { host.allocateAppWidgetId() }.getOrElse { failed(); return }
        val columns = if (kind != null) 1 else provider.targetCellWidth.coerceIn(1, 2).takeIf {
            provider.targetCellWidth > 0
        } ?: 2
        val widget = WidgetPlacement(id, kind, columns, provider.targetCellHeight.coerceIn(1, widgetMaxRows))
        savePending(widget)
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
        }
        val bound = runCatching {
            manager.bindAppWidgetIdIfAllowed(id, provider.profile, provider.provider, options)
        }.getOrDefault(false)
        if (bound) afterBinding(widget)
        else if (!prompt) discard(widget)
        else try {
            activity.requestWidgetBinding(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
            })
        } catch (_: RuntimeException) {
            discard(widget)
            failed()
        }
    }

    fun bindingResult(success: Boolean) {
        val widget = pending ?: return
        if (success) {
            afterBinding(widget)
            if (!adding && widget.kind != null) bindRemainingDefaults(prompt = false)
        } else discard(widget)
    }

    private fun afterBinding(widget: WidgetPlacement) {
        val provider = info(widget.id)
        if (provider == null) {
            discard(widget)
            failed()
            return
        }
        val optional = provider.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (provider.configure == null || optional) {
            complete(widget)
        } else configure(widget)
    }

    fun configure(widget: WidgetPlacement) {
        if (adding && pending?.id != widget.id) return
        savePending(widget)
        try {
            host.startAppWidgetConfigureActivityForResult(activity, widget.id, 0, widgetConfigureRequest, null)
        } catch (_: RuntimeException) {
            if (placements.none { it.id == widget.id }) discard(widget) else savePending(null)
            failed()
        }
    }

    fun configurationResult(success: Boolean) {
        val widget = pending ?: return
        if (placements.any { it.id == widget.id }) savePending(null)
        else if (success) complete(widget) else discard(widget)
    }

    private fun complete(widget: WidgetPlacement) {
        val next = placements + widget
        val converted = widget.kind?.let { migrated + it } ?: migrated
        store.edit().putString("placements", encode(next))
            .putStringSet("migrated", converted.mapTo(mutableSetOf()) { it.name })
            .remove("pending").apply()
        placements = next
        migrated = converted
        pending = null
        adding = false
    }

    private fun discard(widget: WidgetPlacement) {
        runCatching { host.deleteAppWidgetId(widget.id) }
        savePending(null)
    }

    fun remove(widget: WidgetPlacement) {
        runCatching { host.deleteAppWidgetId(widget.id) }
        save(placements.filterNot { it.id == widget.id })
    }

    fun resize(widget: WidgetPlacement, columns: Int = widget.columns, rows: Int = widget.rows) {
        save(placements.map { if (it.id == widget.id) it.copy(columns = columns, rows = rows) else it })
    }

    private fun save(next: List<WidgetPlacement>) {
        store.edit().putString("placements", encode(next)).apply()
        placements = next
    }

    private fun savePending(next: WidgetPlacement?) {
        store.edit().putString("pending", next?.let { encode(listOf(it)) }).apply()
        pending = next
        adding = next != null
    }

    private fun failed() {
        Toast.makeText(activity, R.string.widget_add_failed, Toast.LENGTH_SHORT).show()
    }

    private fun readPlacements() = decode(store.getString("placements", null))
    private fun readPending() = decode(store.getString("pending", null)).firstOrNull()
    private fun readMigrated() = TodayWidget.entries.filterTo(mutableSetOf()) {
        it.name in store.getStringSet("migrated", emptySet()).orEmpty()
    }

    private fun encode(widgets: List<WidgetPlacement>) = JSONArray().apply {
        widgets.forEach { widget ->
            put(JSONObject().put("id", widget.id).put("kind", widget.kind?.name)
                .put("columns", widget.columns).put("rows", widget.rows))
        }
    }.toString()

    private fun decode(raw: String?) = runCatching {
        val saved = JSONArray(raw ?: "[]")
        List(saved.length()) { index ->
            val item = saved.getJSONObject(index)
            WidgetPlacement(item.getInt("id"), TodayWidget.entries.firstOrNull { it.name == item.optString("kind") },
                item.optInt("columns", 1).coerceIn(1, 2), item.optInt("rows", 1).coerceIn(1, widgetMaxRows))
        }
    }.getOrDefault(emptyList())
}

internal const val widgetConfigureRequest = 225
private const val widgetMaxRows = 5
internal val LocalWidgetBoard = staticCompositionLocalOf<WidgetBoard> { error("Widget board unavailable") }

internal fun AppWidgetProviderInfo.widgetRows(rowHeightPx: Int, availablePx: Int, saved: Int): Int {
    val minimum = if (resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0 && minResizeHeight > 0) minResizeHeight else minHeight
    val minimumRows = ceil(minimum.toFloat() / rowHeightPx).toInt().coerceIn(1, widgetMaxRows)
    val maximumRows = if (maxResizeHeight > 0) (maxResizeHeight / rowHeightPx).coerceAtLeast(minimumRows)
        else widgetMaxRows
    val windowRows = (availablePx / rowHeightPx).coerceAtLeast(1)
    return saved.coerceIn(minimumRows, maximumRows.coerceAtMost(widgetMaxRows)).coerceAtMost(windowRows)
}

@Composable
internal fun WidgetPicker(board: WidgetBoard, close: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    EnyoSheet(stringResource(R.string.widget_add), close) {
        if (board.providers.isEmpty()) Text(stringResource(R.string.widget_picker_empty), style = LocalTokens.current.secondary)
        Column(Modifier.heightIn(max = LocalTokens.current.folderMaxHeight).verticalScroll(rememberScrollState())) {
            for (provider in board.providers) {
                val label = remember(provider) { runCatching { provider.loadLabel(pm) }.getOrNull() ?: provider.provider.packageName }
                TextButton(onClick = { close(); board.add(provider) }, modifier = Modifier.fillMaxWidth()) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
internal fun HostedWidget(board: WidgetBoard, widget: WidgetPlacement, info: AppWidgetProviderInfo?, height: Dp) {
    val tokens = LocalTokens.current
    if (info == null) {
        Box(Modifier.fillMaxWidth().height(height).padding(tokens.padding)) {
            Text(stringResource(R.string.widget_unavailable), style = tokens.secondary)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
        val dimensions = remember(maxWidth, maxHeight) { listOf(SizeF(maxWidth.value, maxHeight.value)) }
        AndroidView(
            factory = { context ->
                runCatching { board.makeView(context, widget, info) }.getOrElse {
                    android.appwidget.AppWidgetHostView(context)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                if (view.getTag(R.id.widget_content) != dimensions) {
                    view.setTag(R.id.widget_content, dimensions)
                    runCatching { view.updateAppWidgetSize(Bundle(), dimensions) }
                }
            }
        )
    }
}
