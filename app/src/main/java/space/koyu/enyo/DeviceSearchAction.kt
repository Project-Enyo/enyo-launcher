package space.koyu.enyo

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import java.time.LocalTime

internal sealed interface DeviceSearchAction : LauncherSearchAction {
    data class Torch(val enabled: Boolean) : DeviceSearchAction
    data class Alarm(val time: LocalTime) : DeviceSearchAction
    data class Timer(val seconds: Int) : DeviceSearchAction
}

internal enum class IntentKind { Message, Flashlight, Alarm, Timer }

private val torchWord = Regex("""\b(?:flash\s*light|torch|taschenlampe|kameralicht)\b""", RegexOption.IGNORE_CASE)
private val alarmWord = Regex("""\b(?:alarm|wecker|wake\s+me)\b""", RegexOption.IGNORE_CASE)
private val timerWord = Regex("""\b(?:timer|countdown)\b""", RegexOption.IGNORE_CASE)
private val torchOn = Regex("""\b(?:on|enable|switch\s+on|activate|ein|an|einschalten|anmachen|aktivieren)\b""", RegexOption.IGNORE_CASE)
private val torchOff = Regex("""\b(?:off|disable|deactivate|aus|ausschalten|ausmachen|deaktivieren)\b""", RegexOption.IGNORE_CASE)
private val alarmTime = Regex("""(?<![\d:])(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?(?![\d:])""", RegexOption.IGNORE_CASE)
private val duration = Regex("""\b(\d{1,5})\s*(seconds?|secs?|sekunden?|minutes?|mins?|minuten?|hours?|hrs?|stunden?)\b""", RegexOption.IGNORE_CASE)
private val readOnlyRequest = Regex("""^(?:(?:please|bitte)\s+)?(?:why|what|how|is|are|find|search|show|summari[sz]e|recap|warum|wie|ist|zeige|such\w*)\b""", RegexOption.IGNORE_CASE)
private val declinedRequest = Regex("""\b(?:don['’]t|do\s+not|never|nicht|keinen?)\b""", RegexOption.IGNORE_CASE)

private val alarmRequest = Regex("""\b(?:set|create|schedule|wake\s+me|stell\w*|weck\w*|setz\w*)\b""", RegexOption.IGNORE_CASE)
private val timerRequest = Regex("""\b(?:set|start|create|run|stell\w*|start\w*|setz\w*)\b""", RegexOption.IGNORE_CASE)

internal fun intentKind(input: String): IntentKind? {
    if (readOnlyRequest.containsMatchIn(input.trimStart())) return null
    if (looksLikeMessageRequest(input)) return IntentKind.Message
    if (declinedRequest.containsMatchIn(input)) return null
    return when {
        torchWord.containsMatchIn(input) -> IntentKind.Flashlight
        alarmWord.containsMatchIn(input) -> IntentKind.Alarm
        timerWord.containsMatchIn(input) -> IntentKind.Timer
        else -> null
    }
}

internal fun matchDeviceAction(input: String, kind: IntentKind): DeviceSearchAction? = when (kind) {
    IntentKind.Flashlight -> {
        val on = torchOn.containsMatchIn(input)
        val off = torchOff.containsMatchIn(input)
        if (on == off) null else DeviceSearchAction.Torch(on)
    }
    IntentKind.Alarm -> if (alarmRequest.containsMatchIn(input)) parseAlarmTime(input)?.let(DeviceSearchAction::Alarm) else null
    IntentKind.Timer -> if (timerRequest.containsMatchIn(input)) parseTimerSeconds(input)?.let(DeviceSearchAction::Timer) else null
    IntentKind.Message -> null
}

internal fun parseAlarmTime(input: String): LocalTime? {
    val match = alarmTime.findAll(input).toList().singleOrNull() ?: return null
    val hour = match.groupValues[1].toInt()
    val prefix = input.substring(0, match.range.first).trimEnd().lowercase()
    val suffix = input.substring(match.range.last + 1).trimStart().lowercase()
    val relative = when {
        prefix.endsWith("half past") -> 30
        prefix.endsWith("quarter past") || prefix.endsWith("viertel nach") -> 15
        prefix.endsWith("quarter to") || prefix.endsWith("viertel vor") -> -15
        prefix.endsWith("halb") -> -30
        else -> 0
    }
    val statedMinutes = match.groupValues[2].toIntOrNull()
    if (relative != 0 && statedMinutes != null) return null
    val period = match.groupValues[3].lowercase().firstOrNull() ?: when {
        suffix.startsWith("in the morning") || suffix.startsWith("morgens") -> 'a'
        suffix.startsWith("in the evening") || suffix.startsWith("in the afternoon") ||
            suffix.startsWith("tonight") || suffix.startsWith("abends") || suffix.startsWith("nachmittags") -> 'p'
        else -> null
    }
    if (period != null && hour !in 1..12) return null
    val normalized = when (period) {
        'a' -> hour % 12
        'p' -> hour % 12 + 12
        else -> hour
    }
    return runCatching { LocalTime.of(normalized, statedMinutes ?: 0).plusMinutes(relative.toLong()) }.getOrNull()
}

internal fun parseTimerSeconds(input: String): Int? {
    val parts = duration.findAll(input).toList()
    if (parts.isEmpty()) return null
    return parts.sumOf {
        val multiplier = when (it.groupValues[2].lowercase().first()) {
            'h' -> 3600
            'm' -> 60
            else -> if (it.groupValues[2].startsWith("st", true)) 3600 else 1
        }
        it.groupValues[1].toLong() * multiplier
    }.takeIf { it in 1..86_400 }?.toInt()
}

internal fun DeviceSearchAction.clockIntent(): Intent? = when (this) {
    is DeviceSearchAction.Alarm -> Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, time.hour).putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
    is DeviceSearchAction.Timer -> Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
    is DeviceSearchAction.Torch -> null
}

internal fun DeviceSearchAction.execute(context: Context, openIntent: (Intent) -> Boolean): Boolean =
    if (this is DeviceSearchAction.Torch) setSearchTorch(context, enabled) else clockIntent()?.let(openIntent) == true

private fun setSearchTorch(context: Context, enabled: Boolean): Boolean {
    val camera = context.getSystemService(CameraManager::class.java)
    val id = camera.flashCameraId() ?: return false
    return runCatching { camera.setTorchMode(id, enabled) }.isSuccess
}
