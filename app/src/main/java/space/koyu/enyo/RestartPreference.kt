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
    val view = LocalView.current
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    var remaining by remember { mutableStateOf<Int?>(null) }
    val seconds = remaining ?: restartHoldSeconds
    val restartLabel = stringResource(R.string.restart_title)
    ListRow(
        restartLabel,
        Modifier
            .semantics { onClick(restartLabel) { restartLauncher(context); true } }
            .pointerInput(context, view) {
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
                        restartLauncher(context)
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
        leading = { EnyoGlyph(Glyph.Bomb, color = tokens.danger) },
        foreground = if (remaining != null) tokens.danger else Color.Unspecified
    )
}

private const val restartHoldSeconds = 3
