package space.koyu.enyo

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class EnyoCaptureService : AccessibilityService() {
    private val store by lazy { RecentStore.get(this) }
    private val handler by lazy { Handler(mainLooper) }
    private var trackedWindow: TrackedWindow? = null
    private var backup: Bitmap? = null
    private var backupRequested = false
    private var mostRecentAttempt = -captureInterval
    private var captureGeneration = 0L
    private val returnToLauncher: () -> Unit = { captureBeforeLauncher() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (Build.VERSION.SDK_INT >= 34) store.captureOnReturn = returnToLauncher
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (Build.VERSION.SDK_INT < 34 || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (isLocked()) {
            onInterrupt()
            return
        }
        val source = event.packageName?.toString() ?: return
        if (source == packageName && event.className?.toString() == EnyoActivity::class.java.name) {
            captureBeforeLauncher()
            return
        }
        if (!windowExists(event.windowId, active = true)) return
        var request = store.capture
        if (request == null || source != request.component.packageName) {
            resetTracking()
            // An app that came forward another way, such as from a widget or Android Settings, also gets a card.
            if (source == packageName || source in ignoredPackages) return
            // Panels and dialogs float over the launcher; they are not apps the person moved to.
            val className = event.className?.toString().orEmpty()
            if (floatingWindow.containsMatchIn(className)) return
            val component = event.className?.toString()?.let { android.content.ComponentName(source, it) }
                ?: android.content.ComponentName(source, "")
            request = store.adopt(component) ?: return
            if (source != request.component.packageName) return
        }
        if (trackedWindow?.request !== request) resetTracking()
        val target = TrackedWindow(request, event.windowId)
        trackedWindow = target
        if (!request.needsPreview || backupRequested) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ captureBackup(target) }, backupDelay)
    }

    private fun captureBackup(target: TrackedWindow) {
        if (trackedWindow != target || store.capture !== target.request ||
            isLocked() || !windowExists(target.id, active = true) || !canCapture()
        ) return
        backupRequested = true
        val generation = captureGeneration
        screenshot(target, generation, onBitmap = { bitmap -> backup = bitmap }, onUnavailable = {})
    }

    private fun captureBeforeLauncher() {
        if (Build.VERSION.SDK_INT < 34) return
        if (isLocked()) {
            onInterrupt()
            return
        }
        val request = store.capture ?: return
        val target = trackedWindow?.takeIf { it.request === request }
        var saved = if (target != null) backup else null
        if (target != null) backup = null
        resetTracking()
        store.capture = null
        val generation = captureGeneration
        fun useBackup() {
            saved?.let { bitmap ->
                if (generation == captureGeneration && store.capture == null && !isLocked()) {
                    store.save(request, bitmap)
                } else bitmap.recycle()
            }
            saved = null
        }
        if (target == null || !windowExists(target.id) || !canCapture()) {
            useBackup()
            return
        }
        screenshot(target, generation, onBitmap = { bitmap ->
            saved?.recycle()
            saved = null
            store.save(request, bitmap)
        }, onUnavailable = { secure ->
            if (secure) {
                saved?.recycle()
                saved = null
            } else useBackup()
        })
    }

    private fun screenshot(
        target: TrackedWindow,
        generation: Long,
        onBitmap: (Bitmap) -> Unit,
        onUnavailable: (Boolean) -> Unit
    ) {
        mostRecentAttempt = SystemClock.elapsedRealtime()
        fun acceptResult(): Boolean = generation == captureGeneration && !isLocked() &&
            (store.capture == null || store.capture === target.request)
        runCatching {
            takeScreenshotOfWindow(target.id, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val copy = buffer.use {
                        if (!acceptResult()) return@use null
                        runCatching {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                    }
                    if (copy != null) onBitmap(copy) else onUnavailable(false)
                }

                override fun onFailure(errorCode: Int) {
                    val secure = errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                    if (secure && acceptResult()) {
                        store.denied(target.request)
                        if (store.capture === target.request) store.capture = null
                    }
                    onUnavailable(secure)
                }
            })
        }.onFailure { onUnavailable(false) }
    }

    private fun windowExists(id: Int, active: Boolean = false): Boolean =
        windows.any { it.id == id && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (!active || it.isActive) }

    private fun canCapture(): Boolean = SystemClock.elapsedRealtime() - mostRecentAttempt >= captureInterval

    private fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun resetTracking() {
        captureGeneration++
        handler.removeCallbacksAndMessages(null)
        trackedWindow = null
        backup?.recycle()
        backup = null
        backupRequested = false
    }

    override fun onInterrupt() {
        resetTracking()
        store.capture = null
    }

    override fun onDestroy() {
        if (store.captureOnReturn === returnToLauncher) store.captureOnReturn = null
        onInterrupt()
        super.onDestroy()
    }

    private data class TrackedWindow(val request: CaptureRequest, val id: Int)

    private companion object {
        const val captureInterval = 500L
        const val backupDelay = 650L
        val floatingWindow = Regex("Panel|Dialog|Chooser|Resolver|Popup", RegexOption.IGNORE_CASE)
        /** Windows that come forward over apps without being an app the person opened. */
        val ignoredPackages = setOf("com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.intentresolver", "android")
    }
}
