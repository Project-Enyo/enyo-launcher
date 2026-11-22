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
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private val batteryQuery = Regex("""\b(?:battery|charging|akku|batterie|ladestand)\b""", RegexOption.IGNORE_CASE)
private val wifiQuery = Regex("""\b(?:wi[ -]?fi|wlan|network status|internet connection|netzwerkstatus)\b""", RegexOption.IGNORE_CASE)
private val bluetoothQuery = Regex("""\b(?:bluetooth|connected devices|verbundene geräte)\b""", RegexOption.IGNORE_CASE)
private val pairedQuery = Regex("""\b(?:paired|bonded|gekoppelt\w*)\b""", RegexOption.IGNORE_CASE)
private val timeQuery = Regex(
    """\b(?:current time|what time is it|local time|current date|today'?s date|what day is it|wie spät|uhrzeit|heutiges datum|welcher tag)\b""",
    RegexOption.IGNORE_CASE
)
private val calendarQuery = Regex(
    """\b(?:calendar|agenda|events?|appointments?|meetings?|schedule|kalender|termin\w*|veranstaltungen?)\b""",
    RegexOption.IGNORE_CASE
)
private val weatherQuery = Regex("""\b(?:weather|forecast|temperature|wetter|temperatur|vorhersage)\b""", RegexOption.IGNORE_CASE)

internal fun assistantDeviceContext(context: Context, query: String): String {
    if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return ""
    val now = System.currentTimeMillis()
    return buildList {
        if (batteryQuery.containsMatchIn(query)) {
            val reading = readBattery(context)
            add(if (reading == null) "Battery: unavailable" else
                "Battery: ${(reading.level * 100).roundToInt()}%, ${if (reading.charging) "charging or full" else "not charging"}")
        }
        if (wifiQuery.containsMatchIn(query)) add(wifiContext(context))
        if (bluetoothQuery.containsMatchIn(query)) add(bluetoothContext(context, pairedQuery.containsMatchIn(query)))
        if (timeQuery.containsMatchIn(query)) {
            val date = Date(now)
            add("Current local date and time: ${DateFormat.getLongDateFormat(context).format(date)}, ${DateFormat.getTimeFormat(context).format(date)} (${java.util.TimeZone.getDefault().id})")
        }
        if (calendarQuery.containsMatchIn(query)) add(calendarContext(context, now))
        if (weatherQuery.containsMatchIn(query)) add(weatherContext(context))
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
