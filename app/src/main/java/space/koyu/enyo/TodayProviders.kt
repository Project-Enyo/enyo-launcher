package space.koyu.enyo

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.provider.CalendarContract
import android.view.View
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import java.text.NumberFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

abstract class TodayProvider : AppWidgetProvider() {
    internal abstract val kind: TodayWidget

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (kind != TodayWidget.Calendar) {
            ids.forEach { renderTodayWidget(context, manager, it, kind) }
        } else {
            val result = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val event = nextTodayEvent(context, System.currentTimeMillis())
                    ids.forEach { renderTodayWidget(context, manager, it, kind, event) }
                } finally {
                    result.finish()
                }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }
}

class ClockWidget : TodayProvider() { override val kind get() = TodayWidget.Clock }
class CalendarWidget : TodayProvider() { override val kind get() = TodayWidget.Calendar }
class WeatherAppWidget : TodayProvider() { override val kind get() = TodayWidget.Weather }
class BatteryWidget : TodayProvider() { override val kind get() = TodayWidget.Battery }
class InternetWidget : TodayProvider() { override val kind get() = TodayWidget.Network }

internal fun nextTodayEvent(context: Context, start: Long): String? {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
    return runCatching {
        CalendarContract.Instances.query(
            context.contentResolver, arrayOf(CalendarContract.Instances.TITLE),
            start, start + 30L * 24 * 60 * 60 * 1000
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
}

internal fun publishTodayWidget(context: Context, kind: TodayWidget, calendarEvent: String? = null) {
    val manager = AppWidgetManager.getInstance(context)
    manager.getAppWidgetIds(kind.component(context)).forEach {
        renderTodayWidget(context, manager, it, kind, calendarEvent)
    }
}

private fun renderTodayWidget(
    context: Context,
    manager: AppWidgetManager,
    id: Int,
    kind: TodayWidget,
    event: String? = null
) {
    val options = manager.getAppWidgetOptions(id)
    val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
    val expandedBattery = kind == TodayWidget.Battery && width >= 250
    val label = context.getString(kind.title)
    val locale = context.resources.configuration.locales[0]
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val views = RemoteViews(context.packageName, if (kind == TodayWidget.Clock) R.layout.today_widget_clock else R.layout.today_widget)
    if (kind != TodayWidget.Clock) {
        views.setTextViewText(R.id.widget_title, label)
        views.setViewVisibility(R.id.widget_detail, View.GONE)
        views.setViewVisibility(R.id.widget_icon, View.GONE)
        fun value(text: CharSequence) = views.setTextViewText(R.id.widget_value, text)
        fun detail(text: CharSequence) {
            views.setTextViewText(R.id.widget_detail, text)
            views.setViewVisibility(R.id.widget_detail, View.VISIBLE)
        }
        fun icon(glyph: Glyph, description: Int? = null) {
            views.setImageViewResource(R.id.widget_icon, glyph.drawable)
            views.setViewVisibility(R.id.widget_icon, View.VISIBLE)
            views.setContentDescription(R.id.widget_icon, description?.let(context::getString))
        }
        when (kind) {
            TodayWidget.Clock -> Unit
            TodayWidget.Calendar -> value(event ?: context.getString(
                if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
                    R.string.calendar_empty else R.string.widget_calendar_unavailable
            ))
            TodayWidget.Battery -> {
                views.setTextViewTextSize(R.id.widget_value, TypedValue.COMPLEX_UNIT_SP, 28f)
                val state = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = state?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = state?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) {
                    value(NumberFormat.getPercentInstance(locale).format(level.toDouble() / scale))
                    val power = state?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    if (power == BatteryManager.BATTERY_STATUS_CHARGING || power == BatteryManager.BATTERY_STATUS_FULL) {
                        detail(context.getString(R.string.widget_charging))
                    }
                    if (expandedBattery) {
                        icon(Glyph.Battery)
                        views.setImageViewBitmap(R.id.widget_icon, batteryWidgetImage(context, dark, level.toFloat() / scale))
                        views.setViewLayoutWidth(R.id.widget_icon, 92f, android.util.TypedValue.COMPLEX_UNIT_DIP)
                        views.setViewLayoutHeight(R.id.widget_icon, 46f, android.util.TypedValue.COMPLEX_UNIT_DIP)
                    }
                } else value(context.getString(R.string.torch_unavailable))
            }
            TodayWidget.Network -> {
                val network = context.getSystemService(ConnectivityManager::class.java)
                val capabilities = network.getNetworkCapabilities(network.activeNetwork)
                value(context.getString(when {
                    capabilities == null -> R.string.disconnected
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.string.wifi_connected
                    else -> R.string.connected
                }))
            }
            TodayWidget.Weather -> {
                val hasAccess = context.weatherAccess() != WeatherAccess.None
                val reading = latestWeather.takeIf { hasAccess }
                icon(if (hasAccess) Glyph.Location else Glyph.LocationOff,
                    if (hasAccess) R.string.weather_location_on else R.string.weather_location_off)
                if (reading == null) {
                    views.setTextViewTextSize(R.id.widget_value, TypedValue.COMPLEX_UNIT_SP, 12f)
                    value(context.getString(if (hasAccess) latestWeatherStatus else R.string.weather_location_prompt))
                } else {
                    views.setTextViewTextSize(R.id.widget_value, TypedValue.COMPLEX_UNIT_SP, 28f)
                    value(context.getString(R.string.weather_celsius, reading.celsius))
                    detail(context.getString(if (reading.estimated) R.string.weather_estimated_conditions else reading.conditions.label))
                }
                val open = PendingIntent.getActivity(
                    context, id, context.weatherWidgetIntent(),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                views.setOnClickPendingIntent(R.id.widget_icon, open)
                views.setOnClickPendingIntent(R.id.widget_content, null)
            }
        }
    }
    manager.updateAppWidget(id, views)
}

private fun batteryWidgetImage(context: Context, dark: Boolean, level: Float): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val bitmap = android.graphics.Bitmap.createBitmap(
        (92 * density).toInt(), (46 * density).toInt(), android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(bitmap)
    val tokens = Tokens(dark, CardSize.CompactHeight)
    val w = bitmap.width / 24f
    val h = bitmap.height / 12f
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = tokens.accentContainer.toArgb()
    }
    canvas.drawRoundRect(6 * w, 3 * h, (6 + 11 * level.coerceIn(0f, 1f)) * w, 9 * h, h, h, paint)
    context.getDrawable(Glyph.Battery.drawable)?.apply {
        setTint(tokens.accent.toArgb())
        setBounds(0, 0, bitmap.width, bitmap.height)
        draw(canvas)
    }
    return bitmap
}
