package space.koyu.enyo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Job
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
    val restartLabel = stringResource(R.string.restart_title)
    SettingsPanel(null) {
        ListRow(
            restartLabel,
            Modifier
                .semantics { onClick(restartLabel) { restartLauncher(context); true } }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        var countdown: Job? = null
                        countdown = scope.launch {
                            for (left in restartHoldSeconds downTo 1) {
                                remaining = left
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                delay(1_000)
                            }
                            remaining = 0
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            restartLauncher(context)
                        }
                        waitForUpOrCancellation()
                        countdown.cancel()
                        remaining = null
                    }
                },
            summary = stringResource(R.string.restart_summary),
            leading = { EnyoGlyph(Glyph.Refresh, color = if (remaining != null) tokens.danger else tokens.onAccentContainer) },
            trailing = { remaining?.let { Text(it.toString(), style = tokens.state.copy(color = tokens.danger)) } }
        )
    }
}

private const val restartHoldSeconds = 3
