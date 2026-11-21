package space.koyu.enyo

import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.LauncherApps
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class BrightnessLevel(val fraction: Float, val label: Int) {
    Low(0.15f, R.string.brightness_low),
    Medium(0.35f, R.string.brightness_medium),
    High(0.65f, R.string.brightness_high),
    Maximum(1f, R.string.brightness_maximum)
}

@Composable
internal fun QuickPage(openPanel: () -> Unit, first: FocusRequester, searchOverlay: Boolean, visible: Boolean) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val model = viewModel<QuickState>()
    val state by model.snapshot.collectAsStateWithLifecycle()
    val focus = remember(first) { listOf(first) + List(5) { FocusRequester() } }
    fun order(index: Int, previous: Int?, next: Int?): Modifier =
        Modifier.focusRequester(focus[index]).focusProperties {
            if (previous != null) this.previous = focus[previous]
            if (next != null) this.next = focus[next]
        }
    val target = with(LocalDensity.current) { tokens.cover.roundToPx() }
    if (visible) DisposableEffect(lifecycle, model, target, context) {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        fun ready() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            power.isInteractive && !keyguard.isDeviceLocked
        var reconnectOnResume = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        reconnectOnResume = true
                        model.stop()
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        if (ready()) {
                            model.reconnect(target)
                            reconnectOnResume = false
                        } else reconnectOnResume = true
                    }
                }
            }
        }
        val signals = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, signals, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (ready()) {
                    if (reconnectOnResume) model.reconnect(target) else model.start(target)
                    reconnectOnResume = false
                }
                Lifecycle.Event.ON_PAUSE -> model.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
            model.stop()
        }
    }
    val window = (context as? EnyoActivity)?.window
    var brightnessMenu by remember { mutableStateOf(false) }
    var grantExplanation by remember { mutableStateOf(false) }
    var bluetoothExplanation by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<Float?>(null) }

    fun overrideWindow(value: Float) {
        window?.let { it.attributes = it.attributes.apply { screenBrightness = value } }
    }
    fun brightness(level: BrightnessLevel) {
        draft = null
        if (model.setBrightness(level.fraction)) overrideWindow(level.fraction)
        else grantExplanation = true
    }
    DisposableEffect(window) {
        onDispose { overrideWindow(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }

    val door: @Composable (Modifier) -> Unit = { modifier ->
        val network = state.network
        DoorCard(
            state = stringResource(
                when (network) {
                    NetworkStatus.Offline -> R.string.disconnected
                    NetworkStatus.Other -> R.string.connected
                    else -> R.string.wifi_connected
                }
            ),
            detail = when (network) {
                NetworkStatus.Weak -> stringResource(R.string.signal_weak)
                NetworkStatus.Fair -> stringResource(R.string.signal_fair)
                NetworkStatus.Good -> stringResource(R.string.signal_good)
                NetworkStatus.Strong -> stringResource(R.string.signal_strong)
                else -> null
            },
            open = openPanel,
            modifier = modifier.then(order(0, null, 1)),
            label = stringResource(R.string.wireless)
        )
    }
    val bluetooth: @Composable (Modifier) -> Unit = { modifier ->
        DoorCard(
            state = stringResource(when (state.bluetooth) {
                BluetoothStatus.Offline -> R.string.disabled
                BluetoothStatus.Idle -> R.string.bluetooth_idle
                BluetoothStatus.Connected -> R.string.connected
            }),
            detail = state.bluetoothName,
            open = {
                when {
                    !state.bluetoothPermission -> bluetoothExplanation = true
                    state.bluetooth == BluetoothStatus.Offline ->
                        context.launchSafely(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    else -> context.launchSafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            },
            modifier = modifier.then(order(1, 0, 2)),
            label = stringResource(R.string.bluetooth)
        )
    }
    val volume: @Composable (Modifier) -> Unit = { modifier ->
        ControlCard(stringResource(R.string.media_volume), state.volume, model::volume, modifier.then(order(2, 1, 3)),
            onLongPress = {
                context.launchSafely(Intent(Settings.Panel.ACTION_VOLUME))
            })
    }
    val brightnessCard: @Composable (Modifier) -> Unit = { modifier ->
        val displayed = draft ?: state.brightness
        ControlCard(
            stringResource(R.string.brightness), displayed, { draft = it }, modifier.then(order(3, 2, if (state.hasTorch) 4 else 5)),
            onTap = { brightnessMenu = true },
            valueLabel = if (state.automatic && draft == null) stringResource(R.string.automatic_short) else null,
            onDragSettled = {
                val requested = draft ?: state.brightness
                brightness(BrightnessLevel.entries.minBy { abs(it.fraction - requested) })
            }
        )
    }
    QuickGrid(
        ignoreIme = searchOverlay,
        held = { modifier -> MediaCard(state, modifier.then(order(5, if (state.hasTorch) 4 else 3, null)), model::transport) },
        rows = listOf(listOf(door, bluetooth), listOf(volume, brightnessCard)),
        strip = if (state.hasTorch) { modifier -> TorchCard(state, modifier.then(order(4, 3, 5)), model::toggleTorch) } else null
    )

    if (brightnessMenu) EnyoSheet(stringResource(R.string.brightness), { brightnessMenu = false }) {
        BrightnessLevel.entries.forEach { option ->
            TextButton(onClick = { brightnessMenu = false; brightness(option) }) {
                Text(stringResource(option.label))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.automatic, onCheckedChange = {
                if (model.automatic(it)) overrideWindow(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                else { brightnessMenu = false; grantExplanation = true }
            })
            Spacer(Modifier.width(tokens.gap))
            Text(stringResource(R.string.automatic_brightness))
        }
    }
    if (grantExplanation) {
        QuickGrantDialog(
            R.string.brightness, R.string.brightness_grant_reason,
            dismiss = { grantExplanation = false },
            open = {
                context.launchSafely(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
        )
    }
    if (bluetoothExplanation) {
        QuickGrantDialog(
            R.string.bluetooth, R.string.bluetooth_grant_reason,
            dismiss = { bluetoothExplanation = false },
            open = {
                context.launchSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
        )
    }
}

@Composable
private fun QuickGrantDialog(title: Int, reason: Int, dismiss: () -> Unit, open: () -> Unit) {
    EnyoSheet(stringResource(title), dismiss) {
        Text(stringResource(reason), style = LocalTokens.current.secondary)
        Row {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = { dismiss(); open() }) { Text(stringResource(R.string.android_settings)) }
        }
    }
}

@Composable
private fun TorchCard(state: QuickSnapshot, modifier: Modifier, toggle: () -> Unit) {
    val tokens = LocalTokens.current
    val on = state.torch == true
    val checked = stringResource(if (state.torch == null) R.string.torch_unavailable else if (on) R.string.enabled else R.string.disabled)
    val toggleLabel = stringResource(R.string.torch_toggle)
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = tokens.touch)
            .cardInput(activate = toggle)
            .toggleable(value = on, role = Role.Switch, onValueChange = { toggle() })
            .semantics {
                stateDescription = checked
                customActions = listOf(CustomAccessibilityAction(toggleLabel) { toggle(); true })
            },
        shape = RoundedCornerShape(tokens.radius),
        color = if (on) tokens.accentContainer else tokens.surface,
        shadowElevation = tokens.elevation[if (on) 2 else 1]
    ) {
        Row(
            Modifier.padding(horizontal = tokens.padding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.gap)
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.torch), style = tokens.state)
                if (state.torchFailed) Text(stringResource(R.string.torch_failed), style = tokens.secondary, maxLines = 1)
            }
            Text(checked, style = tokens.secondary)
        }
    }
}

@Composable
private fun MediaCard(state: QuickSnapshot, modifier: Modifier, transport: (Long) -> Unit) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val media = state.media
    val open: (() -> Unit)? = when {
        !state.mediaAllowed -> {
            { context.launchSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); Unit }
        }
        media != null -> {
            {
                if (!context.openMediaPlayer(media)) {
                    Toast.makeText(context, R.string.action_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }
        else -> null
    }
    val actionLabel = stringResource(if (state.mediaAllowed) R.string.media_open_player else R.string.notification_access)
    val interaction = if (open == null) Modifier.cardInput() else Modifier.cardInput(activate = open)
        .clickable(interactionSource = null, indication = null, role = Role.Button, onClickLabel = actionLabel, onClick = open)
    ContentCard(modifier.then(interaction)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(tokens.padding),
            contentAlignment = Alignment.Center
        ) {
            if (!state.mediaAllowed || media == null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(tokens.gap),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MediaCover(null, Modifier.size(tokens.touch))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.now_playing), style = tokens.state)
                        Text(
                            stringResource(if (state.mediaAllowed) R.string.no_media else R.string.media_needs_access),
                            style = tokens.secondary
                        )
                    }
                    if (open != null) EnyoGlyph(Glyph.Forward, color = tokens.quiet)
                }
            } else {
                val compact = maxHeight < tokens.cover + tokens.touch + tokens.gap * 2
                val cover = minOf(if (compact) tokens.mediaCompactCover else tokens.cover, maxWidth / 3)
                val inlineControls = compact && maxWidth - cover - tokens.gap >= tokens.touch * 3
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(tokens.gap)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(tokens.gap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MediaCover(media, Modifier.size(cover), open, actionLabel)
                        Column(Modifier.weight(1f)) {
                            if (!compact) Text(
                                stringResource(R.string.now_playing), style = tokens.label,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                media.title ?: stringResource(R.string.now_playing),
                                style = tokens.mediaTitle, maxLines = if (compact) 1 else 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            media.artist?.let {
                                Text(it, style = tokens.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (inlineControls) MediaControls(media, transport)
                        }
                    }
                    if (!inlineControls) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            MediaControls(media, transport)
                        }
                    }
                    if (media.duration > 0) MediaProgress(media)
                }
            }
        }
    }
}

private fun Context.openMediaPlayer(media: MediaSnapshot): Boolean {
    val user = Process.myUserHandle()
    val launcher = getSystemService(LauncherApps::class.java)
    val launched = runCatching {
        val activity = launcher.getActivityList(media.packageName, user).firstOrNull { it.user == user }
        if (activity == null) false else {
            launcher.startMainActivity(activity.componentName, user, null, null)
            true
        }
    }.getOrDefault(false)
    if (launched) return true

    val intent = runCatching { packageManager.getLaunchIntentForPackage(media.packageName) }.getOrNull()
    if (intent != null && launchSafely(intent)) return true
    return media.sessionActivity?.let { runCatching { it.send() }.isSuccess } == true
}

@Composable
private fun MediaCover(
    media: MediaSnapshot?, modifier: Modifier, open: (() -> Unit)? = null, openLabel: String? = null
) {
    val tokens = LocalTokens.current
    val tapArtwork = if (open == null) Modifier else Modifier.clickable(
        interactionSource = null, indication = null, role = Role.Button, onClickLabel = openLabel, onClick = open
    )
    Box(
        modifier.clip(RoundedCornerShape(tokens.artworkRadius)).background(tokens.accentContainer).then(tapArtwork),
        contentAlignment = Alignment.Center
    ) {
        val artwork = media?.artwork
        if (artwork == null) EnyoGlyph(Glyph.Music, Modifier.size(tokens.appGlyphSize), color = tokens.accent)
        else Image(
            bitmap = remember(artwork) { artwork.asImageBitmap() },
            contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun MediaControls(media: MediaSnapshot, transport: (Long) -> Unit) {
    val actions = media.playback?.actions ?: return
    val playing = media.playback.state == PlaybackState.STATE_PLAYING
    Row(verticalAlignment = Alignment.CenterVertically) {
        MediaButton(actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS, R.string.previous_track, Glyph.PreviousTrack, transport)
        MediaButton(
            actions, if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY,
            if (playing) R.string.pause else R.string.play, if (playing) Glyph.Pause else Glyph.Play,
            transport, primary = true
        )
        MediaButton(actions, PlaybackState.ACTION_SKIP_TO_NEXT, R.string.next_track, Glyph.NextTrack, transport)
    }
}

@Composable
private fun MediaButton(
    actions: Long, action: Long, label: Int, glyph: Glyph, transport: (Long) -> Unit, primary: Boolean = false
) {
    if (actions and action == 0L) return
    val tokens = LocalTokens.current
    if (primary) FilledTonalIconButton(
        onClick = { transport(action) },
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = tokens.accentContainer)
    ) {
        EnyoGlyph(glyph, color = tokens.accent, description = stringResource(label))
    } else IconButton(onClick = { transport(action) }) {
        EnyoGlyph(glyph, description = stringResource(label))
    }
}

@Composable
private fun MediaProgress(media: MediaSnapshot) {
    val tokens = LocalTokens.current
    val state = media.playback ?: return
    if (state.position < 0) return
    val playing = state.state == PlaybackState.STATE_PLAYING
    val position by produceState(state.position.toDouble(), state) {
        value = state.position.toDouble()
        if (playing) while (true) {
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            value = state.position + elapsed * state.playbackSpeed.toDouble()
            delay(250)
        }
    }
    LinearProgressIndicator(
        progress = { (position / media.duration).coerceIn(0.0, 1.0).toFloat() },
        modifier = Modifier.fillMaxWidth().height(tokens.progress),
        color = tokens.accent, trackColor = tokens.accentContainer
    )
}
