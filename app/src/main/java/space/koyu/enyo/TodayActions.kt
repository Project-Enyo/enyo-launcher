package space.koyu.enyo

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

private const val todayAction = "today_widget_action"
private const val todayKindExtra = "today_widget_kind"

/** A Today widget whose actions Enyo was asked to show, set when the person taps the widget. */
internal val todayActionRequest = MutableStateFlow<TodayWidget?>(null)

internal fun Context.todayActionPendingIntent(id: Int, kind: TodayWidget): PendingIntent = PendingIntent.getActivity(
    this, id * TodayWidget.entries.size + kind.ordinal,
    Intent(this, EnyoActivity::class.java).setAction(todayAction)
        .putExtra(todayKindExtra, kind.name).putExtra(weatherTokenExtra, weatherWidgetToken()),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

/** The widget a tap intent names, only for intents Enyo's own widgets created. */
internal fun Context.todayActionKind(intent: Intent?): TodayWidget? {
    if (intent?.action != todayAction || intent.getStringExtra(weatherTokenExtra) != weatherWidgetToken()) return null
    return TodayWidget.entries.firstOrNull { it.name == intent.getStringExtra(todayKindExtra) }
}

private data class TodayEvent(val id: Long, val title: String, val begin: Long, val end: Long)

private fun nextEvent(context: Context): TodayEvent? {
    if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
    val now = System.currentTimeMillis()
    return runCatching {
        CalendarContract.Instances.query(
            context.contentResolver,
            arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END),
            now, now + 30L * DateUtils.DAY_IN_MILLIS
        )?.use { cursor ->
            var best: TodayEvent? = null
            while (cursor.moveToNext()) {
                val event = TodayEvent(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getLong(2), cursor.getLong(3))
                if (event.end >= now && (best == null || event.begin < best.begin)) best = event
            }
            best
        }
    }.getOrNull()
}

private data class TodayActionItem(val label: String, val glyph: Glyph, val summary: String? = null, val intents: List<Intent>)

/** The actions behind a tapped Today widget, each opening the matching Android screen or app. */
@Composable
internal fun TodayActionSheet(kind: TodayWidget, openInternet: () -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    var failed by remember { mutableStateOf(false) }
    val event by produceState<TodayEvent?>(null, kind) {
        if (kind == TodayWidget.Calendar) value = withContext(Dispatchers.IO) { nextEvent(context) }
    }
    val items = when (kind) {
        TodayWidget.Clock -> listOf(
            TodayActionItem(stringResource(R.string.today_alarms), Glyph.Alarm, intents = listOf(Intent(AlarmClock.ACTION_SHOW_ALARMS))),
            TodayActionItem(stringResource(R.string.today_new_alarm), Glyph.Plus, intents = listOf(Intent(AlarmClock.ACTION_SET_ALARM))),
            TodayActionItem(stringResource(R.string.today_timers), Glyph.Clock, intents = listOf(Intent(AlarmClock.ACTION_SHOW_TIMERS), Intent(AlarmClock.ACTION_SET_TIMER))),
            TodayActionItem(stringResource(R.string.today_date_settings), Glyph.Settings, intents = listOf(Intent(Settings.ACTION_DATE_SETTINGS)))
        )
        TodayWidget.Battery -> listOf(
            TodayActionItem(stringResource(R.string.today_battery_usage), Glyph.Battery, intents = listOf(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))),
            TodayActionItem(stringResource(R.string.today_battery_saver), Glyph.Settings, intents = listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)))
        )
        TodayWidget.Network -> listOf(
            TodayActionItem(stringResource(R.string.today_internet_panel), Glyph.Wifi, intents = emptyList()),
            TodayActionItem(stringResource(R.string.today_wifi_settings), Glyph.Settings, intents = listOf(Intent(Settings.ACTION_WIFI_SETTINGS))),
            TodayActionItem(stringResource(R.string.today_mobile_settings), Glyph.World, intents = listOf(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS)))
        )
        TodayWidget.Calendar -> listOfNotNull(
            event?.let {
                TodayActionItem(
                    it.title.ifBlank { stringResource(R.string.calendar_untitled) }, Glyph.Calendar,
                    summary = DateUtils.formatDateRange(context, it.begin, it.end, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL),
                    intents = listOf(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it.id))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it.begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.end))
                )
            },
            TodayActionItem(stringResource(R.string.today_open_calendar), Glyph.Calendar, intents = listOf(
                Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(System.currentTimeMillis().toString()).build())
            )),
            TodayActionItem(stringResource(R.string.today_new_event), Glyph.Plus, intents = listOf(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)))
        )
        TodayWidget.Weather -> emptyList()
    }
    EnyoSheet(stringResource(kind.title), close) {
        ListCard {
            items.forEach { item ->
                SettingsAction(item.label, item.glyph, summary = item.summary) {
                    if (item.intents.isEmpty()) { close(); openInternet() }
                    else if (item.intents.any { context.launchSafely(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) close()
                    else failed = true
                }
            }
            if (failed) ListNote(stringResource(R.string.action_unavailable))
        }
    }
}
