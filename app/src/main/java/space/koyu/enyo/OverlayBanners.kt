package space.koyu.enyo

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val systemBanners = booleanPreferencesKey("system_banners")
internal val launcherVisible = MutableStateFlow(false)

@Composable
internal fun OverlayPreference(recommended: Boolean = false) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    val store = context.appearancePreferences
    val foreground = foregroundCycle()
    val allowed = remember(foreground) { Settings.canDrawOverlays(context) }
    val enabled by remember(store) { store.data.map { it[systemBanners] == true } }.collectAsState(false)
    var error by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) scope.launch { store.edit { it[systemBanners] = true } }
    }
    SettingsPanel(stringResource(if (recommended) R.string.overlay_recommended else R.string.settings_banners)) {
        SettingsToggle(
            stringResource(R.string.overlay_enable), enabled && allowed,
            summary = stringResource(R.string.overlay_privacy), glyph = Glyph.Bell
        ) { next ->
            if (!next || allowed) scope.launch { store.edit { it[systemBanners] = next } }
            else runCatching {
                permission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null)))
            }.onFailure { error = true }
        }
        if (error) ListNote(stringResource(R.string.action_unavailable))
    }
}

internal class OverlayBanners(private val context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private var enabled = false
    private var view: ComposeView? = null
    private var owner: BannerOwner? = null
    private var windowManager: WindowManager? = null
    private var expiration: Job? = null
    private var downloadKey: String? = null
    private val closedDownloads = mutableSetOf<String>()
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) dismiss() else showDownloadIfNeeded()
        }
    }
    private val permission = AppOpsManager.OnOpChangedListener { _, _ ->
        scope.launch { if (!Settings.canDrawOverlays(context)) dismiss() }
    }

    init {
        val screenEvents = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) }
        ContextCompat.registerReceiver(context, screen, screenEvents, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching { appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, permission) }
        scope.launch {
            context.appearancePreferences.data.map { it[systemBanners] == true }.distinctUntilChanged().collect {
                enabled = it
                if (!it) dismiss() else showDownloadIfNeeded()
            }
        }
        scope.launch { launcherVisible.collect { if (it) dismiss() else showDownloadIfNeeded() } }
        scope.launch {
            EnyoNotificationService.entries.collect { active ->
                closedDownloads.retainAll(active.mapTo(mutableSetOf()) { it.key })
                if (downloadKey != null && active.none { it.key == downloadKey }) dismiss()
                showDownloadIfNeeded()
            }
        }
        scope.launch {
            EnyoNotificationService.arrivals.collect { entry ->
                val overlayNotice = entry.packageName == "android" &&
                    entry.tag == "com.android.server.wm.AlertWindowNotification - ${context.packageName}"
                if (!overlayNotice && !entry.isModelDownload(context.packageName) && canShow()) show(entry)
            }
        }
    }

    private fun canShow() = enabled && !launcherVisible.value && power.isInteractive &&
        !keyguard.isKeyguardLocked && Settings.canDrawOverlays(context)

    private fun showDownloadIfNeeded() {
        if (view != null || !canShow()) return
        EnyoNotificationService.entries.value.firstOrNull {
            it.key !in closedDownloads && it.isModelDownload(context.packageName)
        }?.let(::show)
    }

    private fun show(entry: StatusBarNotification) {
        dismiss()
        val downloading = entry.isModelDownload(context.packageName)
        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val overlayContext = runCatching {
            context.createWindowContext(display, overlayType, null)
        }.getOrNull() ?: return
        val manager = overlayContext.getSystemService(WindowManager::class.java)
        val metrics = manager.currentWindowMetrics
        val density = overlayContext.resources.displayMetrics.density
        val box = metrics.bounds
        val size = resolveCardSize((box.width() / density).dp, (box.height() / density).dp)
        val measured = Tokens(false, size)
        val inset = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val margin = (measured.margin.value * density).roundToInt()
        val width = (box.width() - inset.left - inset.right - margin * 2).coerceAtLeast(1)
        val params = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (measured.bottomClearance.value * density).roundToInt()
            setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        }
        val bannerOwner = BannerOwner()
        val banner = ComposeView(overlayContext).apply {
            setViewTreeLifecycleOwner(bannerOwner)
            setViewTreeSavedStateRegistryOwner(bannerOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val dark = isSystemInDarkTheme()
                val tokens = rememberTokens(dark, size)
                CompositionLocalProvider(LocalTokens provides tokens) {
                    val active by EnyoNotificationService.entries.collectAsState()
                    val current = if (downloading) active.firstOrNull { it.key == entry.key } ?: entry else entry
                    fun closeOverlay() {
                        if (downloading) closedDownloads.add(entry.key)
                        dismiss()
                    }
                    OverlayCard(current, close = ::closeOverlay, startReply = { startReply(params) }) {
                        if (!keyguard.isKeyguardLocked) {
                            // The notification target comes first, followed by its app.
                            val opened = current.openFromForeground(context) || context.launchNotifyingApp(current.packageName)
                            if (!opened) context.launchSafely(Intent(context, EnyoActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        closeOverlay()
                    }
                }
            }
        }
        if (runCatching { manager.addView(banner, params) }.isFailure) {
            bannerOwner.destroy()
            banner.disposeComposition()
            return
        }
        view = banner
        owner = bannerOwner
        windowManager = manager
        if (downloading) downloadKey = entry.key
        else expiration = scope.launch {
            delay(bannerLifetimeMillis)
            dismiss()
            showDownloadIfNeeded()
        }
    }

    /** Lets the banner take the keyboard while the person types a reply, and keeps it until they finish. */
    private fun startReply(params: WindowManager.LayoutParams) {
        expiration?.cancel()
        expiration = null
        val banner = view ?: return
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        runCatching { windowManager?.updateViewLayout(banner, params) }
    }

    private fun dismiss() {
        expiration?.cancel()
        expiration = null
        view?.let { banner -> runCatching { windowManager?.removeViewImmediate(banner) } }
        owner?.destroy()
        owner = null
        downloadKey = null
        view = null
        windowManager = null
    }

    override fun close() {
        dismiss()
        runCatching { context.unregisterReceiver(screen) }
        runCatching { appOps.stopWatchingMode(permission) }
        scope.cancel()
    }
}

private class BannerOwner : SavedStateRegistryOwner {
    override val lifecycle = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performAttach()
        saved.performRestore(null)
        lifecycle.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() { lifecycle.currentState = Lifecycle.State.DESTROYED }
}

@Composable
private fun OverlayCard(entry: StatusBarNotification, close: () -> Unit, startReply: () -> Unit, open: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val action = remember(entry) { if (entry.isMessagingNotice()) entry.notification.replyAction() else null }
    var replying by remember { mutableStateOf(false) }
    val group = entry.noticeGroup()
    val title = if (group.messaging) stringResource(R.string.messages) else sourceLabel(context, entry.packageName)
    val text = entry.noticePayload()
    val replyTitle = entry.notification.extras.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        ?: text.sender ?: title
    Surface(
        color = tokens.widgetSurface,
        contentColor = tokens.onAccentContainer,
        shape = RoundedCornerShape(tokens.sheetRadius),
        shadowElevation = tokens.elevation[2]
    ) {
        Column(Modifier.fillMaxWidth().padding(tokens.padding), verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
        Row(
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(tokens.gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EnyoGlyph(if (group.messaging) Glyph.Messages else Glyph.Inbox, color = tokens.accent)
            Column(Modifier.weight(1f).heightIn(min = tokens.touch).appPress(open)) {
                Text(if (replying) replyTitle else title, style = tokens.state, color = tokens.onAccentContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (replying) {
                    Text(sourceLabel(context, entry.packageName), style = tokens.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    text.sender?.let { Text(it, style = tokens.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    text.body?.let { Text(it, style = tokens.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
            val label = stringResource(R.string.overlay_close)
            Box(
                Modifier.size(tokens.touch).appPress(close).semantics { contentDescription = label },
                contentAlignment = Alignment.Center
            ) { EnyoGlyph(Glyph.Close) }
        }
        entry.modelDownloadProgress(context.packageName)?.let { progress ->
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(),
                color = tokens.accent, trackColor = tokens.widgetTrack)
        }
        if (action != null && !replying) Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
            PillButton(stringResource(R.string.message_reply), filled = true) { replying = true; startReply() }
        }
        if (action != null && replying) {
            entry.recentLines(count = 1).lastOrNull()?.let { line ->
                ConversationBubble(line.text, line.outgoing, line.sender?.takeUnless { line.outgoing || it == replyTitle }, maxLines = 3)
            }
            ReplyComposer(stringResource(R.string.message_reply), action.replyChoices(), autofocus = true) { reply ->
                sendReply(context, entry, action, reply).also { if (it) close() }
            }
        }
        }
    }
}
