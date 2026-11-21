package space.koyu.enyo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity

@Composable
internal fun Modifier.searchPull(
    enabled: Boolean,
    appGrid: () -> Boolean,
    appGridAtTop: () -> Boolean,
    update: (Float) -> Unit,
    open: () -> Unit
): Modifier {
    if (!enabled) return this
    val callback by rememberUpdatedState(open)
    val report by rememberUpdatedState(update)
    val onApps by rememberUpdatedState(appGrid)
    val appsAtTop by rememberUpdatedState(appGridAtTop)
    val threshold = with(LocalDensity.current) { LocalTokens.current.searchPullDistance.toPx() }
    val progress = remember { PullProgress() }
    val connection = remember(threshold) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || onApps()) return Offset.Zero
                if (consumed.y != 0f) progress.contentMoved = true
                if (progress.contentMoved) {
                    progress.distance = 0f
                    report(0f)
                    return Offset.Zero
                }
                if (available.y <= 0f || kotlin.math.abs(available.x) > available.y) {
                    progress.distance = (progress.distance + available.y).coerceAtLeast(0f)
                    report((progress.distance / threshold).coerceIn(0f, 1f))
                    return Offset.Zero
                }
                progress.distance += available.y
                report((progress.distance / threshold).coerceIn(0f, 1f))
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection).pointerInput(threshold) {
        awaitEachGesture {
            progress.distance = 0f
            progress.contentMoved = false
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val fromApps = onApps()
            if (fromApps && !appsAtTop()) return@awaitEachGesture
            var childScrolled = false
            var captured = false
            try {
                while (true) {
                    val event = awaitPointerEvent(if (fromApps) PointerEventPass.Initial else PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.size > 1) break
                    if (!change.pressed) {
                        if (progress.distance >= threshold) {
                            if (fromApps) change.consume()
                            callback()
                        }
                        break
                    }
                    val delta = change.position - down.position
                    if (fromApps) {
                        if (!captured && delta.getDistance() >= viewConfiguration.touchSlop) {
                            if (delta.y <= kotlin.math.abs(delta.x)) break
                            captured = true
                        }
                        if (captured) {
                            change.consume()
                            progress.distance = delta.y.coerceAtLeast(0f)
                            report((progress.distance / threshold).coerceIn(0f, 1f))
                        }
                        continue
                    }
                    childScrolled = childScrolled || change.isConsumed
                    if (!childScrolled) {
                        progress.distance = if (delta.y > kotlin.math.abs(delta.x)) delta.y else 0f
                        report((progress.distance / threshold).coerceIn(0f, 1f))
                    }
                }
            } finally {
                progress.distance = 0f
                report(0f)
            }
        }
    }
}

private class PullProgress {
    var distance = 0f
    var contentMoved = false
}
