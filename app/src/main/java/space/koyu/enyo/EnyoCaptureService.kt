package space.koyu.enyo

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.content.pm.ApplicationInfo
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.max

class EnyoCaptureService : AccessibilityService() {
    private val store by lazy { RecentStore.get(this) }
    private var mostRecentAttempt = 0L
    private val handler by lazy { Handler(mainLooper) }
    private var captureGeneration = 0L

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
        val generation = ++captureGeneration
        captureWindow(request, targetWindow, generation, retry = true)
    }

    private fun captureWindow(request: CaptureRequest, windowId: Int, generation: Long, retry: Boolean) {
        val delay = max(settleTime, captureInterval - (SystemClock.elapsedRealtime() - mostRecentAttempt))
        handler.postDelayed({
            if (generation != captureGeneration || store.capture != null ||
                SystemClock.elapsedRealtime() - request.createdAt > requestLifetime ||
                getSystemService(KeyguardManager::class.java).isDeviceLocked ||
                windows.none { it.id == windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ) return@postDelayed
            mostRecentAttempt = SystemClock.elapsedRealtime()
            runCatching {
                takeScreenshotOfWindow(windowId, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val copy = buffer.use {
                            if (generation != captureGeneration || store.capture != null ||
                                getSystemService(KeyguardManager::class.java).isDeviceLocked
                            ) return
                            runCatching {
                                Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                            }.getOrNull()
                        }
                        debug("Capture copied ${copy != null}.")
                        if (copy != null) store.save(request, copy)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (generation != captureGeneration) return
                        debug("Capture status ${errorCode}.")
                        when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> store.denied(request)
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT, ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
                                if (retry) captureWindow(request, windowId, generation, retry = false)
                        }
                    }
                })
            }
        }, delay)
    }

    private fun debug(message: String) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d("EnyoCapture", message)
    }

    override fun onInterrupt() {
        captureGeneration++
        handler.removeCallbacksAndMessages(null)
        store.capture = null
    }

    override fun onDestroy() {
        onInterrupt()
        super.onDestroy()
    }

    private companion object {
        const val captureInterval = 500L
        const val settleTime = 300L
        const val requestLifetime = 2500L
    }
}
