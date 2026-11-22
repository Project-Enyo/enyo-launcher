package space.koyu.enyo

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class EnyoTaskService(context: Context) : ITaskBridge.Stub() {
    private val ownerUid = context.applicationInfo.uid
    private val ownPackage = context.packageName

    override fun destroy() = exitProcess(0)

    @Synchronized
    override fun closeCardTask(component: String): Boolean {
        checkOwner()
        val expected = ComponentName.unflattenFromString(component) ?: return false
        val before = tasks() ?: return false
        val matches = before.filterValues { it == expected }
        if (matches.isEmpty()) return true
        val id = matches.keys.singleOrNull() ?: return false
        if (activityCommand("stack", "remove", id.toString()) == null) return false

        val deadline = SystemClock.elapsedRealtime() + closeConfirmationMillis
        do {
            val remaining = tasks()
            if (remaining != null && id !in remaining) return true
            val wait = deadline - SystemClock.elapsedRealtime()
            if (wait <= 0) break
            SystemClock.sleep(minOf(wait, closePollMillis))
        } while (true)
        return false
    }

    private fun checkOwner() {
        check(Process.myUid() == shellUid &&
            Binder.getCallingUid() == ownerUid &&
            UserHandle.getUserHandleForUid(ownerUid) == UserHandle.getUserHandleForUid(shellUid))
    }

    private fun tasks(): Map<Int, ComponentName>? {
        val output = activityCommand("stack", "list") ?: return null
        if (output.lineSequence().none { it.startsWith("RootTask id=") }) return null
        return buildMap {
            var standard = false
            output.lineSequence().forEach { line ->
                if (line.startsWith("RootTask id=")) standard = false
                if (line.startsWith(" configuration=")) standard = " mActivityType=standard" in line
                if (!standard) return@forEach
                val match = leaf.matchEntire(line) ?: return@forEach
                val id = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                val component = ComponentName.unflattenFromString(match.groupValues[2]) ?: return@forEach
                if (component.packageName != ownPackage) put(id, component)
            }
        }
    }

    private fun activityCommand(vararg arguments: String): String? = runCatching {
        val process = ProcessBuilder("/system/bin/cmd", "activity", *arguments)
            .redirectErrorStream(true)
            .start()
        val output = FutureTask {
            process.inputStream.use { input ->
                val bytes = ByteArray(maxOutput + 1)
                var size = 0
                while (size < bytes.size) {
                    val read = input.read(bytes, size, bytes.size - size)
                    if (read < 0) break
                    size += read
                }
                bytes.copyOf(size)
            }
        }
        Thread(output, "EnyoTaskOutput").apply { isDaemon = true }.start()
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) return null
            val bytes = output.get(1, TimeUnit.SECONDS)
            bytes.takeIf { it.size <= maxOutput }?.decodeToString()
        } finally {
            process.destroyForcibly()
            output.cancel(true)
        }
    }.getOrNull()

    private companion object {
        const val shellUid = 2000
        const val closeConfirmationMillis = 2000L
        const val closePollMillis = 150L
        const val maxOutput = 256 * 1024
        val leaf = Regex("""  taskId=(\d+): ([\w.$]+/[\w.$]+)(?: bounds=\S+)? userId=0 visible=(?:true|false)(?: topActivity=.*)?""")
    }
}
