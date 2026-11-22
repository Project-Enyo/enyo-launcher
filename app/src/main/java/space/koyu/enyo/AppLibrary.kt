package space.koyu.enyo

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map

internal val dockApps = stringPreferencesKey("dock_apps")

internal fun Context.pins() = appearancePreferences.data.map {
    it[dockApps].orEmpty().lineSequence().mapNotNull(ComponentName::unflattenFromString).distinct().take(dockCapacity).toList()
}

internal suspend fun Context.changePin(component: ComponentName, pin: Boolean, installed: Set<ComponentName>) {
    appearancePreferences.edit { prefs ->
        val saved = prefs[dockApps].orEmpty().lineSequence().mapNotNull(ComponentName::unflattenFromString)
            .filter { it in installed }.distinct().map { it.flattenToString() }.toList()
        val name = component.flattenToString()
        val updated = if (pin) (saved + name).distinct().take(dockCapacity) else saved - name
        prefs[dockApps] = updated.joinToString("\n")
    }
}

internal suspend fun Context.movePin(component: ComponentName, anchor: ComponentName, after: Boolean) {
    appearancePreferences.edit { prefs ->
        val saved = prefs[dockApps].orEmpty().lineSequence().mapNotNull(ComponentName::unflattenFromString)
            .distinct().take(dockCapacity).toMutableList()
        if (component !in saved || anchor !in saved || component == anchor) return@edit
        saved.remove(component)
        saved.add(saved.indexOf(anchor) + if (after) 1 else 0, component)
        prefs[dockApps] = saved.joinToString("\n") { it.flattenToString() }
    }
}

internal const val dockCapacity = 5
private const val preparedTileLimit = 80

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppsGrid(
    apps: List<LauncherActivityInfo>,
    extraIndex: Int,
    extra: @Composable () -> Unit,
    onAtTopChanged: (Boolean) -> Unit,
    tile: @Composable (LauncherActivityInfo) -> Unit
) {
    val tokens = LocalTokens.current
    val reportTop by rememberUpdatedState(onAtTopChanged)
    val fade = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val edge = (tokens.appEdgeFade.toPx() / size.height.coerceAtLeast(1f)).coerceIn(0f, 0.45f)
            val mask = Brush.verticalGradient(
                0f to Color.Transparent, edge to Color.Black,
                (1f - edge) to Color.Black, 1f to Color.Transparent
            )
            onDrawWithContent {
                drawContent()
                drawRect(mask, blendMode = BlendMode.DstIn)
            }
        }
    BoxWithConstraints(Modifier.fillMaxSize().then(fade)) {
        val width = maxWidth - tokens.margin * 2
        val columns = ((width + tokens.gap) / (tokens.appCellWidth + tokens.gap)).toInt().coerceAtLeast(1)
        val entries = apps.size + 1
        val rows = (entries + columns - 1) / columns
        val collection = Modifier.fillMaxSize().semantics { collectionInfo = CollectionInfo(rows, columns) }
        val rowContent: @Composable (Int) -> Unit = { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                repeat(columns) { column ->
                    val index = row * columns + column
                    val cell = if (index < entries) Modifier.semantics {
                        collectionItemInfo = CollectionItemInfo(row, 1, column, 1)
                    } else Modifier
                    Box(Modifier.weight(1f).then(cell)) {
                        if (index == extraIndex) extra()
                        else apps.getOrNull(if (index < extraIndex) index else index - 1)?.let { app ->
                            key(app.componentName) { tile(app) }
                        }
                    }
                }
            }
        }
        if (entries <= preparedTileLimit) {
            val state = rememberScrollState()
            LaunchedEffect(state) { snapshotFlow { !state.canScrollBackward }.collect { reportTop(it) } }
            Column(
                collection.verticalScroll(state).padding(tokens.margin),
                verticalArrangement = Arrangement.spacedBy(tokens.gap)
            ) {
                repeat(rows) { rowContent(it) }
            }
        } else {
            val cache = remember { LazyLayoutCacheWindow(aheadFraction = 0.50f, behindFraction = 0.30f) }
            val state = rememberLazyListState(cacheWindow = cache)
            LaunchedEffect(state) { snapshotFlow { !state.canScrollBackward }.collect { reportTop(it) } }
            LazyColumn(
                modifier = collection,
                state = state,
                contentPadding = PaddingValues(tokens.margin),
                verticalArrangement = Arrangement.spacedBy(tokens.gap)
            ) {
                items(rows, contentType = { "appRow" }) { rowContent(it) }
            }
        }
    }
}

@Composable
internal fun AppTile(
    label: String,
    open: () -> Unit,
    actions: (() -> Unit)? = null,
    icon: @Composable () -> Unit
) {
    val tokens = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().heightIn(min = tokens.appCellHeight)
            .appPress(open, actions)
            .padding(vertical = tokens.gap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(tokens.gap)
    ) {
        icon()
        Text(label, style = tokens.secondary.copy(color = tokens.ink), textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun AppFolder(
    title: String,
    items: List<LauncherActivityInfo>,
    close: () -> Unit,
    open: (LauncherActivityInfo) -> Unit,
    actions: (LauncherActivityInfo) -> Unit,
    footer: (@Composable () -> Unit)? = null
) {
    val tokens = LocalTokens.current
    EnyoSheet(title, close) {
        Column(Modifier.fillMaxWidth().heightIn(max = tokens.folderMaxHeight).verticalScroll(rememberScrollState())) {
            items.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    for (app in row) Box(Modifier.weight(1f)) {
                        AppTile(app.label.toString(), { open(app) }, { actions(app) }) { AppIcon(app) }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        footer?.invoke()
    }
}

@Composable
internal fun PinnedDock(
    apps: List<LauncherActivityInfo>,
    browse: () -> Unit,
    open: (LauncherActivityInfo) -> Unit,
    actions: (LauncherActivityInfo) -> Unit,
    labels: Map<ComponentName, String>,
    move: (ComponentName, ComponentName, Boolean) -> Unit,
    remove: (ComponentName) -> Unit
) {
    val tokens = LocalTokens.current
    val components = remember(apps) { apps.map { it.componentName } }
    val movePin by rememberUpdatedState(move)
    val removePin by rememberUpdatedState(remove)
    var dragged by remember(components) { mutableStateOf<ComponentName?>(null) }
    var offset by remember(components) { mutableStateOf(Offset.Zero) }
    val earlier = stringResource(R.string.dock_move_earlier)
    val later = stringResource(R.string.dock_move_later)
    val unpin = stringResource(R.string.dock_unpin)
    val appActions = stringResource(R.string.dock_app_actions)
    val haptics = LocalHapticFeedback.current
    if (apps.isEmpty()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = browse) { Text(stringResource(R.string.dock_empty), style = tokens.secondary) }
        }
        return
    }
    val platformGestures = LocalViewConfiguration.current
    val pickupGestures = remember(platformGestures, tokens.dockHoldMillis) {
        object : ViewConfiguration by platformGestures {
            override val longPressTimeoutMillis = maxOf(tokens.dockHoldMillis, platformGestures.longPressTimeoutMillis)
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides pickupGestures) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = tokens.margin)
                .padding(top = tokens.gap, bottom = tokens.dockBottomSpace),
            contentAlignment = Alignment.Center
        ) {
            val iconSize = tokens.dockIconSize
            val rowHeight = maxOf(tokens.touch, iconSize)
            val padding = tokens.dockInnerPadding
            val singleTileWidth = rowHeight + padding * 2
            val cell = if (apps.size == 1) rowHeight else {
                // The end cells contribute half their spare width to the dock inset.
                val fit = (maxWidth - singleTileWidth) / (apps.size - 1)
                minOf(tokens.dockCellWidth, singleTileWidth, fit).coerceAtLeast(rowHeight)
            }
            val horizontalPadding = padding - (cell - rowHeight) / 2
            val scroll = maxWidth < cell * apps.size + horizontalPadding * 2
            val iconInset = padding + (rowHeight - iconSize) / 2
            val dockRadius = iconSize * tokens.iconRasterCorner + iconInset
            val density = LocalDensity.current
            val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
            val step = with(density) { cell.roundToPx().toFloat() }
            val sidePad = with(density) { horizontalPadding.toPx() }
            val halfHeight = with(density) { (rowHeight / 2 + padding).toPx() }
            fun destination(index: Int) = (index + (offset.x * direction / step).roundToInt()).coerceIn(components.indices)
            fun outside(index: Int): Boolean {
                val center = (index + 0.5f) * step + offset.x * direction
                return kotlin.math.abs(offset.y) > halfHeight ||
                    center < -sidePad || center > step * components.size + sidePad
            }
            Box {
                Surface(
                    modifier = Modifier.matchParentSize(),
                    color = tokens.dockSurface.copy(alpha = tokens.dockOpacity),
                    border = BorderStroke(tokens.glyphStroke / 2, tokens.onAccentContainer.copy(alpha = tokens.dockBorderOpacity)),
                    shadowElevation = tokens.elevation.last(),
                    shape = RoundedCornerShape(dockRadius)
                ) {}
                Row(
                    Modifier.then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                        .padding(horizontal = horizontalPadding, vertical = padding)
                ) {
                    apps.forEachIndexed { index, app ->
                        val component = app.componentName
                        key(component) {
                            val label = labels[component].orEmpty()
                            Box(
                                Modifier.width(cell).height(rowHeight)
                                    .zIndex(if (dragged == component) 1f else 0f)
                                    .graphicsLayer {
                                        val from = components.indexOf(dragged)
                                        val to = if (from >= 0) destination(from) else from
                                        translationX = when {
                                            from == index -> offset.x
                                            from < index && index <= to -> -step * direction
                                            to <= index && index < from -> step * direction
                                            else -> 0f
                                        }
                                        translationY = if (from == index) offset.y else 0f
                                        alpha = if (from == index && outside(index)) 0.65f else 1f
                                    }
                                    .appPress({ if (dragged != component) open(app) })
                                    .pointerInput(components, index, step, direction, halfHeight, sidePad, haptics) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                dragged = component
                                                offset = Offset.Zero
                                            },
                                            onDrag = { change, delta ->
                                                change.consume()
                                                offset += delta
                                            },
                                            onDragEnd = {
                                                val target = destination(index)
                                                if (outside(index)) removePin(component)
                                                else if (target != index) movePin(component, components[target], target > index)
                                                dragged = null
                                                offset = Offset.Zero
                                            },
                                            onDragCancel = {
                                                dragged = null
                                                offset = Offset.Zero
                                            }
                                        )
                                    }
                                    .semantics {
                                        contentDescription = label
                                        customActions = buildList {
                                            add(CustomAccessibilityAction(appActions) { actions(app); true })
                                            if (index > 0) add(CustomAccessibilityAction(earlier) {
                                                movePin(component, components[index - 1], false)
                                                true
                                            })
                                            if (index < components.lastIndex) add(CustomAccessibilityAction(later) {
                                                movePin(component, components[index + 1], true)
                                                true
                                            })
                                            add(CustomAccessibilityAction(unpin) { removePin(component); true })
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) { AppIcon(app, displaySize = iconSize, label = label) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Modifier.appPress(open: () -> Unit, actions: (() -> Unit)? = null): Modifier {
    val tokens = LocalTokens.current
    val input = LocalInputModeManager.current
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }
        .then(
            if (focused && input.inputMode == InputMode.Keyboard) {
                Modifier.border(BorderStroke(tokens.focusStroke, tokens.accent), RoundedCornerShape(tokens.iconRadius))
            } else Modifier
        )
        .combinedClickable(
            interactionSource = null,
            indication = null,
            role = Role.Button,
            onClick = open,
            onLongClick = actions
        )
}
