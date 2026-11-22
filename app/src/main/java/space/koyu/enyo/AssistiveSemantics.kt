package space.koyu.enyo

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * Compose tracks the full semantics tree on every frame whenever any accessibility service is
 * enabled, without asking what that service reads. Enyo's own capture service only takes window
 * screenshots and never reads nodes, so when it is the only enabled service nobody consumes the
 * launcher tree and that tracking costs dropped frames while scrolling. Measured on the attached
 * Pixel 6a Apps page: 38.6 percent janky frames with the capture service enabled, 2.3 percent
 * with it disabled.
 *
 * Returns a modifier that drops the launcher semantics only in that case. Touch exploration or
 * any other enabled service, such as a screen reader, switch access, or voice access, restores
 * the full tree immediately.
 */
@Composable
internal fun Modifier.semanticsWhenConsumed(): Modifier {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    val own = remember(context) { ComponentName(context, EnyoCaptureService::class.java) }
    var consumed by remember { mutableStateOf(manager.hasSemanticsConsumer(own)) }
    DisposableEffect(manager, own) {
        val refresh = { consumed = manager.hasSemanticsConsumer(own) }
        val state = AccessibilityManager.AccessibilityStateChangeListener { refresh() }
        val touch = AccessibilityManager.TouchExplorationStateChangeListener { refresh() }
        manager.addAccessibilityStateChangeListener(state)
        manager.addTouchExplorationStateChangeListener(touch)
        val services = if (Build.VERSION.SDK_INT >= 33) {
            AccessibilityManager.AccessibilityServicesStateChangeListener { refresh() }
                .also { manager.addAccessibilityServicesStateChangeListener(context.mainExecutor, it) }
        } else null
        refresh()
        onDispose {
            manager.removeAccessibilityStateChangeListener(state)
            manager.removeTouchExplorationStateChangeListener(touch)
            if (Build.VERSION.SDK_INT >= 33 && services != null) {
                manager.removeAccessibilityServicesStateChangeListener(services)
            }
        }
    }
    return if (consumed) this else clearAndSetSemantics {}
}

private fun AccessibilityManager.hasSemanticsConsumer(own: ComponentName): Boolean {
    if (!isEnabled) return true
    if (isTouchExplorationEnabled) return true
    return getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
        val service = info.resolveInfo?.serviceInfo ?: return@any true
        ComponentName(service.packageName, service.name) != own
    }
}

