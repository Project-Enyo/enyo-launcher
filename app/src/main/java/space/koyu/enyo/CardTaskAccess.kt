package space.koyu.enyo

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

private const val shellUid = 2000
private const val taskPermissionRequest = 219

private data class TaskAccess(val available: Boolean, val granted: Boolean)

@Composable
private fun rememberTaskAccess(): TaskAccess {
    if (Process.myUserHandle() != UserHandle.getUserHandleForUid(shellUid)) return TaskAccess(false, false)
    var available by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }
    val foreground = foregroundCycle()
    fun update() {
        val running = runCatching {
            Shizuku.pingBinder() && Shizuku.getVersion() >= 13 && Shizuku.getUid() == shellUid
        }.getOrDefault(false)
        available = running
        granted = running && runCatching {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }
    LaunchedEffect(foreground) { update() }
    DisposableEffect(Unit) {
        val received = Shizuku.OnBinderReceivedListener { update() }
        val dead = Shizuku.OnBinderDeadListener {
            granted = false
            available = false
        }
        val permission = Shizuku.OnRequestPermissionResultListener { request, _ ->
            if (request == taskPermissionRequest) update()
        }
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        onDispose {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
            Shizuku.removeRequestPermissionResultListener(permission)
        }
    }
    return TaskAccess(available, granted)
}

@Composable
internal fun TaskClosePreference() {
    val access = rememberTaskAccess()
    if (!access.available) return
    var failed by remember { mutableStateOf(false) }
    SettingsPanel(stringResource(R.string.settings_cards)) {
        ListRow(
            stringResource(R.string.task_close_title),
            summary = stringResource(R.string.task_close_description),
            leading = { EnyoGlyph(Glyph.Cards, color = LocalTokens.current.onAccentContainer) },
            trailing = {
                if (access.granted) Text(stringResource(R.string.setup_enabled), style = LocalTokens.current.label)
                else PillButton(stringResource(R.string.running_tasks_allow)) {
                    failed = runCatching { Shizuku.requestPermission(taskPermissionRequest) }.isFailure
                }
            }
        )
        if (failed) ListNote(stringResource(R.string.action_unavailable))
    }
}

@Composable
internal fun rememberCardTaskCloser(): suspend (List<ComponentName>) -> Boolean {
    val context = LocalContext.current
    val access = rememberTaskAccess()
    var bridge by remember { mutableStateOf<ITaskBridge?>(null) }
    DisposableEffect(context, access.granted) {
        if (!access.granted) return@DisposableEffect onDispose {}
        val args = Shizuku.UserServiceArgs(ComponentName(context, EnyoTaskService::class.java))
            .processNameSuffix("tasks").daemon(false).version(3)
        var active = true
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (active) bridge = ITaskBridge.Stub.asInterface(binder)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (!active) return
                bridge = null
            }
        }
        runCatching { Shizuku.bindUserService(args, connection) }
        onDispose {
            active = false
            bridge = null
            runCatching { Shizuku.unbindUserService(args, connection, false) }
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    val currentAccess by rememberUpdatedState(access.granted)
    return close@{ components ->
        if (!currentAccess || components.isEmpty()) return@close true
        val service = withTimeoutOrNull(4000) {
            snapshotFlow { bridge }.filterNotNull().first()
        } ?: return@close false
        if (!currentAccess) return@close true
        withContext(Dispatchers.IO) {
            components.distinct().map { component ->
                runCatching { service.closeCardTask(component.flattenToString()) }.getOrDefault(false)
            }.all { it }
        }
    }
}
