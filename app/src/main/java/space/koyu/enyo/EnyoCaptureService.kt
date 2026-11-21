package space.koyu.enyo

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.content.pm.ApplicationInfo
import android.util.Log
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.max

class EnyoCaptureService : AccessibilityService() {
    private val store by lazy { RecentStore.get(this) }
    private var mostRecentAttempt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (Build.VERSION.SDK_INT < 34 || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) return
        val request = store.capture ?: return
        val now = SystemClock.elapsedRealtime()
        val age = now - request.createdAt
        if (age > requestLifetime) { store.capture = null; return }
        if (event.packageName?.toString() == request.component.packageName) {
            debug("Matching package event. Window ${event.windowId}. Expected activity ${event.className?.toString() == request.component.className}.")
        }
        if (event.packageName?.toString() != request.component.packageName ||
            event.windowId < 0
        ) return
        val targetWindow = event.windowId
        if (windows.none { it.id == targetWindow && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }) return
        store.capture = null
        val delay = max(settleTime, captureInterval - (now - mostRecentAttempt))
        mainExecutor.execute {
            android.os.Handler(mainLooper).postDelayed({
                if (getSystemService(KeyguardManager::class.java).isDeviceLocked ||
                    windows.none { it.id == targetWindow && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                ) return@postDelayed
                mostRecentAttempt = SystemClock.elapsedRealtime()
                runCatching {
                    takeScreenshotOfWindow(targetWindow, mainExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            val copy = buffer.use {
                                runCatching {
                                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                                }.getOrNull()
                            }
                            debug("Capture copied ${copy != null}.")
                            if (copy != null) store.save(request, copy)
                        }

                        override fun onFailure(errorCode: Int) {
                            debug("Capture status ${errorCode}.")
                            if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) store.denied(request)
                        }
                    })
                }
            }, delay)
        }
    }

    private fun debug(message: String) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d("EnyoCapture", message)
    }

    override fun onInterrupt() { store.capture = null }
    override fun onDestroy() {
        store.capture = null
        super.onDestroy()
    }

    private companion object {
        const val captureInterval = 450L
        const val settleTime = 300L
        const val requestLifetime = 2500L
    }
}
