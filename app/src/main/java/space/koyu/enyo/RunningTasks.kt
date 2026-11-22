package space.koyu.enyo

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private const val shellUid = 2000
private const val taskPermissionRequest = 219

@Composable
internal fun RunningTasksAction() {
    if (Process.myUserHandle() != UserHandle.getUserHandleForUid(shellUid)) return
    var available by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val foreground = foregroundCycle()
    fun update() {
        val running = runCatching {
            Shizuku.pingBinder() && Shizuku.getVersion() >= 13 && Shizuku.getUid() == shellUid
        }.getOrDefault(false)
        available = running
        granted = running && runCatching {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!running) open = false
    }
    LaunchedEffect(foreground) { update() }
    DisposableEffect(Unit) {
        val received = Shizuku.OnBinderReceivedListener { update() }
        val dead = Shizuku.OnBinderDeadListener {
            open = false
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
    if (available) TextButton(onClick = { open = true }) {
        Text(stringResource(R.string.running_tasks))
    }
    if (!open) return
    EnyoSheet(stringResource(R.string.running_tasks), { open = false }) {
        if (granted) {
            RunningTaskList()
        } else {
            var failed by remember { mutableStateOf(false) }
            Text(stringResource(R.string.running_tasks_permission))
            TextButton(onClick = {
                failed = runCatching { Shizuku.requestPermission(taskPermissionRequest) }.isFailure
            }) { Text(stringResource(R.string.running_tasks_allow)) }
            if (failed) Text(stringResource(R.string.action_unavailable))
        }
    }
}

private data class RunningTask(val id: Int, val component: ComponentName)

@Composable
private fun ColumnScope.RunningTaskList() {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    var bridge by remember { mutableStateOf<ITaskBridge?>(null) }
    var tasks by remember { mutableStateOf<List<RunningTask>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var unavailable by remember { mutableStateOf(false) }
    var closeFailed by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RunningTask?>(null) }
    val foreground = foregroundCycle()
    suspend fun refresh(service: ITaskBridge) {
        val result = withContext(Dispatchers.IO) { runCatching { service.listTasks() }.getOrNull() }
        if (bridge != service) return
        unavailable = result == null
        tasks = result.orEmpty().lineSequence().mapNotNull {
            val id = it.substringBefore(' ').toIntOrNull() ?: return@mapNotNull null
            val component = ComponentName.unflattenFromString(it.substringAfter(' ')) ?: return@mapNotNull null
            RunningTask(id, component)
        }.toList()
        busy = false
    }
    DisposableEffect(context) {
        val args = Shizuku.UserServiceArgs(ComponentName(context, EnyoTaskService::class.java))
            .processNameSuffix("tasks").daemon(false).version(2)
        var active = true
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (active) bridge = ITaskBridge.Stub.asInterface(binder)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (!active) return
                bridge = null
                selected = null
                tasks = emptyList()
                unavailable = true
                busy = false
            }
        }
        if (runCatching { Shizuku.bindUserService(args, connection) }.isFailure) {
            unavailable = true
            busy = false
        }
        onDispose {
            active = false
            runCatching { Shizuku.unbindUserService(args, connection, false) }
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }
    LaunchedEffect(bridge, foreground) {
        val service = bridge ?: return@LaunchedEffect
        busy = true
        refresh(service)
    }
    LaunchedEffect(bridge) {
        if (bridge == null) {
            kotlinx.coroutines.delay(5000)
            unavailable = true
            busy = false
        }
    }
    when {
        closing -> Text(stringResource(R.string.running_tasks_closing), style = tokens.secondary)
        busy -> Text(stringResource(R.string.running_tasks_loading), style = tokens.secondary)
        unavailable -> Text(stringResource(R.string.running_tasks_unavailable), style = tokens.secondary)
        tasks.isEmpty() -> Text(stringResource(R.string.running_tasks_empty), style = tokens.secondary)
    }
    if (closeFailed) Text(stringResource(R.string.running_tasks_close_failed), style = tokens.secondary)
    selected?.let { task ->
        Text(stringResource(R.string.running_tasks_confirm, sourceLabel(context, task.component.packageName)))
        Row {
            TextButton(onClick = { selected = null }) { Text(stringResource(R.string.back)) }
            TextButton(enabled = !busy && !closing, onClick = {
                val service = bridge ?: return@TextButton
                selected = null
                closing = true
                scope.launch {
                    closeFailed = !withContext(Dispatchers.IO) {
                        runCatching { service.closeTask(task.id, task.component.flattenToString()) }.getOrDefault(false)
                    }
                    refresh(service)
                    closing = false
                }
            }) { Text(stringResource(R.string.running_tasks_close)) }
        }
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        items(tasks, key = { it.id }) { task ->
            val label = remember(task.component) { sourceLabel(context, task.component.packageName) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = tokens.state)
                    Text(stringResource(R.string.running_task_number, task.id), style = tokens.secondary)
                }
                val close = stringResource(R.string.running_tasks_close_named, label)
                IconButton(
                    enabled = !busy && !closing && selected == null,
                    onClick = { closeFailed = false; selected = task },
                    modifier = Modifier.semantics { contentDescription = close }
                ) { EnyoGlyph(Glyph.Close) }
            }
        }
    }
}
