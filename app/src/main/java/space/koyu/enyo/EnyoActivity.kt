package space.koyu.enyo

import android.content.ActivityNotFoundException
import android.content.Intent
import android.icu.text.NumberFormat
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
    private val widgetBoard by lazy { WidgetBoard(this) }
    private val widgetBinding = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        widgetBoard.bindingResult(it.resultCode == RESULT_OK)
    }

    internal fun requestWidgetBinding(intent: Intent) = widgetBinding.launch(intent)

    @Deprecated("Required by the Android widget configuration flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == widgetConfigureRequest) widgetBoard.configurationResult(resultCode == RESULT_OK)
    }

    override fun onStart() {
        super.onStart()
        RecentStore.get(this).captureOnReturn?.invoke()
        launcherVisible.value = true
        widgetBoard.start()
    }

    override fun onStop() {
        launcherVisible.value = false
        widgetBoard.stop()
        super.onStop()
    }

    private var launcherKeys: ((android.view.KeyEvent) -> Boolean)? = null
    private var homeSequence by mutableIntStateOf(0)
    private var weatherTap by mutableLongStateOf(0)

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && !event.isCtrlPressed &&
            !event.isAltPressed && !event.isMetaPressed &&
            launcherKeys?.invoke(event) == true
        ) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isWeatherWidgetIntent(intent)) weatherTap = SystemClock.elapsedRealtime()
        else if (todayActionKind(intent) != null) todayActionRequest.value = todayActionKind(intent)
        else if (newsRequest(intent) != null) NewsStore.requested.value = newsRequest(intent)
        else if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            RecentStore.get(this).captureOnReturn?.invoke()
            homeSequence++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && isWeatherWidgetIntent(intent)) weatherTap = SystemClock.elapsedRealtime()
        if (savedInstanceState == null) todayActionKind(intent)?.let { todayActionRequest.value = it }
        if (savedInstanceState == null) newsRequest(intent)?.let { NewsStore.requested.value = it }
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val size = resolveCardSize(maxWidth, maxHeight)
                val tokens = rememberTokens(dark, size)
                val appearance = remember { appearancePreferences.data }
                val prefs by appearance.collectAsState(null)
                val brandIcons by EnyoNotificationService.brandIcons.collectAsState()
                val scheme = remember(tokens) {
                    val base = if (dark) darkColorScheme() else lightColorScheme()
                    base.copy(
                        primary = tokens.accent,
                        onPrimary = tokens.onAccent,
                        primaryContainer = tokens.accentContainer,
                        onPrimaryContainer = tokens.onAccentContainer,
                        secondary = tokens.accent,
                        onSecondary = tokens.onAccent,
                        secondaryContainer = tokens.accentContainer,
                        onSecondaryContainer = tokens.onAccentContainer,
                        tertiary = tokens.accent,
                        onTertiary = tokens.onAccent,
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
                        LocalWidgetBoard provides widgetBoard,
                        LocalOriginalIcons provides (prefs?.get(originalIcons) == true),
                        LocalBrandIcons provides brandIcons
                    ) {
                        Box(Modifier.fillMaxSize().semanticsWhenConsumed()) {
                            LauncherScreen(::openInternetPanel, homeSequence, weatherTap) { launcherKeys = it }
                            WallpaperConfirmationBanner(Modifier.align(Alignment.BottomCenter))
                            val todayRequest by todayActionRequest.collectAsState()
                            todayRequest?.let { kind -> TodayActionSheet(kind, ::openInternetPanel) { todayActionRequest.value = null } }
                            val newsRequested by NewsStore.requested.collectAsState()
                            newsRequested?.let { article -> NewsReader(article) { NewsStore.requested.value = null } }
                        }
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
        color = tokens.widgetSurface,
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
            EnyoGlyph(Glyph.Forward, Modifier.size(tokens.chevronSize), color = tokens.quiet)
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
    valueLabel: String? = null,
    onDragSettled: (() -> Unit)? = null
) {
    val tokens = LocalTokens.current
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValue)
    val settled by rememberUpdatedState(onDragSettled)
    val hold = rememberHoldHaptic()
    val click = if (onTap != null) {
        Modifier.combinedClickable(onClick = onTap)
    } else Modifier
    ContentCard(
        modifier.fillMaxWidth().heightIn(min = maxOf(tokens.cardHeight, tokens.touch))
            .cardInput(ownFocusTarget = onTap == null, activate = onTap)
            .then(click).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
            setProgress {
                change(it.coerceIn(0f, 1f))
                settled?.invoke()
                true
            }
        }.pointerInput(tokens.controlHoldMillis) {
            var position = 0f
            // A brief still hold starts adjusting, so a plain swipe across the card still switches pages.
            detectHeldHorizontalDrag(
                holdMillis = tokens.controlHoldMillis,
                onHold = hold,
                onStart = { position = currentValue },
                onDrag = { _, amount ->
                    position = (position + amount / size.width).coerceIn(0f, 1f)
                    change(position)
                },
                onEnd = { settled?.invoke() },
                onCancel = { settled?.invoke() }
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
                drawLine(tokens.widgetTrack, Offset(0f, center), Offset(size.width, center), size.height)
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
        color = tokens.widgetSurface,
        shadowElevation = tokens.elevation[1],
        content = content
    )
}
