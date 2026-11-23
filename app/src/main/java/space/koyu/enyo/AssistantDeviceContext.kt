package space.koyu.enyo

import android.Manifest
import android.app.KeyguardManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.SystemClock
import android.provider.CalendarContract.Instances
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private val batteryQuery = Regex("""\b(?:battery|charging|akku|batterie|ladestand)\b""", RegexOption.IGNORE_CASE)
private val wifiQuery = Regex("""\b(?:wi[ -]?fi|wlan|network status|internet connection|netzwerkstatus)\b""", RegexOption.IGNORE_CASE)
private val bluetoothQuery = Regex("""\b(?:bluetooth|connected devices|verbundene geräte)\b""", RegexOption.IGNORE_CASE)
private val pairedQuery = Regex("""\b(?:paired|bonded|gekoppelt\w*)\b""", RegexOption.IGNORE_CASE)
private val timeQuery = Regex(
    """\b(?:current time|what time is it|local time|current date|today'?s date|what day is it|wie spät|wieviel uhr|wie viel uhr|uhrzeit|heutiges datum|welcher tag)\b""",
    RegexOption.IGNORE_CASE
)
private val calendarQuery = Regex(
    """\b(?:calendar|agenda|events?|appointments?|meetings?|schedule|kalender|termin\w*|veranstaltungen?)\b""",
    RegexOption.IGNORE_CASE
)
private val weatherQuery = Regex("""\b(?:weather|forecast|temperature|wetter|temperatur|vorhersage)\b""", RegexOption.IGNORE_CASE)

private val directClockQuery = Regex(
    """^(?:(?:please|bitte)\s+)?(?:what(?:'s| is) the (?:current |local )?(?:time|date)|what time is it|what day is it|(?:current |local )(?:time|date)|(?:wie spät|wie ?viel uhr) ist es(?: gerade| jetzt)?|(?:aktuelle |lokale )?uhrzeit|heutiges datum|welcher tag ist heute)[.!?]*$""",
    RegexOption.IGNORE_CASE
)

internal fun isDirectClockQuery(query: String): Boolean = directClockQuery.matches(query.trim())

private val deviceDetail = Regex(
    """\b(?:my|our|current|local|right now|today|tomorrow|upcoming|connected|paired|remaining|meine?\w*|unser\w*|aktuell\w*|heute|morgen|nächste\w*|gerade|verbunden\w*|gekoppelt\w*)\b""",
    RegexOption.IGNORE_CASE
)
private val directDeviceQuestion = Regex(
    """^(?:(?:how much battery|how full is (?:the )?battery|is (?:the )?(?:wi[ -]?fi|bluetooth) (?:on|connected)|(?:wie|ist|welche)\b.*(?:akku|batterie|wetter|wlan|bluetooth|termin)\w*)[^\n]*|(?:what(?:'s| is) (?:the )?)?(?:weather(?: forecast)?|battery(?: status| level)?|wi[ -]?fi(?: status)?|bluetooth(?: status)?|calendar|wetter|akku|wlan|kalender)[.!?]*)$""",
    RegexOption.IGNORE_CASE
)

internal fun assistantClockAnswer(context: Context, query: String, now: Long = System.currentTimeMillis()): String? {
    if (!isDirectClockQuery(query)) return null
    val date = Date(now)
    val localTime = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    val offset = localTime.format(DateTimeFormatter.ofPattern("xxx"))
    return context.getString(R.string.local_ai_clock_answer, DateFormat.getLongDateFormat(context).format(date),
        DateFormat.getTimeFormat(context).format(date), localTime.zone.id, offset)
}

internal fun assistantDeviceContext(context: Context, query: String): String {
    if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return ""
    val reportTime = timeQuery.containsMatchIn(query) && (isDirectClockQuery(query) ||
        !query.contains(Regex("""\b(?:write|story|poem|explain|schreib\w*|geschichte|gedicht|erklär\w*)\b""", RegexOption.IGNORE_CASE)))
    val reportDevice = deviceDetail.containsMatchIn(query) || directDeviceQuestion.matches(query.trim())
    if (!reportDevice && !reportTime) return ""
    val now = System.currentTimeMillis()
    return buildList {
        if (reportDevice && batteryQuery.containsMatchIn(query)) {
            val reading = readBattery(context)
            add(if (reading == null) "Battery: unavailable" else
                "Battery: ${(reading.level * 100).roundToInt()}%, ${if (reading.charging) "charging or full" else "not charging"}")
        }
        if (reportDevice && wifiQuery.containsMatchIn(query)) add(wifiContext(context))
        if (reportDevice && bluetoothQuery.containsMatchIn(query)) add(bluetoothContext(context, pairedQuery.containsMatchIn(query)))
        if (reportTime) {
            val date = Date(now)
            val localTime = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            add("Current device local time: ${DateFormat.getLongDateFormat(context).format(date)}, ${DateFormat.getTimeFormat(context).format(date)}; time zone: ${localTime.zone.id}, UTC${localTime.format(DateTimeFormatter.ofPattern("xxx"))}.")
        }
        if (reportDevice && calendarQuery.containsMatchIn(query)) add(calendarContext(context, now))
        if (reportDevice && weatherQuery.containsMatchIn(query)) add(weatherContext(context))
    }.joinToString("\n").take(1500)
}

private fun wifiContext(context: Context): String {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val state = manager.getNetworkCapabilities(manager.activeNetwork)
    if (state?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return "Wi-Fi: not the active connection"
    val network = (state.transportInfo as? WifiInfo)?.ssid?.trim('"')?.takeIf {
        it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true)
    }
    val validated = state.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    return "Wi-Fi: connected" + (network?.let { " to ${it.take(80)}" } ?: "") +
        if (validated) ", internet validated" else ", internet availability has not been verified"
}

private fun bluetoothContext(context: Context, includePaired: Boolean): String {
    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        return "Bluetooth: permission unavailable"
    }
    val bluetooth = context.getSystemService(BluetoothManager::class.java)
    val adapter = bluetooth.adapter ?: return "Bluetooth: unavailable"
    return runCatching {
        val audio = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in bluetoothAudioOutputs }.map { it.productName.toString() }
        val peripheral = bluetooth.getConnectedDevices(BluetoothProfile.GATT).mapNotNull { it.name }
        val connected = (audio + peripheral).filter { it.isNotBlank() }.distinct().take(6)
        val paired = if (includePaired) adapter.bondedDevices.mapNotNull { it.name }.distinct().take(8) else emptyList()
        buildString {
            append("Bluetooth: ${if (adapter.isEnabled) "on" else "off"}")
            if (connected.isNotEmpty()) append("; connected devices reported to Enyo: ${connected.joinToString { it.take(60) }}")
            else if (adapter.isEnabled) append("; no connected audio or GATT devices reported to Enyo")
            if (includePaired) append("; paired devices: ${paired.joinToString { it.take(60) }.ifEmpty { "none reported" }}")
        }
    }.getOrDefault("Bluetooth: unavailable")
}

private fun calendarContext(context: Context, now: Long): String {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
        return "Calendar: permission unavailable"
    }
    val uri = Instances.CONTENT_URI.buildUpon().apply {
        ContentUris.appendId(this, now)
        ContentUris.appendId(this, now + TimeUnit.DAYS.toMillis(7))
    }.build()
    val projection = arrayOf(Instances.TITLE, Instances.BEGIN, Instances.ALL_DAY)
    val date = DateFormat.getDateFormat(context)
    val time = DateFormat.getTimeFormat(context)
    return runCatching {
        val events: List<String> = context.contentResolver.query(uri, projection, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC")
            ?.use { cursor ->
                buildList<String> {
                    while (size < 6 && cursor.moveToNext()) {
                        val title = cursor.getString(0)?.take(100) ?: context.getString(R.string.calendar_untitled)
                        val begins = cursor.getLong(1)
                        val whenText = if (cursor.getInt(2) != 0) {
                            "${Instant.ofEpochMilli(begins).atOffset(ZoneOffset.UTC).toLocalDate()}, all day"
                        } else "${date.format(Date(begins))} ${time.format(Date(begins))}"
                        add("$whenText: $title")
                    }
                }
            } ?: return "Calendar: unavailable"
        if (events.isEmpty()) "Calendar: no visible events in the next seven days"
        else "Calendar for the next seven days:\n${events.joinToString("\n")}"
    }.getOrDefault("Calendar: unavailable")
}

private fun weatherContext(context: Context): String {
    val reading = latestWeather.takeIf { context.weatherAccess() != WeatherAccess.None }
        ?: return "Weather: no widget reading is available"
    val age = TimeUnit.MILLISECONDS.toMinutes((SystemClock.elapsedRealtime() - reading.fetchedAt).coerceAtLeast(0))
    return "Weather widget reading: ${reading.celsius} °C, ${context.getString(reading.conditions.label)}, recorded $age minutes ago" +
        if (reading.estimated) ", estimated location" else ""
}

private val messagesWith = Regex("""\b(?:messages?|chats?|texts?|wrote|said|sagte|schrieb|geschrieben|nachrichten?)\b""", RegexOption.IGNORE_CASE)

/**
 * Phone data for models with a larger context: the local time always, readings whose topic the
 * request mentions, and recent messages with people it names. Nothing private while the phone is locked.
 */
internal fun assistantPhoneContext(context: Context, query: String, now: Long = System.currentTimeMillis()): String {
    val date = Date(now)
    val localTime = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    val clock = "Current device local time: ${DateFormat.getLongDateFormat(context).format(date)}, ${DateFormat.getTimeFormat(context).format(date)}; " +
        "time zone: ${localTime.zone.id}, UTC${localTime.format(DateTimeFormatter.ofPattern("xxx"))}."
    if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return clock
    return buildList {
        add(clock)
        if (batteryQuery.containsMatchIn(query)) {
            val reading = readBattery(context)
            add(if (reading == null) "Battery: unavailable" else
                "Battery: ${(reading.level * 100).roundToInt()}%, ${if (reading.charging) "charging or full" else "not charging"}")
        }
        if (wifiQuery.containsMatchIn(query)) add(wifiContext(context))
        if (bluetoothQuery.containsMatchIn(query)) add(bluetoothContext(context, pairedQuery.containsMatchIn(query)))
        if (calendarQuery.containsMatchIn(query)) add(calendarContext(context, now))
        if (weatherQuery.containsMatchIn(query)) add(weatherContext(context))
        add(conversationContext(context, query))
    }.filter { it.isNotBlank() }.joinToString("\n").take(2400)
}

/** The latest messages of up to two chats whose person or group the request names. */
/** Up to two chats whose person or group the request names. */
internal fun mentionedThreads(context: Context, query: String): List<ThreadSummary> {
    val lower = query.lowercase()
    fun mentions(name: String) = name.length >= 3 && Regex("""(?<![\p{L}\p{N}])${Regex.escape(name.lowercase())}(?![\p{L}\p{N}])""").containsMatchIn(lower)
    return runCatching { ArchiveData.threads(context, 60) }.getOrDefault(emptyList()).filter { thread ->
        mentions(thread.name) || mentions(thread.name.substringBefore(' '))
    }.take(2)
}

private fun conversationContext(context: Context, query: String): String {
    val named = mentionedThreads(context, query)
    if (named.isEmpty()) return ""
    val time = DateFormat.getTimeFormat(context)
    val day = DateFormat.getDateFormat(context)
    val wanted = if (messagesWith.containsMatchIn(query)) 10 else 6
    return named.joinToString("\n") { thread ->
        val lines = runCatching { ArchiveData.messages(context, thread.ref) }.getOrDefault(emptyList()).takeLast(wanted).joinToString("\n") { message ->
            val who = if (message.outgoing) "Me" else message.sender?.take(60) ?: thread.name.take(60)
            "- ${day.format(Date(message.postedAt))} ${time.format(Date(message.postedAt))}, $who: ${message.text.take(200)}"
        }
        if (lines.isBlank()) "" else "Recent messages in the chat \"${thread.name.take(60)}\":\n$lines"
    }
}

/** The phone data behind an answer, as cards under it; the same topics and limits as the context. */
internal fun assistantCards(context: Context, query: String, rich: Boolean, now: Long = System.currentTimeMillis()): List<AssistantCard> {
    val clock = isDirectClockQuery(query) || timeQuery.containsMatchIn(query)
    val time = if (clock) AssistantCard.Clock(DateFormat.getLongDateFormat(context).format(Date(now)), DateFormat.getTimeFormat(context).format(Date(now)),
        ZoneId.systemDefault().id) else null
    if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return listOfNotNull(time)
    val topical = rich || deviceDetail.containsMatchIn(query) || directDeviceQuestion.matches(query.trim())
    return buildList {
        time?.let(::add)
        if (topical && weatherQuery.containsMatchIn(query)) latestWeather.takeIf { context.weatherAccess() != WeatherAccess.None }?.let { reading ->
            add(AssistantCard.Weather(reading.celsius, context.getString(reading.conditions.label)))
        }
        if (topical && batteryQuery.containsMatchIn(query)) readBattery(context)?.let { add(AssistantCard.Battery((it.level * 100).roundToInt(), it.charging)) }
        if (topical && calendarQuery.containsMatchIn(query) &&
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            val events = calendarContext(context, now).lines().drop(1).filter { it.contains(": ") }.take(4)
            add(AssistantCard.Calendar(events))
        }
        val network = listOfNotNull(
            wifiContext(context).takeIf { topical && wifiQuery.containsMatchIn(query) },
            bluetoothContext(context, pairedQuery.containsMatchIn(query)).takeIf { topical && bluetoothQuery.containsMatchIn(query) }
        )
        if (network.isNotEmpty()) add(AssistantCard.Network(network))
        if (rich) mentionedThreads(context, query).forEach { thread ->
            add(AssistantCard.Chat(thread.ref, thread.name.take(80), thread.preview?.take(160)))
        }
    }
}
