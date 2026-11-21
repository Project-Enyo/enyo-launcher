package space.koyu.enyo

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal data class QuickSnapshot(
    val network: NetworkStatus = NetworkStatus.Offline,
    val bluetooth: BluetoothStatus = BluetoothStatus.Offline,
    val bluetoothName: String? = null,
    val bluetoothPermission: Boolean = false,
    val volume: Float = 0f,
    val brightness: Float = 0f,
    val automatic: Boolean = false,
    val canWriteBrightness: Boolean = false,
    val hasTorch: Boolean = false,
    val torch: Boolean? = null,
    val torchFailed: Boolean = false,
    val mediaAllowed: Boolean = false,
    val media: MediaSnapshot? = null
)

internal enum class NetworkStatus { Offline, Other, Wireless, Weak, Fair, Good, Strong }
internal enum class BluetoothStatus { Offline, Idle, Connected }

internal data class MediaSnapshot(
    val title: String?,
    val artist: String?,
    val artwork: Bitmap?,
    val playback: PlaybackState?,
    val duration: Long
)

internal class QuickState(application: Application) : AndroidViewModel(application) {
    private val context: Context = application
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetooth.adapter
    private val camera = context.getSystemService(CameraManager::class.java)
    private val mediaManager = context.getSystemService(MediaSessionManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val listener = ComponentName(context, EnyoNotificationService::class.java)
    private val flashId by lazy {
        runCatching {
            camera.cameraIdList.mapNotNull { id ->
                val characteristics = camera.getCameraCharacteristics(id)
                id.takeIf { characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?.let { it to (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) }
            }.sortedByDescending { it.second }.firstOrNull()?.first
        }.getOrNull()
    }

    val snapshot = MutableStateFlow(QuickSnapshot())

    private var running = false
    private var polling: Job? = null
    private var torchError: Job? = null
    private var artJob: Job? = null
    private var artworkSource: Bitmap? = null
    private var artwork: Bitmap? = null
    private var artworkTarget = 0
    private var sessions = emptyList<MediaController>()
    private var selected: MediaController? = null
    private var observingMedia = false
    private var mediaRecovery: Job? = null
    private var recoveringToken: MediaSession.Token? = null

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) { main.post { if (running) refreshNetwork() } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            main.post { if (running) readNetwork(capabilities) }
        }
    }
    private val audioDevices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshAudio()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshAudio()
    }
    private val torch = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (running && cameraId == flashId) snapshot.update { it.copy(torch = enabled, torchFailed = false) }
        }
        override fun onTorchModeUnavailable(cameraId: String) {
            if (running && cameraId == flashId) snapshot.update { it.copy(torch = null) }
        }
    }
    private val settings = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refreshBrightness()
    }
    private val mediaSessions = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        if (running) attachSessions(controllers.orEmpty())
    }
    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = chooseSession()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishMedia(readMetadata = true)
        override fun onSessionDestroyed() = refreshMedia()
    }

    fun start(target: Int) {
        if (artworkTarget != target) {
            artworkTarget = target
            artworkSource = null
            artwork = null
        }
        if (running) { publishMedia(readMetadata = true); return }
        running = true
        snapshot.update { it.copy(hasTorch = flashId != null) }
        runCatching { connectivity.registerDefaultNetworkCallback(network, main) }
        audio.registerAudioDeviceCallback(audioDevices, main)
        if (flashId != null) runCatching { camera.registerTorchCallback(torch, main) }
        listOf(Settings.System.SCREEN_BRIGHTNESS, Settings.System.SCREEN_BRIGHTNESS_MODE).forEach { key ->
            context.contentResolver.registerContentObserver(Settings.System.getUriFor(key), false, settings)
        }
        refreshNetwork()
        refreshBrightness()
        refreshAudio()
        refreshMedia()
        polling = viewModelScope.launch {
            while (isActive) {
                delay(750)
                refreshAudio()
                refreshBrightness()
                if (snapshot.value.mediaAllowed != notifications.isNotificationListenerAccessGranted(listener)) refreshMedia()
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        polling?.cancel()
        torchError?.cancel()
        mediaRecovery?.cancel()
        mediaRecovery = null
        recoveringToken = null
        runCatching { connectivity.unregisterNetworkCallback(network) }
        audio.unregisterAudioDeviceCallback(audioDevices)
        if (flashId != null) camera.unregisterTorchCallback(torch)
        context.contentResolver.unregisterContentObserver(settings)
        if (observingMedia) mediaManager.removeOnActiveSessionsChangedListener(mediaSessions)
        observingMedia = false
        attachSessions(emptyList())
        snapshot.update { it.copy(torch = null, torchFailed = false, media = null) }
    }

    fun reconnect(target: Int) {
        stop()
        start(target)
    }

    private fun refreshNetwork() = readNetwork(connectivity.getNetworkCapabilities(connectivity.activeNetwork))

    private fun readNetwork(capabilities: NetworkCapabilities?) {
        val status = when {
            capabilities == null -> NetworkStatus.Offline
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus.Other
            else -> {
                val rssi = (capabilities.transportInfo as? WifiInfo)?.rssi
                if (rssi == null || rssi <= -127) NetworkStatus.Wireless else {
                    val max = wifi.maxSignalLevel.coerceAtLeast(1)
                    val level = wifi.calculateSignalLevel(rssi)
                    listOf(NetworkStatus.Weak, NetworkStatus.Fair, NetworkStatus.Good, NetworkStatus.Strong)[
                        (level * 3 / max).coerceIn(0, 3)
                    ]
                }
            }
        }
        snapshot.update { it.copy(network = status) }
    }

    private fun refreshAudio() {
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val allowed = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val bluetoothOutput = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type in bluetoothOutputs
        }
        val peripheral = if (allowed && bluetoothOutput == null) runCatching {
            bluetooth.getConnectedDevices(BluetoothProfile.GATT).firstOrNull()?.name
        }.getOrNull() else null
        val enabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        val state = when {
            bluetoothOutput != null || peripheral != null -> BluetoothStatus.Connected
            enabled -> BluetoothStatus.Idle
            else -> BluetoothStatus.Offline
        }
        snapshot.update {
            it.copy(
                bluetooth = state,
                bluetoothName = bluetoothOutput?.productName?.toString()?.takeIf(String::isNotBlank) ?: peripheral,
                bluetoothPermission = allowed,
                volume = if (maximum > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum else 0f
            )
        }
    }

    fun volume(value: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt().coerceIn(min, max), 0) }
        refreshAudio()
    }

    private fun refreshBrightness() {
        val resolver = context.contentResolver
        val level = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 127)
        val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        snapshot.update {
            it.copy(
                canWriteBrightness = Settings.System.canWrite(context),
                brightness = level.coerceIn(0, 255) / 255f,
                automatic = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            )
        }
    }

    fun setBrightness(level: Float): Boolean = runCatching {
        val resolver = context.contentResolver
        Settings.System.canWrite(context) &&
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) &&
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (level * 255).roundToInt())
    }.getOrDefault(false).also { refreshBrightness() }

    fun automatic(enabled: Boolean): Boolean = runCatching {
        val mode = if (enabled) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        Settings.System.canWrite(context) && Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
    }.getOrDefault(false).also { refreshBrightness() }

    fun toggleTorch() {
        val id = flashId ?: return
        val state = snapshot.value.torch
        if (state != null && runCatching { camera.setTorchMode(id, !state) }.isSuccess) return
        snapshot.update { it.copy(torchFailed = true) }
        torchError?.cancel()
        torchError = viewModelScope.launch {
            delay(2400)
            snapshot.update { it.copy(torchFailed = false) }
        }
    }

    private fun refreshMedia() {
        if (!running) return
        val allowed = notifications.isNotificationListenerAccessGranted(listener)
        snapshot.update { it.copy(mediaAllowed = allowed) }
        if (!allowed) {
            if (observingMedia) mediaManager.removeOnActiveSessionsChangedListener(mediaSessions)
            observingMedia = false
            attachSessions(emptyList())
            return
        }
        if (!observingMedia) {
            observingMedia = runCatching { mediaManager.addOnActiveSessionsChangedListener(mediaSessions, listener, main) }.isSuccess
        }
        attachSessions(runCatching { mediaManager.getActiveSessions(listener) }.getOrDefault(emptyList()))
    }

    private fun attachSessions(controllers: List<MediaController>) {
        sessions.forEach { it.unregisterCallback(mediaCallback) }
        sessions = controllers
        sessions.forEach { it.registerCallback(mediaCallback, main) }
        chooseSession()
    }

    private fun chooseSession() {
        val playing = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val next = playing ?: sessions.firstOrNull()
        val changed = next?.sessionToken != selected?.sessionToken
        if (changed) {
            artJob?.cancel()
            artworkSource = null
            artwork = null
            snapshot.update { it.copy(media = null) }
        }
        selected = next
        publishMedia(readMetadata = changed)
    }

    private fun publishMedia(readMetadata: Boolean) {
        val controller = selected ?: return
        val playback = controller.playbackState
        if (playback == null) {
            snapshot.update { it.copy(media = null) }
            recoverPausedConnection(controller.sessionToken)
            return
        }
        if (recoveringToken == controller.sessionToken) {
            mediaRecovery?.cancel()
            mediaRecovery = null
            recoveringToken = null
        }
        val metadata = if (readMetadata) controller.metadata else null
        val old = snapshot.value.media
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: old?.duration ?: 0L
        val title = if (metadata == null) old?.title else metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank)
        val artist = if (metadata == null) old?.artist else metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf(String::isNotBlank)
        if (metadata != null) {
            val cover = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (cover !== artworkSource) {
                artworkSource = cover
                artwork = null
                artJob?.cancel()
                val target = artworkTarget
                if (cover != null && target > 0) artJob = viewModelScope.launch {
                    val decoded = withContext(Dispatchers.Default) {
                        runCatching {
                            val scale = (target.toFloat() / maxOf(cover.width, cover.height)).coerceAtMost(1f)
                            Bitmap.createScaledBitmap(
                                cover,
                                (cover.width * scale).roundToInt().coerceAtLeast(1),
                                (cover.height * scale).roundToInt().coerceAtLeast(1),
                                true
                            )
                        }.getOrNull()
                    }
                    if (selected?.sessionToken == controller.sessionToken && artworkSource === cover) {
                        artwork = decoded
                        snapshot.update { it.copy(media = it.media?.copy(artwork = decoded)) }
                    }
                }
            }
        }
        snapshot.update { it.copy(media = MediaSnapshot(title, artist, artwork, playback, duration)) }
    }

    private fun recoverPausedConnection(token: MediaSession.Token) {
        if (!running || recoveringToken == token) return
        mediaRecovery?.cancel()
        recoveringToken = token
        mediaRecovery = viewModelScope.launch {
            delay(650)
            if (running && selected?.sessionToken == token && selected?.playbackState == null) {
                runCatching { NotificationListenerService.requestRebind(listener) }
                if (observingMedia) mediaManager.removeOnActiveSessionsChangedListener(mediaSessions)
                observingMedia = false
                refreshMedia()
            }
        }
    }

    fun transport(action: Long) {
        val controller = selected ?: return
        if (!snapshot.value.mediaAllowed || (controller.playbackState?.actions ?: 0L) and action == 0L) return
        runCatching {
            when (action) {
                PlaybackState.ACTION_PLAY -> controller.transportControls.play()
                PlaybackState.ACTION_PAUSE -> controller.transportControls.pause()
                PlaybackState.ACTION_SKIP_TO_NEXT -> controller.transportControls.skipToNext()
                PlaybackState.ACTION_SKIP_TO_PREVIOUS -> controller.transportControls.skipToPrevious()
            }
        }
    }

    override fun onCleared() = stop()

    private companion object {
        val bluetoothOutputs = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID
        )
    }
}
