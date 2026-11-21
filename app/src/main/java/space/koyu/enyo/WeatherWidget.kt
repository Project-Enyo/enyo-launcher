package space.koyu.enyo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
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
private const val gpsTimeoutMillis = 15_000L
private const val locationTimeoutMillis = 4_000L
private const val weatherTimeoutMillis = 15_000
private val weatherPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

internal enum class WeatherAccess { None, Approximate, Precise }

internal fun Context.weatherAccess() = when {
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> WeatherAccess.Precise
    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> WeatherAccess.Approximate
    else -> WeatherAccess.None
}

internal enum class Conditions(val label: Int) {
    Clear(R.string.weather_clear),
    Cloudy(R.string.weather_cloudy),
    Fog(R.string.weather_fog),
    Rain(R.string.weather_rain),
    Snow(R.string.weather_snow),
    Storm(R.string.weather_storm),
    Unknown(R.string.weather_conditions_unknown)
}

internal data class WeatherReading(
    val celsius: Int,
    val conditions: Conditions,
    val fetchedAt: Long,
    val estimated: Boolean
)

@Volatile internal var latestWeather: WeatherReading? = null
    private set
@Volatile internal var latestWeatherStatus = R.string.weather_loading
    private set

private const val weatherAction = "weather_widget_refresh"
private const val weatherTokenExtra = "weather_widget_token"

@Synchronized
private fun Context.weatherWidgetToken(): String {
    val preferences = getSharedPreferences("weather_widget_action", Context.MODE_PRIVATE)
    return preferences.getString(weatherTokenExtra, null) ?: java.util.UUID.randomUUID().toString().also {
        preferences.edit().putString(weatherTokenExtra, it).apply()
    }
}

internal fun Context.weatherWidgetIntent() = Intent(this, EnyoActivity::class.java)
    .setAction(weatherAction)
    .putExtra(weatherTokenExtra, weatherWidgetToken())

internal fun Context.isWeatherWidgetIntent(intent: Intent): Boolean =
    intent.action == weatherAction && intent.getStringExtra(weatherTokenExtra) == weatherWidgetToken()

private class WeatherState : ViewModel() {
    private var reading: WeatherReading? = null
    private var access = WeatherAccess.None
    private var lastRefresh = 0L
    private var lastWidgetTap = 0L

    fun consumeWidgetTap(tap: Long): Boolean {
        if (tap <= lastWidgetTap) return false
        lastWidgetTap = tap
        return true
    }

    suspend fun observe(context: Context, permission: WeatherAccess, refreshAt: Long) {
        if (access != permission) {
            reading = latestWeather.takeIf { access == WeatherAccess.None && permission != WeatherAccess.None }
            access = permission
        }
        if (permission == WeatherAccess.None) {
            if (latestWeather != null) {
                latestWeather = null
                publishTodayWidget(context, TodayWidget.Weather)
            }
            return
        }
        val refresh = refreshAt > lastRefresh
        if (refresh) lastRefresh = refreshAt
        val manager = context.getSystemService(LocationManager::class.java)
        if (!refresh && manager.isLocationEnabled) {
            val remaining = reading?.let { weatherIntervalMillis - (SystemClock.elapsedRealtime() - it.fetchedAt) }
            if (remaining != null && remaining > 0) delay(remaining)
        }
        while (context.weatherAccess() == permission) {
            var status = R.string.weather_loading
            val network = context.getSystemService(ConnectivityManager::class.java)
            if (network.activeNetwork == null) {
                status = R.string.weather_network_unavailable
            } else {
                val fix = if (manager.isLocationEnabled) currentWeatherLocation(context, manager, permission) else null
                val result = fix?.let { loadWeather(context, it, permission) }
                    ?: loadWeather(context, null, permission)
                if (result == null) status = R.string.weather_unavailable else reading = result
            }
            latestWeather = reading
            latestWeatherStatus = status
            publishTodayWidget(context, TodayWidget.Weather)
            delay(weatherIntervalMillis)
        }
    }
}

@Composable
internal fun ObserveTodayWeather(foreground: Int, visible: Boolean, widgetTap: Long) {
    val context = LocalContext.current
    val model = viewModel<WeatherState> { WeatherState() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
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
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionResult++
    }
    val access = remember(context, foreground, permissionResult, lifecycle) { context.weatherAccess() }
    val allowed = access != WeatherAccess.None
    val resumed = visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    fun activate() {
        if (context.weatherAccess() == WeatherAccess.None) request.launch(weatherPermissions)
        else refreshAt = SystemClock.elapsedRealtime()
    }
    LaunchedEffect(widgetTap, resumed) {
        if (resumed && model.consumeWidgetTap(widgetTap)) activate()
    }
    val tokens = LocalTokens.current
    LaunchedEffect(resumed, tokens.accent, tokens.widgetSurface) {
        if (resumed) publishTodayWidget(context, TodayWidget.Weather)
    }
    LaunchedEffect(visible, lifecycle, access, refreshAt, locationRevision) {
        if (!allowed || (visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED))) {
            model.observe(context.applicationContext, access, refreshAt)
        }
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
            withTimeoutOrNull(if (provider == LocationManager.GPS_PROVIDER) gpsTimeoutMillis else locationTimeoutMillis) {
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

private suspend fun loadWeather(context: Context, location: Location?, permission: WeatherAccess): WeatherReading? =
    withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (context.weatherAccess() != permission) return@withContext null
        val coordinates = location?.let { String.format(Locale.ROOT, "%.2f,%.2f", it.latitude, it.longitude) }.orEmpty()
        val german = context.resources.configuration.locales[0].language == "de"
        val url = java.net.URL("https://wttr.in/$coordinates?format=j1" + if (german) "&lang=de" else "")
        val connection = try {
            url.openConnection() as HttpURLConnection
        } catch (_: java.io.IOException) {
            return@withContext null
        }
        try {
            connection.connectTimeout = weatherTimeoutMillis
            connection.readTimeout = weatherTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8_192)
                buildString {
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (length + count > 512 * 1024) return@withContext null
                        append(buffer, 0, count)
                    }
                }
            }
            val current = JSONObject(body).getJSONArray("current_condition").getJSONObject(0)
            WeatherReading(current.getInt("temp_C"), conditions(current.getInt("weatherCode")), SystemClock.elapsedRealtime(), estimated = location == null)
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
