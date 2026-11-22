package space.koyu.enyo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs

/**
 * Hosts one launcher page in its own ComposeView. Compose keeps one semantics tree per view and,
 * while any accessibility service is enabled, sorts that whole tree after every layout change. A
 * page swipe then only moves this view, so the page's own tree stays unchanged and is not sorted
 * on every swipe frame. The child composition inherits all composition locals from the launcher.
 */
@Composable
internal fun PageHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val parent = rememberCompositionContext()
    val current by rememberUpdatedState(content)
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            ComposeView(context).apply {
                setParentCompositionContext(parent)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
                setContent { Box(Modifier.fillMaxSize().semanticsWhenConsumed()) { current() } }
            }
        }
    )
}

/**
 * Waits for a still hold before a horizontal drag adjusts a value, so an ordinary swipe across the
 * card keeps switching pages. Movement past touch slop before the hold leaves the gesture to the
 * pager and to taps; after the hold every horizontal step is consumed and reported.
 */
internal suspend fun PointerInputScope.detectHeldHorizontalDrag(
    holdMillis: Long,
    onHold: () -> Unit,
    onStart: (Offset) -> Unit,
    onDrag: (PointerInputChange, Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val slop = viewConfiguration.touchSlop
    var travel = Offset.Zero
    // The pointer scope's own timeout, which ends with null once the hold time passes.
    val held = withTimeoutOrNull<Boolean>(holdMillis) {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
            if (!change.pressed) return@withTimeoutOrNull false
            travel += change.positionChange()
            if (travel.getDistance() > slop) return@withTimeoutOrNull false
        }
        @Suppress("UNREACHABLE_CODE")
        false
    } ?: true
    if (!held) return@awaitEachGesture
    onHold()
    onStart(down.position)
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null) { onCancel(); return@awaitEachGesture }
        if (!change.pressed) { onEnd(); return@awaitEachGesture }
        val delta = change.positionChange().x
        change.consume()
        if (abs(delta) > 0f) onDrag(change, delta)
    }
}

@Composable
internal fun rememberHoldHaptic(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
}
