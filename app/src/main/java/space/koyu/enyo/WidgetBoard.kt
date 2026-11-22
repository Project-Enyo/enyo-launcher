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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

internal enum class TodayWidget(val title: Int, val provider: Class<out TodayProvider>, val startsWide: Boolean = false, val startRows: Int = 1) {
    Clock(R.string.widget_clock, ClockWidget::class.java, true),
    Calendar(R.string.widget_calendar, CalendarWidget::class.java, true),
    Weather(R.string.widget_weather, WeatherAppWidget::class.java),
    Battery(R.string.widget_battery, BatteryWidget::class.java),
    Network(R.string.wireless, InternetWidget::class.java),
    News(R.string.widget_news, NewsWidget::class.java, true, 3);

    fun component(context: Context) = ComponentName(context, provider)
}

/**
 * A widget on the Today cell grid: its cell position and its span in cells. A negative x marks a
 * widget that still waits for a free spot, and legacy marks spans saved by the old two column board.
 */
internal data class WidgetPlacement(
    val id: Int,
    val kind: TodayWidget?,
    val columns: Int,
    val rows: Int,
    val x: Int = -1,
    val y: Int = -1,
    val legacy: Boolean = false
) {
    val placed get() = x >= 0 && y >= 0
    fun overlaps(other: WidgetPlacement) =
        x < other.x + other.columns && other.x < x + columns && y < other.y + other.rows && other.y < y + rows
}

/** The measured Today grid: cell counts and cell size in pixels. */
internal data class WidgetGridSpec(val columns: Int, val rows: Int, val cellWidth: Int, val cellHeight: Int, val visibleRows: Int = rows)

/** First free spot for a span, scanning rows top to bottom like a home screen. */
internal fun List<WidgetPlacement>.firstFit(columns: Int, rows: Int, grid: WidgetGridSpec): Pair<Int, Int>? {
    for (y in 0..grid.rows - rows) for (x in 0..grid.columns - columns) {
        val probe = WidgetPlacement(-1, null, columns, rows, x, y)
        if (none { it.placed && it.overlaps(probe) }) return x to y
    }
    return null
}

/**
 * Moves one widget to [target] and pushes every widget it now overlaps to the nearest free spot,
 * the way the Launcher3 workspace reorders. Returns null when the arrangement does not fit.
 */
internal fun List<WidgetPlacement>.reflow(target: WidgetPlacement, grid: WidgetGridSpec): List<WidgetPlacement>? {
    if (target.x < 0 || target.y < 0 || target.x + target.columns > grid.columns || target.y + target.rows > grid.rows) return null
    val others = filter { it.id != target.id && it.placed }
    val settled = mutableListOf(target)
    settled += others.filterNot { it.overlaps(target) }
    val displaced = others.filter { it.overlaps(target) }
        .sortedBy { kotlin.math.abs(it.x - target.x) + kotlin.math.abs(it.y - target.y) }
    for (item in displaced) {
        var best: WidgetPlacement? = null
        var bestDistance = Int.MAX_VALUE
        for (y in 0..grid.rows - item.rows) for (x in 0..grid.columns - item.columns) {
            val probe = item.copy(x = x, y = y)
            if (settled.any { it.overlaps(probe) }) continue
            val distance = kotlin.math.abs(x - item.x) + kotlin.math.abs(y - item.y) * 2
            if (distance < bestDistance) { best = probe; bestDistance = distance }
        }
        settled += best ?: return null
    }
    return map { current -> settled.firstOrNull { it.id == current.id } ?: current }
}

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
    var grid by mutableStateOf<WidgetGridSpec?>(null)
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
        host.createView(WidgetLaunchContext(context), widget.id, info).apply {
            // AppWidgetHostView adds Android's default widget padding; the Today grid already spaces its cells.
            setPadding(0, 0, 0, 0)
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
        val (columns, rows) = spanFor(provider, kind)
        val widget = WidgetPlacement(id, kind, columns, rows)
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

    /** Cell span for a new widget, from its target cells or its minimum size in the measured grid. */
    private fun spanFor(provider: AppWidgetProviderInfo, kind: TodayWidget?): Pair<Int, Int> {
        val spec = grid ?: return 2 to 1
        if (kind != null) return (if (kind.startsWide) spec.columns else (spec.columns / 2).coerceAtLeast(1)) to kind.startRows.coerceAtMost(spec.visibleRows)
        val columns = if (provider.targetCellWidth > 0) {
            kotlin.math.round(provider.targetCellWidth * spec.columns / launcherReferenceColumns.toFloat()).toInt()
        } else ceil(provider.minWidth.toFloat() / spec.cellWidth).toInt()
        val rows = if (provider.targetCellHeight > 0) provider.targetCellHeight
            else ceil(provider.minHeight.toFloat() / spec.cellHeight).toInt()
        return columns.coerceIn(1, spec.columns) to rows.coerceIn(1, spec.visibleRows)
    }

    /** The span a provider would take, for the picker. */
    fun previewSpan(provider: AppWidgetProviderInfo): Pair<Int, Int>? = grid?.let { spanFor(provider, null) }

    /** Smallest and largest spans a widget accepts in the measured grid. */
    fun spanLimits(info: AppWidgetProviderInfo?, spec: WidgetGridSpec): SpanLimits {
        if (info == null) return SpanLimits(1, spec.columns, 1, spec.rows, horizontal = false, vertical = false)
        val horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        val minWidth = if (horizontal && info.minResizeWidth > 0) info.minResizeWidth else info.minWidth
        val minHeight = if (vertical && info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
        val minColumns = ceil(minWidth.toFloat() / spec.cellWidth).toInt().coerceIn(1, spec.columns)
        val minRows = ceil(minHeight.toFloat() / spec.cellHeight).toInt().coerceIn(1, spec.rows)
        val maxColumns = if (info.maxResizeWidth > 0) (info.maxResizeWidth / spec.cellWidth).coerceIn(minColumns, spec.columns) else spec.columns
        // No widget grows taller than one screen of the scrolling grid.
        val tallest = spec.visibleRows.coerceAtLeast(minRows)
        val maxRows = if (info.maxResizeHeight > 0) (info.maxResizeHeight / spec.cellHeight).coerceIn(minRows, tallest) else tallest
        return SpanLimits(minColumns, maxColumns, minRows, maxRows, horizontal, vertical)
    }

    /**
     * Records the measured grid and gives every waiting widget a spot. Spans saved by the old two
     * column board convert to half or full width; Enyo's own widgets keep their saved width choice.
     */
    fun arrange(spec: WidgetGridSpec, wideKinds: Set<String>) {
        grid = spec
        if (placements.all { it.placed && it.x + it.columns <= spec.columns && it.y + it.rows <= spec.rows }) return
        val half = (spec.columns / 2).coerceAtLeast(1)
        var next = placements.filter { it.placed && it.x + it.columns <= spec.columns && it.y + it.rows <= spec.rows }
        val waiting = placements.filterNot { candidate -> next.any { it.id == candidate.id } }
        for (item in waiting) {
            val columns = when {
                item.legacy && item.kind != null -> if (item.kind.name in wideKinds) spec.columns else half
                item.legacy -> if (item.columns >= 2) spec.columns else half
                else -> item.columns.coerceIn(1, spec.columns)
            }
            val rows = item.rows.coerceIn(1, spec.rows)
            // A widget that no longer fits at its size shrinks to one cell rather than disappearing.
            val (spot, fitted) = next.firstFit(columns, rows, spec)?.let { it to item.copy(columns = columns, rows = rows) }
                ?: next.firstFit(1, 1, spec)?.let { it to item.copy(columns = 1, rows = 1) }
                ?: continue
            next = next + fitted.copy(x = spot.first, y = spot.second, legacy = false)
        }
        save(next + placements.filterNot { candidate -> next.any { it.id == candidate.id } })
    }

    /** Saves a whole arrangement after a move or resize. */
    fun commit(next: List<WidgetPlacement>) = save(next)

    /**
     * Applies a backed up arrangement to Enyo's own widgets by kind. Third party widgets need a new
     * binding on each device, so they keep their place. Overlaps wait for the next free spot.
     */
    fun restoreKindLayout(raw: String) {
        val saved = decode(raw).filter { it.kind != null && it.placed }.associateBy { it.kind }
        var next = placements.map { current ->
            saved[current.kind]?.let { current.copy(x = it.x, y = it.y, columns = it.columns, rows = it.rows, legacy = false) } ?: current
        }
        next = next.map { item -> if (next.any { it.id != item.id && it.placed && item.placed && it.overlaps(item) && it.kind == null }) item.copy(x = -1, y = -1) else item }
        save(next)
        grid?.let { arrange(it, emptySet()) }
    }

    private fun complete(requested: WidgetPlacement) {
        val spec = grid
        // A widget that does not fit at its preferred size takes the largest free size it accepts.
        val widget = if (spec == null) requested else {
            val limits = spanLimits(info(requested.id), spec)
            val sizes = (requested.rows downTo minOf(limits.minRows, requested.rows)).flatMap { rows ->
                (requested.columns downTo minOf(limits.minColumns, requested.columns)).map { columns -> requested.copy(columns = columns, rows = rows) }
            }.sortedByDescending { it.columns * it.rows }
            sizes.firstOrNull { placements.firstFit(it.columns, it.rows, spec) != null } ?: requested
        }
        val spot = spec?.let { placements.firstFit(widget.columns, widget.rows, it) }
        if (spec != null && spot == null) {
            discard(widget)
            val kind = widget.kind
            if (kind != null) {
                // An Enyo widget added by default waits in the picker instead of asking again on every visit.
                migrated = migrated + kind
                store.edit().putStringSet("migrated", migrated.mapTo(mutableSetOf()) { it.name }).apply()
            } else Toast.makeText(activity, R.string.widget_no_room, Toast.LENGTH_SHORT).show()
            return
        }
        val placed = if (spot != null) widget.copy(x = spot.first, y = spot.second) else widget
        val next = placements + placed
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
                .put("columns", widget.columns).put("rows", widget.rows)
                .put("x", widget.x).put("y", widget.y).put("legacy", widget.legacy))
        }
    }.toString()

    private fun decode(raw: String?) = runCatching {
        val saved = JSONArray(raw ?: "[]")
        List(saved.length()) { index ->
            val item = saved.getJSONObject(index)
            val legacy = !item.has("x") || item.optBoolean("legacy")
            WidgetPlacement(item.getInt("id"), TodayWidget.entries.firstOrNull { it.name == item.optString("kind") },
                item.optInt("columns", 1).coerceAtLeast(1), item.optInt("rows", 1).coerceAtLeast(1),
                if (legacy) -1 else item.optInt("x", -1), if (legacy) -1 else item.optInt("y", -1), legacy)
        }
    }.getOrDefault(emptyList())
}

internal const val widgetConfigureRequest = 225
/** Launcher3 target cell sizes refer to its default four column workspace. */
private const val launcherReferenceColumns = 4

internal data class SpanLimits(
    val minColumns: Int, val maxColumns: Int, val minRows: Int, val maxRows: Int,
    val horizontal: Boolean, val vertical: Boolean
) {
    val resizable get() = horizontal || vertical
}
internal val LocalWidgetBoard = staticCompositionLocalOf<WidgetBoard> { error("Widget board unavailable") }

@Composable
internal fun WidgetPicker(board: WidgetBoard, close: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val pm = context.packageManager
    EnyoSheet(stringResource(R.string.widget_add), close) {
        if (board.providers.isEmpty()) Text(stringResource(R.string.widget_picker_empty), style = tokens.secondary)
        LazyColumn(Modifier.heightIn(max = tokens.widgetPickerHeight), verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
            items(board.providers, key = { it.provider.flattenToString() }) { provider ->
                val label = remember(provider) { runCatching { provider.loadLabel(pm) }.getOrNull() ?: provider.provider.packageName }
                val app = remember(provider) { sourceLabel(context, provider.provider.packageName) }
                val span = remember(provider, board.grid) { board.previewSpan(provider) }
                val preview by produceState<ImageBitmap?>(null, provider) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            val density = context.resources.displayMetrics.densityDpi
                            (provider.loadPreviewImage(context, density) ?: provider.loadIcon(context, density))?.toBitmap()?.asImageBitmap()
                        }.getOrNull()
                    }
                }
                ListCard {
                    ListRow(
                        label,
                        Modifier.appPress({ close(); board.add(provider) }),
                        summary = listOfNotNull(app.takeIf { it != label }, span?.let { stringResource(R.string.widget_size, it.first, it.second) })
                            .joinToString(" · "),
                        summaryLines = 1,
                        leading = {
                            Box(
                                Modifier.size(tokens.widgetPreview).clip(RoundedCornerShape(tokens.iconRadius)).background(tokens.widgetTrack),
                                contentAlignment = Alignment.Center
                            ) {
                                preview?.let { Image(it, null, Modifier.fillMaxSize().padding(tokens.gap / 2), contentScale = ContentScale.Fit) }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun HostedWidget(board: WidgetBoard, widget: WidgetPlacement, info: AppWidgetProviderInfo?, modifier: Modifier) {
    val tokens = LocalTokens.current
    if (info == null) {
        Box(modifier.padding(tokens.padding)) {
            Text(stringResource(R.string.widget_unavailable), style = tokens.secondary)
        }
        return
    }
    BoxWithConstraints(modifier) {
        val dimensions = remember(maxWidth, maxHeight) { listOf(SizeF(maxWidth.value, maxHeight.value)) }
        AndroidView(
            factory = { context ->
                runCatching { board.makeView(context, widget, info) }.getOrElse {
                    android.appwidget.AppWidgetHostView(context)
                }
            },
            // Every widget shares the grid's corner radius, whatever shape its own background has.
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(tokens.radius)),
            update = { view ->
                if (view.paddingLeft != 0 || view.paddingTop != 0 || view.paddingRight != 0 || view.paddingBottom != 0) view.setPadding(0, 0, 0, 0)
                if (view.getTag(R.id.widget_content) != dimensions) {
                    view.setTag(R.id.widget_content, dimensions)
                    runCatching { view.updateAppWidgetSize(Bundle(), dimensions) }
                }
            }
        )
    }
}

/**
 * Widgets start their PendingIntents through their view's context. This context notes which app
 * the intent belongs to, so an app a widget opens gets a card in Cards.
 */
private class WidgetLaunchContext(base: Context) : android.content.ContextWrapper(base) {
    override fun startIntentSender(
        intent: android.content.IntentSender, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int
    ) {
        RecentStore.get(this).expectLaunch(intent.creatorPackage)
        super.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags)
    }

    override fun startIntentSender(
        intent: android.content.IntentSender, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?
    ) {
        RecentStore.get(this).expectLaunch(intent.creatorPackage)
        super.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, options)
    }
}
