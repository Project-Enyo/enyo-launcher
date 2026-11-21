package space.koyu.enyo

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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

internal const val dockCapacity = 5

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppsGrid(
    apps: List<LauncherActivityInfo>,
    extra: (@Composable () -> Unit)? = null,
    onAtTopChanged: (Boolean) -> Unit,
    tile: @Composable (LauncherActivityInfo) -> Unit
) {
    val tokens = LocalTokens.current
    Box(
        Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val edge = (tokens.appEdgeFade.toPx() / size.height.coerceAtLeast(1f)).coerceIn(0f, 0.45f)
                val mask = Brush.verticalGradient(
                    0f to Color.Transparent,
                    edge to Color.Black,
                    (1f - edge) to Color.Black,
                    1f to Color.Transparent
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(mask, blendMode = BlendMode.DstIn)
                }
            }
    ) {
        GridContent(apps, extra, onAtTopChanged, tile)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridContent(
    apps: List<LauncherActivityInfo>,
    extra: (@Composable () -> Unit)?,
    onAtTopChanged: (Boolean) -> Unit,
    tile: @Composable (LauncherActivityInfo) -> Unit
) {
    val tokens = LocalTokens.current
    val reportTop by rememberUpdatedState(onAtTopChanged)
    if (apps.size > tokens.eagerAppLimit) {
        val cache = remember { LazyLayoutCacheWindow(aheadFraction = 0.5f, behindFraction = 0.5f) }
        val state = rememberLazyGridState(cacheWindow = cache)
        LaunchedEffect(state) { snapshotFlow { !state.canScrollBackward }.collect { reportTop(it) } }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(tokens.appCellWidth),
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(tokens.margin),
            horizontalArrangement = Arrangement.spacedBy(tokens.gap),
            verticalArrangement = Arrangement.spacedBy(tokens.gap)
        ) {
            if (extra != null) item(key = "feature") { extra() }
            items(apps, key = { it.componentName.flattenToString() }, contentType = { "app" }) { tile(it) }
        }
    } else BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = tokens.margin)) {
        val count = ((maxWidth + tokens.gap) / (tokens.appCellWidth + tokens.gap)).toInt().coerceAtLeast(1)
        val cells = remember(apps, count, extra != null) {
            buildList<LauncherActivityInfo?> {
                if (extra != null) add(null)
                addAll(apps)
            }.chunked(count)
        }
        val state = rememberScrollState()
        LaunchedEffect(state) { snapshotFlow { !state.canScrollBackward }.collect { reportTop(it) } }
        Column(
            Modifier.fillMaxSize().verticalScroll(state).padding(vertical = tokens.margin),
            verticalArrangement = Arrangement.spacedBy(tokens.gap)
        ) {
            cells.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    row.forEach { app ->
                        key(app?.componentName) {
                            Box(Modifier.weight(1f)) {
                                if (app == null) extra?.invoke() else tile(app)
                            }
                        }
                    }
                    repeat(count - row.size) { Spacer(Modifier.weight(1f)) }
                }
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
    actions: (LauncherActivityInfo) -> Unit
) {
    val tokens = LocalTokens.current
    if (apps.isEmpty()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = browse) { Text(stringResource(R.string.dock_empty), style = tokens.secondary) }
        }
        return
    }
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = tokens.margin)
            .padding(top = tokens.gap, bottom = tokens.dockBottomSpace),
        contentAlignment = Alignment.Center
    ) {
        val available = maxWidth - tokens.dockInnerHorizontal * 2
        val cell = minOf(tokens.dockCellWidth, available / apps.size).coerceAtLeast(tokens.touch)
        val scroll = available < cell * apps.size
        Surface(
            color = tokens.surface.copy(alpha = tokens.dockOpacity),
            border = BorderStroke(tokens.glyphStroke / 2, tokens.quiet.copy(alpha = tokens.hoverOpacity)),
            shadowElevation = tokens.elevation.last(),
            shape = RoundedCornerShape(tokens.sheetRadius)
        ) {
            Row(
                Modifier.then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = tokens.dockInnerHorizontal, vertical = tokens.dockInnerVertical)
            ) {
                apps.forEach { app ->
                    val label = app.label.toString()
                    Box(
                        Modifier.width(cell).heightIn(min = tokens.touch)
                            .appPress({ open(app) }, { actions(app) })
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center
                    ) { AppIcon(app, displaySize = minOf(tokens.dockIconSize, cell)) }
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
