package space.koyu.enyo

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

private data class SetupStep(val title: Int, val detail: Int, val enabled: Boolean, val request: () -> Unit)

@Composable
internal fun PermissionSetup(firstRun: Boolean, close: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val foreground = foregroundCycle()
    var permissionResult by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionResult++ }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionResult++ }
    var failed by remember { mutableStateOf(false) }
    fun open(action: String, uri: android.net.Uri? = null) {
        failed = !context.launchSafely(Intent(action, uri))
    }
    val steps = remember(context, foreground, permissionResult) {
        val roles = context.getSystemService(RoleManager::class.java)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val accessibility = context.getSystemService(AccessibilityManager::class.java)
        val capture = ComponentName(context, EnyoCaptureService::class.java)
        fun granted(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        buildList {
            if (roles.isRoleAvailable(RoleManager.ROLE_HOME)) add(
                SetupStep(R.string.setup_home, R.string.setup_home_reason, roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                    roleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                }
            )
            add(SetupStep(R.string.contacts_permission, R.string.contacts_reason, granted(Manifest.permission.READ_CONTACTS)) {
                permission.launch(Manifest.permission.READ_CONTACTS)
            })
            add(SetupStep(R.string.calendar_permission, R.string.setup_calendar_reason, granted(Manifest.permission.READ_CALENDAR)) {
                permission.launch(Manifest.permission.READ_CALENDAR)
            })
            add(SetupStep(R.string.notification_access, R.string.setup_notification_reason,
                notificationManager.isNotificationListenerAccessGranted(ComponentName(context, EnyoNotificationService::class.java))) {
                open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            })
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) add(
                SetupStep(R.string.setup_bluetooth, R.string.setup_bluetooth_reason, granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                    permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            )
            add(SetupStep(R.string.brightness, R.string.brightness_grant_reason, Settings.System.canWrite(context)) {
                open(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
            })
            if (Build.VERSION.SDK_INT >= 34) add(
                SetupStep(R.string.cards_capture_service, R.string.cards_capture_description,
                    accessibility.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                        .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == capture }) {
                    open(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                }
            )
        }
    }
    SettingsScreen(footer = if (firstRun) {
        {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                PillButton(stringResource(R.string.setup_finish), filled = true, onClick = close)
            }
        }
    } else null) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
            item {
                SettingsHeading(stringResource(if (firstRun) R.string.setup_welcome else R.string.setup_title),
                    if (firstRun) null else close)
            }
            item { ListCard { ListNote(stringResource(R.string.setup_intro)) } }
            item {
                SettingsPanel(stringResource(R.string.setup_permissions)) {
                    steps.forEach { step ->
                        ListRow(
                            stringResource(step.title),
                            summary = stringResource(step.detail),
                            summaryLines = 4,
                            trailing = {
                                if (step.enabled) Text(stringResource(R.string.setup_enabled), style = tokens.label.copy(color = tokens.accent))
                                else PillButton(stringResource(R.string.setup_configure), onClick = step.request)
                            }
                        )
                    }
                }
            }
            item { OverlayPreference(recommended = true) }
            item { ListCard { ListNote(stringResource(R.string.battery_discovery)) } }
            if (failed) item { ListNote(stringResource(R.string.action_unavailable)) }
        }
    }
}
