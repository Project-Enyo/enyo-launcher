package space.koyu.enyo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.net.HttpURLConnection
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val weatherIntervalMillis = 15 * 60 * 1000L
private const val locationTimeoutMillis = 7_000L
private const val weatherTimeoutMillis = 8_000
private val weatherPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private enum class WeatherAccess { None, Approximate, Precise }

private fun Context.weatherAccess() = when {
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> WeatherAccess.Precise
    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> WeatherAccess.Approximate
    else -> WeatherAccess.None
}

private enum class Conditions(val label: Int, val glyph: Glyph) {
    Clear(R.string.weather_clear, Glyph.Today),
    Cloudy(R.string.weather_cloudy, Glyph.Cloud),
    Fog(R.string.weather_fog, Glyph.CloudFog),
    Rain(R.string.weather_rain, Glyph.CloudRain),
    Snow(R.string.weather_snow, Glyph.CloudSnow),
    Storm(R.string.weather_storm, Glyph.CloudStorm),
    Unknown(R.string.weather_conditions_unknown, Glyph.Cloud)
}

private data class WeatherReading(val celsius: Int, val conditions: Conditions, val fetchedAt: Long)

private class WeatherState : ViewModel() {
    var reading by mutableStateOf<WeatherReading?>(null)
        private set
    var status by mutableIntStateOf(R.string.weather_loading)
        private set
    private var access = WeatherAccess.None
    private var lastRefresh = 0L

    suspend fun observe(context: Context, permission: WeatherAccess, refreshAt: Long) {
        if (access != permission) reading = null
        access = permission
        if (permission == WeatherAccess.None) return
        val refresh = refreshAt > lastRefresh
        if (refresh) lastRefresh = refreshAt
        val manager = context.getSystemService(LocationManager::class.java)
        if (!refresh && manager.isLocationEnabled) {
            val remaining = reading?.let { weatherIntervalMillis - (SystemClock.elapsedRealtime() - it.fetchedAt) }
            if (remaining != null && remaining > 0) delay(remaining)
        }
        while (context.weatherAccess() == permission) {
            status = R.string.weather_loading
            status = if (!manager.isLocationEnabled) R.string.weather_location_disabled else {
                val fix = currentWeatherLocation(context, manager, permission)
                if (fix == null) R.string.weather_location_unavailable else {
                    val result = loadWeather(context, fix, permission)
                    if (result == null) R.string.weather_unavailable else {
                        reading = result
                        R.string.weather_loading
                    }
                }
            }
            if (status != R.string.weather_loading) reading = null
            delay(weatherIntervalMillis)
        }
    }
}

@Composable
internal fun WeatherWidget(foreground: Int, visible: Boolean) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val model = viewModel<WeatherState> { WeatherState() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val locationManager = remember(context) { context.getSystemService(LocationManager::class.java) }
    var locationRevision by remember { mutableIntStateOf(0) }
    if (visible) DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { locationRevision++ }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(LocationManager.MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    var permissionResult by remember { mutableIntStateOf(0) }
    var refreshAt by remember { mutableLongStateOf(0) }
    var explained by rememberSaveable { mutableStateOf(false) }
    var previouslyRequested by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionResult++
    }
    val access = remember(context, foreground, permissionResult, lifecycle) { context.weatherAccess() }
    val allowed = access != WeatherAccess.None
    LaunchedEffect(visible, lifecycle, access, refreshAt, locationRevision) {
        if (!allowed || (visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED))) {
            model.observe(context.applicationContext, access, refreshAt)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.widget_weather), Modifier.weight(1f), style = tokens.label, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        val iconLabel = stringResource(if (allowed) R.string.weather_location_on else R.string.weather_location_off)
        IconButton(onClick = {
            when {
                !allowed -> explained = true
                !locationManager.isLocationEnabled ->
                    context.launchSafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                else -> refreshAt = SystemClock.elapsedRealtime()
            }
        }) {
            EnyoGlyph(if (allowed) Glyph.Location else Glyph.LocationOff, description = iconLabel,
                color = if (allowed) tokens.accent else tokens.quiet)
        }
    }
    val reading = model.reading
    if (allowed && reading != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
            EnyoGlyph(reading.conditions.glyph, Modifier.size(tokens.appGlyphSize), color = tokens.accent)
            Text(stringResource(R.string.weather_celsius, reading.celsius), style = tokens.value, maxLines = 1)
        }
        Text(stringResource(reading.conditions.label), style = tokens.secondary)
    } else {
        Text(stringResource(if (allowed) model.status else R.string.weather_location_prompt), style = tokens.secondary)
    }
    if (explained) EnyoSheet(stringResource(R.string.widget_weather), { explained = false }) {
        Text(stringResource(R.string.weather_location_explanation), style = tokens.secondary)
        TextButton(onClick = {
            explained = false
            previouslyRequested = true
            request.launch(weatherPermissions)
        }) { Text(stringResource(R.string.weather_allow_location)) }
        if (previouslyRequested) TextButton(onClick = {
            explained = false
            context.launchSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }) { Text(stringResource(R.string.app_info)) }
    }
}

private suspend fun currentWeatherLocation(context: Context, manager: LocationManager, access: WeatherAccess): Location? {
    val available = manager.getProviders(true)
    val candidates = buildList {
        if (access == WeatherAccess.Precise) add(LocationManager.GPS_PROVIDER)
        add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }
    for (provider in candidates) {
        if (provider !in available) continue
        val fix = try {
            withTimeoutOrNull(locationTimeoutMillis) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val cancellation = CancellationSignal()
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    manager.getCurrentLocation(provider, cancellation, context.mainExecutor) { continuation.resume(it) }
                }
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            return null
        }
        if (fix != null) return fix
    }
    return null
}

private suspend fun loadWeather(context: Context, location: Location, permission: WeatherAccess): WeatherReading? =
    withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (context.weatherAccess() != permission) return@withContext null
        val coordinates = String.format(Locale.ROOT, "%.2f,%.2f", location.latitude, location.longitude)
        val connection = java.net.URL("https://wttr.in/$coordinates?format=j1").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = weatherTimeoutMillis
            connection.readTimeout = weatherTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val current = JSONObject(body).getJSONArray("current_condition").getJSONObject(0)
            WeatherReading(current.getInt("temp_C"), conditions(current.getInt("weatherCode")), SystemClock.elapsedRealtime())
        } catch (error: CancellationException) {
            throw error
        } catch (_: java.io.IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        } finally {
            connection.disconnect()
        }
    }

private fun conditions(code: Int): Conditions = when (code) {
    113 -> Conditions.Clear
    116, 119, 122 -> Conditions.Cloudy
    143, 248, 260 -> Conditions.Fog
    200, 386, 389, 392, 395 -> Conditions.Storm
    179, 182, 185, 227, 230, 281, 284, 311, 314, 317, 320,
    323, 326, 329, 332, 335, 338, 350, 362, 365, 368, 371, 374, 377 -> Conditions.Snow
    176, 263, 266, 293, 296, 299, 302, 305, 308, 353, 356, 359 -> Conditions.Rain
    else -> Conditions.Unknown
}
