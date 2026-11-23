package space.koyu.enyo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Restarts Enyo's process and opens it again; services such as push reconnect by themselves. */
internal fun restartLauncher(context: Context) {
    context.startActivity(Intent.makeRestartActivityTask(ComponentName(context, EnyoActivity::class.java)))
    Runtime.getRuntime().exit(0)
}

/**
 * Settings: hold for three seconds to restart Enyo. Each second ticks with a light haptic and the
 * red countdown; letting go early cancels. Screen readers get the restart as a plain action.
 */
@Composable
internal fun RestartPreference() {
    val context = LocalContext.current
    HoldAction(stringResource(R.string.restart_title), Glyph.Bomb, danger = true) { restartLauncher(context) }
}

/**
 * A setting that acts only after it is held for three seconds, counting down with a tick each
 * second, so it never happens by accident. Accessibility services can run it directly.
 */
@Composable
internal fun HoldAction(title: String, glyph: Glyph, danger: Boolean = false, run: () -> Unit) {
    val view = LocalView.current
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    val action by rememberUpdatedState(run)
    var remaining by remember { mutableStateOf<Int?>(null) }
    val seconds = remaining ?: restartHoldSeconds
    val color = if (danger) tokens.danger else tokens.accent
    ListRow(
        title,
        Modifier
            .semantics { onClick(title) { action(); true } }
            .pointerInput(view) {
                awaitEachGesture {
                    awaitFirstDown()
                    remaining = restartHoldSeconds
                    val countdown = scope.launch {
                        for (left in restartHoldSeconds downTo 1) {
                            remaining = left
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            delay(1_000)
                        }
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        remaining = null
                        action()
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        countdown.cancel()
                        remaining = null
                    }
                }
            },
        summary = pluralStringResource(R.plurals.restart_summary, seconds, seconds),
        leading = { EnyoGlyph(glyph, color = color) },
        foreground = if (remaining != null) color else Color.Unspecified
    )
}

private const val restartHoldSeconds = 3
