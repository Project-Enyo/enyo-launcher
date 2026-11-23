package space.koyu.enyo

import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

private enum class Edge { Left, Top, Right, Bottom }

private data class WidgetDrag(val id: Int, val grab: Offset, val pointer: Offset, val preview: List<WidgetPlacement>?)

/**
 * The Today workspace: widgets on a cell grid with Launcher3 mechanics. Touch and hold lifts a
 * widget, dragging snaps it to cells while other widgets make room, and dropping it on Remove
 * deletes it. After a drop, a resizable widget shows a frame whose handles snap its span to cells.
 * Touch and hold on empty space opens the widget menu. The grid grows downward with its widgets
 * and scrolls, always keeping about a screen of free rows below the last widget.
 */
@Composable
internal fun WidgetGrid(
    board: WidgetBoard,
    hidden: (WidgetPlacement) -> Boolean,
    wideKinds: Set<String>,
    openPicker: () -> Unit,
    convertDefaults: (() -> Unit)?
) {
    val tokens = LocalTokens.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scroll = rememberScrollState()
    val inset = with(density) { (tokens.gap / 2).roundToPx() }
    val minimumCell = with(density) { tokens.todayCellMinimum.roundToPx() }
    // Cells keep the size of a one screen grid; the row count follows the lowest widget.
    val lowest = board.placements.filter { it.placed }.maxOfOrNull { it.y + it.rows } ?: 0
    val spec = remember(size, minimumCell, lowest) {
        if (size.width <= 0 || size.height <= 0) null else {
            val columns = (size.width / minimumCell).coerceIn(tokens.todayColumnsMinimum, tokens.todayColumnsMaximum)
            val cellWidth = size.width / columns
            val screenRows = (size.height / cellWidth.coerceAtLeast(1)).coerceAtLeast(1)
            WidgetGridSpec(columns, lowest + screenRows, cellWidth, size.height / screenRows, screenRows)
        }
    }
    LaunchedEffect(spec, board.placements, wideKinds) { spec?.let { board.arrange(it, wideKinds) } }
    var drag by remember { mutableStateOf<WidgetDrag?>(null) }
    var resizing by remember { mutableStateOf<Int?>(null) }
    var resizePreview by remember { mutableStateOf<List<WidgetPlacement>?>(null) }
    var menu by remember { mutableStateOf<Offset?>(null) }
    val removeZone = with(density) { tokens.todayRemoveZone.toPx() }
    val handleReach = with(density) { tokens.todayHandleReach.toPx() }
    val actionHeight = with(density) { (tokens.pillHeight + tokens.gap).toPx() }
    val committed = board.placements
    val current by rememberUpdatedState(committed)
    val resizingId = resizing?.takeIf { id -> committed.any { it.id == id } }

    fun origin(item: WidgetPlacement, grid: WidgetGridSpec) = Offset((item.x * grid.cellWidth).toFloat(), (item.y * grid.cellHeight).toFloat())
    fun bounds(item: WidgetPlacement, grid: WidgetGridSpec) = Rect(
        origin(item, grid), androidx.compose.ui.geometry.Size((item.columns * grid.cellWidth).toFloat(), (item.rows * grid.cellHeight).toFloat())
    )
    fun actionBar(frame: Rect): Rect {
        val top = if (frame.top - actionHeight >= 0f) frame.top - actionHeight else frame.bottom
        return Rect(frame.left, top, frame.right, top + actionHeight)
    }

    // While a widget is dragged near the top or bottom edge, the page scrolls under it.
    val edgeZone = with(density) { tokens.recentEdgeScrollZone.toPx() }
    val edgeSpeed = with(density) { tokens.recentEdgeScrollSpeed.toPx() }
    LaunchedEffect(drag != null) {
        if (drag == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val moving = drag ?: break
            val onScreen = moving.pointer.y - scroll.value
            val viewport = size.height.toFloat()
            val up = ((edgeZone - onScreen) / edgeZone).coerceIn(0f, 1f).takeIf { onScreen > removeZone } ?: 0f
            val down = ((onScreen - (viewport - edgeZone)) / edgeZone).coerceIn(0f, 1f)
            val seconds = (now - previous).coerceAtMost(50_000_000L) / 1_000_000_000f
            val moved = scroll.scrollBy((down * down - up * up) * edgeSpeed * seconds)
            // The finger stays put on screen, so it now points at a different spot of the grid.
            if (moved != 0f) drag = drag?.copy(pointer = moving.pointer + Offset(0f, moved))
            previous = now
        }
    }

    Box(
        Modifier.fillMaxSize().padding(horizontal = tokens.margin - tokens.gap / 2)
            .onSizeChanged { size = it }
    ) {
    val contentRows = spec?.let { grid ->
        val shown = (drag?.preview ?: resizePreview ?: committed).filter { it.placed && !hidden(it) }
        maxOf(grid.visibleRows, (shown.maxOfOrNull { it.y + it.rows } ?: 0) + 1)
    } ?: 0
    // Once the widgets reach past one screen, the edges fade like the app grid.
    val overflowing = spec != null &&
        (committed.filter { it.placed && !hidden(it) }.maxOfOrNull { it.y + it.rows } ?: 0) > spec.visibleRows
    Box(
        Modifier.fillMaxWidth()
            .edgeFade(top = { overflowing && scroll.value > 0 }, bottom = { overflowing && scroll.canScrollForward })
            .verticalScroll(scroll, enabled = drag == null && resizing == null)
            .height(with(density) { (contentRows * (spec?.cellHeight ?: 0)).toDp() }.coerceAtLeast(with(density) { size.height.toDp() }))
            .pointerInput(spec, handleReach, removeZone) {
                val grid = spec ?: return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val start = down.position
                    val active = resizing?.let { id -> current.firstOrNull { it.id == id } }
                    if (active != null) {
                        val frame = bounds(active, grid)
                        // The action row above the frame handles its own taps.
                        if (actionBar(frame).contains(start)) return@awaitEachGesture
                        val limits = board.spanLimits(board.info(active.id), grid)
                        val handles = buildList {
                            if (limits.horizontal) { add(Edge.Left to Offset(frame.left, frame.center.y)); add(Edge.Right to Offset(frame.right, frame.center.y)) }
                            if (limits.vertical) { add(Edge.Top to Offset(frame.center.x, frame.top)); add(Edge.Bottom to Offset(frame.center.x, frame.bottom)) }
                        }
                        val edge = handles.minByOrNull { (it.second - start).getDistance() }
                            ?.takeIf { (it.second - start).getDistance() <= handleReach }?.first
                        down.consume()
                        if (edge == null) {
                            // A tap anywhere else ends resizing, as on a Launcher3 workspace.
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                            resizing = null
                            resizePreview = null
                            return@awaitEachGesture
                        }
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            val delta = change.position - start
                            var x = active.x; var y = active.y; var w = active.columns; var h = active.rows
                            when (edge) {
                                Edge.Right -> w = ((frame.right + delta.x) / grid.cellWidth).roundToInt() - x
                                Edge.Bottom -> h = ((frame.bottom + delta.y) / grid.cellHeight).roundToInt() - y
                                Edge.Left -> { val left = ((frame.left + delta.x) / grid.cellWidth).roundToInt().coerceAtLeast(0); w = x + w - left; x = left }
                                Edge.Top -> { val top = ((frame.top + delta.y) / grid.cellHeight).roundToInt().coerceAtLeast(0); h = y + h - top; y = top }
                            }
                            val columns = w.coerceIn(limits.minColumns, limits.maxColumns)
                            val rows = h.coerceIn(limits.minRows, limits.maxRows)
                            if (edge == Edge.Left) x = active.x + active.columns - columns
                            if (edge == Edge.Top) y = active.y + active.rows - rows
                            val target = active.copy(x = x, y = y, columns = columns, rows = rows)
                            current.reflow(target, grid)?.let { resizePreview = it }
                        }
                        resizePreview?.let { if (it != current) board.commit(it) }
                        resizePreview = null
                        return@awaitEachGesture
                    }

                    // Wait for a still hold; any earlier movement or release belongs to the widget or the pager.
                    val held = withTimeoutOrNull<Boolean>(viewConfiguration.longPressTimeoutMillis) {
                        var travel = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                            if (!change.pressed || change.isConsumed) return@withTimeoutOrNull false
                            travel += change.position - change.previousPosition
                            if (travel.getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                        }
                        @Suppress("UNREACHABLE_CODE")
                        false
                    } ?: true
                    if (!held) return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val item = current.filter { it.placed && !hidden(it) }.firstOrNull { bounds(it, grid).contains(start) }
                    if (item == null) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                        menu = start
                        return@awaitEachGesture
                    }
                    // Consuming from here on cancels the widget's own touch handling.
                    drag = WidgetDrag(item.id, start - origin(item, grid), start, null)
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        event.changes.forEach { it.consume() }
                        if (change == null || !change.pressed) break
                        val pointer = change.position
                        if ((pointer - start).getDistance() > viewConfiguration.touchSlop) moved = true
                        val topLeft = pointer - (drag?.grab ?: Offset.Zero)
                        val cellX = (topLeft.x / grid.cellWidth).roundToInt()
                        val cellY = (topLeft.y / grid.cellHeight).roundToInt()
                        // Over Remove there is no landing spot; elsewhere the last arrangement that fit stays shown.
                        val preview = if (pointer.y - scroll.value < removeZone) null
                            else current.reflow(item.copy(x = cellX, y = cellY), grid) ?: drag?.preview
                        drag = drag?.copy(pointer = pointer, preview = preview)
                    }
                    val finished = drag
                    drag = null
                    when {
                        finished != null && finished.pointer.y - scroll.value < removeZone && moved -> board.remove(item)
                        else -> {
                            finished?.preview?.let { if (it != current) board.commit(it) }
                            if (board.spanLimits(board.info(item.id), grid).resizable) resizing = item.id
                        }
                    }
                }
            }
    ) {
        val grid = spec ?: return@Box
        val layout = drag?.preview ?: resizePreview ?: committed
        val visible = layout.filter { it.placed && !hidden(it) }
        if (visible.isEmpty() && drag == null) {
            Box(Modifier.fillMaxWidth().height(with(density) { size.height.toDp() }), contentAlignment = Alignment.Center) {
                WorkspaceEmptyState(R.string.today_hint)
            }
        }
        val removeLabel = stringResource(R.string.widget_remove)
        val resizeLabel = stringResource(R.string.widget_resize)
        visible.forEach { placement ->
            key(placement.id) {
                val stored = committed.firstOrNull { it.id == placement.id } ?: placement
                val dragged = drag?.id == placement.id
                val target = IntOffset(placement.x * grid.cellWidth, placement.y * grid.cellHeight)
                val animated by animateIntOffsetAsState(target, label = "widget position")
                val width = with(density) { (stored.columns * grid.cellWidth).toDp() }
                val height = with(density) { (stored.rows * grid.cellHeight).toDp() }
                Box(
                    Modifier
                        .offset {
                            val moving = drag
                            if (dragged && moving != null) (moving.pointer - moving.grab).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
                            else animated
                        }
                        .size(width, height)
                        .zIndex(if (dragged) 2f else 0f)
                        .graphicsLayer {
                            val lift = if (dragged) tokens.todayLiftScale else 1f
                            scaleX = lift; scaleY = lift
                            shadowElevation = if (dragged) tokens.elevation.last().toPx() else 0f
                            shape = RoundedCornerShape(tokens.radius)
                            clip = false
                        }
                        .padding(with(density) { inset.toDp() })
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(removeLabel) { board.remove(stored); true },
                                CustomAccessibilityAction(resizeLabel) { resizing = stored.id; true }
                            )
                        }
                ) {
                    HostedWidget(board, stored, board.info(stored.id), Modifier.fillMaxSize())
                }
            }
        }
        // Where the dragged widget will land.
        drag?.let { moving ->
            val landing = moving.preview?.firstOrNull { it.id == moving.id }
            if (landing != null) Box(
                Modifier.offset { IntOffset(landing.x * grid.cellWidth + inset, landing.y * grid.cellHeight + inset) }
                    .size(with(density) { (landing.columns * grid.cellWidth - inset * 2).toDp() },
                        with(density) { (landing.rows * grid.cellHeight - inset * 2).toDp() })
                    .border(tokens.focusStroke, tokens.accent.copy(alpha = tokens.hoverOpacity), RoundedCornerShape(tokens.radius))
            )
        }
        // Resize frame with snapping handles and the widget actions.
        resizingId?.let { id ->
            val active = (resizePreview ?: committed).firstOrNull { it.id == id } ?: return@let
            val frame = bounds(active, grid)
            val info = board.info(id)
            val limits = board.spanLimits(info, grid)
            Box(
                Modifier.offset { IntOffset(frame.left.roundToInt() + inset, frame.top.roundToInt() + inset) }
                    .size(with(density) { (frame.width - inset * 2).toDp() }, with(density) { (frame.height - inset * 2).toDp() })
                    .border(tokens.focusStroke, tokens.accent, RoundedCornerShape(tokens.radius))
            )
            val dot = with(density) { tokens.todayHandle.toPx() }
            val handles = buildList {
                if (limits.horizontal) { add(Offset(frame.left + inset, frame.center.y)); add(Offset(frame.right - inset, frame.center.y)) }
                if (limits.vertical) { add(Offset(frame.center.x, frame.top + inset)); add(Offset(frame.center.x, frame.bottom - inset)) }
            }
            handles.forEach { center ->
                Box(
                    Modifier.offset { IntOffset((center.x - dot / 2).roundToInt(), (center.y - dot / 2).roundToInt()) }
                        .size(tokens.todayHandle).clip(CircleShape).background(tokens.accent)
                        .border(tokens.focusStroke, tokens.onAccent, CircleShape)
                )
            }
            val bar = actionBar(frame)
            Row(
                Modifier.offset { IntOffset(bar.left.roundToInt() + inset, bar.top.roundToInt()) }
                    .width(with(density) { (bar.width - inset * 2).toDp() }).height(tokens.pillHeight),
                horizontalArrangement = Arrangement.spacedBy(tokens.gap, Alignment.CenterHorizontally)
            ) {
                PillButton(stringResource(R.string.widget_remove)) {
                    resizing = null
                    committed.firstOrNull { it.id == id }?.let(board::remove)
                }
                if (info?.configure != null) PillButton(stringResource(R.string.widget_configure)) {
                    resizing = null
                    committed.firstOrNull { it.id == id }?.let(board::configure)
                }
            }
        }
        menu?.let { anchor ->
            Box(Modifier.offset { IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()) }) {
                DropdownMenu(
                    expanded = true, onDismissRequest = { menu = null },
                    shape = RoundedCornerShape(tokens.radius), containerColor = tokens.widgetSurface
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.widget_menu_widgets)) },
                        leadingIcon = { EnyoGlyph(Glyph.Widgets) },
                        onClick = { menu = null; openPicker() }
                    )
                    if (convertDefaults != null) DropdownMenuItem(
                        text = { Text(stringResource(R.string.widget_convert_defaults)) },
                        leadingIcon = { EnyoGlyph(Glyph.Today) },
                        onClick = { menu = null; convertDefaults() }
                    )
                }
            }
        }
    }
    // Remove sits at the top of the screen, whatever part of the grid is scrolled into view.
    drag?.let { moving ->
            val over = moving.pointer.y - scroll.value < removeZone
            Box(Modifier.fillMaxWidth().height(with(density) { removeZone.toDp() }), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.graphicsLayer { shadowElevation = tokens.elevation.last().toPx(); shape = CircleShape; clip = true }
                        .background(if (over) tokens.accent else tokens.accentContainer)
                        .padding(horizontal = tokens.padding, vertical = tokens.gap),
                    horizontalArrangement = Arrangement.spacedBy(tokens.gap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val color = if (over) tokens.onAccent else tokens.onAccentContainer
                    EnyoGlyph(Glyph.Trash, Modifier.size(tokens.noticeAppIcon), color = color)
                    Text(stringResource(R.string.widget_drop_remove), style = tokens.pillLabel.copy(color = color))
                }
            }
        }
    }
}
