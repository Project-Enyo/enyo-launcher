package space.koyu.enyo

import android.content.ActivityNotFoundException
import android.content.Intent
import android.icu.text.NumberFormat
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow

class EnyoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            CompositionLocalProvider(LocalTokens provides remember(dark) { Tokens(dark) }) {
                PreviewCards(::openInternetPanel)
            }
        }
    }

    private fun openInternetPanel() {
        for (action in listOf(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS)) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: ActivityNotFoundException) {
                continue
            }
        }
        Toast.makeText(this, R.string.panel_unavailable, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun PreviewCards(openPanel: () -> Unit) {
    val tokens = LocalTokens.current
    var level by rememberSaveable { mutableFloatStateOf(0.45f) }
    Column(
        Modifier.fillMaxSize()
            .background(tokens.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = tokens.margin)
            .padding(top = tokens.margin, bottom = tokens.bottomClearance),
        verticalArrangement = Arrangement.spacedBy(tokens.gap)
    ) {
        DoorCard(stringResource(R.string.network_state), stringResource(R.string.network_detail), openPanel)
        ControlCard(stringResource(R.string.volume), level, onValue = { level = it })
        ContentCard {
            Column(Modifier.padding(tokens.padding), verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                Text(stringResource(R.string.now_playing), style = tokens.state)
                Text(stringResource(R.string.no_media), style = tokens.secondary)
            }
        }
    }
}

@Composable
internal fun DoorCard(state: String, detail: String?, open: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    Surface(
        onClick = open,
        modifier = modifier.fillMaxWidth().heightIn(min = maxOf(tokens.cardHeight, tokens.touch)),
        shape = RoundedCornerShape(tokens.radius),
        color = tokens.surface,
        shadowElevation = tokens.elevation[1]
    ) {
        Row(
            Modifier.padding(tokens.padding),
            horizontalArrangement = Arrangement.spacedBy(tokens.gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(state, style = tokens.state, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) {
                    Text(detail, style = tokens.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Canvas(Modifier.size(tokens.chevronSize)) {
                val start = Offset(size.width / 4, size.height / 5)
                val middle = Offset(size.width * 3 / 4, size.height / 2)
                val end = Offset(start.x, size.height * 4 / 5)
                drawLine(tokens.quiet, start, middle, tokens.chevronStroke.toPx())
                drawLine(tokens.quiet, middle, end, tokens.chevronStroke.toPx())
            }
        }
    }
}

@Composable
internal fun ControlCard(label: String, value: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValue)
    ContentCard(
        modifier.semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
            setProgress {
                change(it.coerceIn(0f, 1f))
                true
            }
        }.pointerInput(Unit) {
            var position = 0f
            detectHorizontalDragGestures(
                onDragStart = { position = currentValue },
                onHorizontalDrag = { event, amount ->
                    event.consume()
                    position = (position + amount / size.width).coerceIn(0f, 1f)
                    change(position)
                }
            )
        }
    ) {
        Column(Modifier.padding(tokens.padding), verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
            Text(label, style = tokens.secondary)
            val locale = LocalConfiguration.current.locales[0]
            val format = remember(locale) { NumberFormat.getPercentInstance(locale) }
            Text(format.format(value.toDouble()), style = tokens.value)
            Canvas(Modifier.fillMaxWidth().height(tokens.stroke)) {
                val center = size.height / 2
                drawLine(tokens.accentContainer, Offset(0f, center), Offset(size.width, center), size.height)
                drawLine(tokens.accent, Offset(0f, center), Offset(size.width * value, center), size.height)
            }
        }
    }
}

@Composable
internal fun ContentCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tokens = LocalTokens.current
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = maxOf(tokens.cardHeight, tokens.touch)),
        shape = RoundedCornerShape(tokens.radius),
        color = tokens.surface,
        shadowElevation = tokens.elevation[1],
        content = content
    )
}
