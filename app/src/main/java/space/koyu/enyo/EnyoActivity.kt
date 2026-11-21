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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow

class EnyoActivity : ComponentActivity() {
    private var launcherKeys: ((android.view.KeyEvent) -> Boolean)? = null
    private var homeSequence by mutableIntStateOf(0)

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && !event.isCtrlPressed &&
            !event.isAltPressed && !event.isMetaPressed &&
            launcherKeys?.invoke(event) == true
        ) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeSequence++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val size = resolveCardSize(maxWidth, maxHeight)
                val tokens = remember(dark, size) { Tokens(dark, size) }
                val appearance = remember { appearancePreferences.data }
                val prefs by appearance.collectAsState(null)
                val brandIcons by EnyoNotificationService.brandIcons.collectAsState()
                val scheme = remember(tokens) {
                    val base = if (dark) darkColorScheme() else lightColorScheme()
                    base.copy(
                        primary = tokens.accent,
                        onPrimary = tokens.background,
                        primaryContainer = tokens.accentContainer,
                        onPrimaryContainer = tokens.ink,
                        secondary = tokens.accent,
                        tertiary = tokens.accent,
                        background = tokens.background,
                        onBackground = tokens.ink,
                        surface = tokens.surface,
                        onSurface = tokens.ink,
                        onSurfaceVariant = tokens.quiet,
                        surfaceContainerHigh = tokens.surface,
                        outline = tokens.quiet
                    )
                }
                MaterialTheme(colorScheme = scheme) {
                    CompositionLocalProvider(
                        LocalTokens provides tokens,
                        LocalOriginalIcons provides (prefs?.get(originalIcons) == true),
                        LocalBrandIcons provides brandIcons
                    ) {
                        LauncherScreen(::openInternetPanel, homeSequence) { launcherKeys = it }
                    }
                }
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
internal fun DoorCard(state: String, detail: String?, open: () -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val tokens = LocalTokens.current
    Surface(
        onClick = open,
        modifier = modifier.fillMaxWidth().heightIn(min = maxOf(tokens.cardHeight, tokens.touch)).cardInput(activate = open),
        shape = RoundedCornerShape(tokens.radius),
        color = tokens.surface.copy(alpha = tokens.cardOpacity),
        shadowElevation = tokens.elevation[1]
    ) {
        Row(
            Modifier.padding(tokens.padding),
            horizontalArrangement = Arrangement.spacedBy(tokens.gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                if (label != null) Text(label, style = tokens.label, maxLines = 1)
                Text(state, style = tokens.state, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null && (label == null || tokens.size != CardSize.Tiny)) {
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
internal fun ControlCard(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    valueLabel: String? = null,
    onDragSettled: (() -> Unit)? = null
) {
    val tokens = LocalTokens.current
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValue)
    val settled by rememberUpdatedState(onDragSettled)
    val click = if (onTap != null || onLongPress != null) {
        Modifier.combinedClickable(onClick = onTap ?: {}, onLongClick = onLongPress)
    } else Modifier
    ContentCard(
        modifier.fillMaxWidth().heightIn(min = maxOf(tokens.cardHeight, tokens.touch))
            .cardInput(ownFocusTarget = onTap == null && onLongPress == null, activate = onTap ?: onLongPress)
            .then(click).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
            setProgress {
                change(it.coerceIn(0f, 1f))
                true
            }
        }.pointerInput(Unit) {
            var position = 0f
            detectHorizontalDragGestures(
                onDragStart = { position = currentValue },
                onDragEnd = { settled?.invoke() },
                onHorizontalDrag = { event, amount ->
                    event.consume()
                    position = (position + amount / size.width).coerceIn(0f, 1f)
                    change(position)
                }
            )
        }
    ) {
        Column(Modifier.padding(tokens.padding), verticalArrangement = Arrangement.Center) {
            Text(label, style = tokens.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val locale = LocalConfiguration.current.locales[0]
            val format = remember(locale) { NumberFormat.getPercentInstance(locale) }
            Text(valueLabel ?: format.format(value.toDouble()), style = tokens.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        color = tokens.surface.copy(alpha = tokens.cardOpacity),
        shadowElevation = tokens.elevation[1],
        content = content
    )
}
