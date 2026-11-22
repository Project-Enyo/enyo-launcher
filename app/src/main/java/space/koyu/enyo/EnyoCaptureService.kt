package space.koyu.enyo

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class EnyoCaptureService : AccessibilityService() {
    private val store by lazy { RecentStore.get(this) }
    private var trackedWindow: TrackedWindow? = null
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
        val request = store.capture ?: return
        val source = event.packageName?.toString() ?: return
        if (source == packageName && event.className?.toString() == EnyoActivity::class.java.name) {
            captureBeforeLauncher()
            store.capture = null
            trackedWindow = null
            return
        }
        val applicationWindow = windows.any {
            it.id == event.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        }
        if (applicationWindow) {
            trackedWindow = if (source == request.component.packageName) TrackedWindow(request, event.windowId) else null
        }
    }

    private fun captureBeforeLauncher() {
        if (Build.VERSION.SDK_INT < 34) return
        if (isLocked()) {
            onInterrupt()
            return
        }
        val target = trackedWindow ?: return
        if (target.request !== store.capture) return
        val exposed = windows.any { it.id == target.id && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (!exposed) return
        trackedWindow = null
        store.capture = null
        val now = SystemClock.elapsedRealtime()
        if (now - mostRecentAttempt < captureInterval) return
        mostRecentAttempt = now
        val generation = ++captureGeneration
        runCatching {
            takeScreenshotOfWindow(target.id, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val copy = buffer.use {
                        if (generation != captureGeneration || store.capture != null || isLocked()) return
                        runCatching {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                    }
                    if (copy != null) store.save(target.request, copy)
                }

                override fun onFailure(errorCode: Int) {
                    if (generation == captureGeneration && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                        store.denied(target.request)
                    }
                }
            })
        }
    }

    private fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    override fun onInterrupt() {
        captureGeneration++
        trackedWindow = null
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
    }
}
