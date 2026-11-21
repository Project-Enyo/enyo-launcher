package space.koyu.enyo

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.provider.CalendarContract
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.Collator
import java.util.Date

internal fun Context.launchSafely(intent: Intent): Boolean =
    runCatching { startActivity(intent); true }.getOrDefault(false)

@Composable
internal fun foregroundCycle(): Int {
    val owner = LocalLifecycleOwner.current
    var count by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) count++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return count
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun LauncherScreen(
    openPanel: () -> Unit,
    homeSequence: Int,
    setTyping: (((KeyEvent) -> Boolean)?) -> Unit
) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val foreground = foregroundCycle()
    var packageCycle by remember { mutableIntStateOf(0) }
    val launcher = remember(context) { context.getSystemService(LauncherApps::class.java) }
    DisposableEffect(launcher) {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(name: String, user: android.os.UserHandle) { packageCycle++ }
            override fun onPackageAdded(name: String, user: android.os.UserHandle) { packageCycle++ }
            override fun onPackageChanged(name: String, user: android.os.UserHandle) { packageCycle++ }
            override fun onPackagesAvailable(names: Array<String>, user: android.os.UserHandle, replacing: Boolean) { packageCycle++ }
            override fun onPackagesUnavailable(names: Array<String>, user: android.os.UserHandle, replacing: Boolean) { packageCycle++ }
        }
        launcher.registerCallback(callback)
        onDispose { launcher.unregisterCallback(callback) }
    }
    val apps by produceState(emptyList<LauncherActivityInfo>(), foreground, packageCycle) {
        value = withContext(Dispatchers.IO) {
            val collator = Collator.getInstance()
            runCatching {
                launcher.getActivityList(null, Process.myUserHandle())
                    .filter { it.componentName.packageName != context.packageName }
                    .sortedWith(compareBy(collator) { it.label.toString() })
            }.getOrDefault(emptyList())
        }
    }
    val pages = listOf(R.string.today, R.string.apps, R.string.cards, R.string.inbox, R.string.quick)
    val pager = rememberPagerState(initialPage = 2) { pages.size }
    val scope = rememberCoroutineScope()
    var surface by rememberSaveable { mutableIntStateOf(0) }
    var query by remember { mutableStateOf(TextFieldValue()) }
    LaunchedEffect(homeSequence) {
        if (homeSequence > 0) {
            surface = 0
            pager.scrollToPage(2)
        }
    }
    val searchFocus = remember { FocusRequester() }
    val quickFocus = remember { FocusRequester() }
    val cardsFocus = remember { FocusRequester() }
    DisposableEffect(surface) {
        setTyping { event ->
            val code = event.unicodeChar
            when {
                event.keyCode == KeyEvent.KEYCODE_ESCAPE -> {
                    surface = 0
                    scope.launch { pager.scrollToPage(2) }
                    true
                }
                surface != 0 -> false
                code in '1'.code..'5'.code -> {
                    scope.launch {
                        val page = code - '1'.code
                        pager.animateScrollToPage(page)
                        if (page == 4) quickFocus.requestFocus()
                        if (page == 2) runCatching { cardsFocus.requestFocus() }
                    }
                    true
                }
                code > 0 && Character.isLetter(code) -> {
                    val letterText = String(Character.toChars(code))
                    query = TextFieldValue(letterText, TextRange(letterText.length))
                    surface = 1
                    true
                }
                else -> false
            }
        }
        onDispose { setTyping(null) }
    }
    val recentStore = remember(context) { RecentStore.get(context) }
    fun openApp(app: LauncherActivityInfo) {
        if (app.user != Process.myUserHandle()) return
        recentStore.prepare(app.componentName)
        val launched = runCatching { launcher.startMainActivity(app.componentName, app.user, null, null) }.isSuccess ||
            context.launchSafely(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.componentName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (launched) recentStore.launched(app.componentName)
        else recentStore.capture = null
    }

    SearchOnboarding()
    BackHandler(surface != 0 || pager.currentPage != 2) {
        if (surface != 0) surface = 0 else scope.launch { pager.animateScrollToPage(2) }
    }
    when (surface) {
        1 -> SearchScreen(query, { query = it }, apps, ::openApp, { surface = 0 }, { surface = 2 }, packageCycle)
        2 -> PreferencesScreen({ surface = 0 }, packageCycle)
        else -> Column(
            Modifier.fillMaxSize().background(tokens.background)
                .safeDrawingPadding().windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(bottom = tokens.bottomClearance)

        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = tokens.margin), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { query = TextFieldValue(); surface = 1 }, modifier = Modifier.focusRequester(searchFocus)) { Text(stringResource(R.string.search), color = tokens.ink) }
                TextButton(
                    onClick = { surface = 2 },
                    modifier = Modifier.focusProperties {
                        if (pager.currentPage == 4) next = quickFocus
                    }
                ) { Text(stringResource(R.string.preferences), color = tokens.ink) }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> TodayPage(foreground)
                    1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(tokens.margin)) {
                        items(apps, key = { it.componentName.flattenToString() }) { app ->
                            TextButton(onClick = { openApp(app) }) { Text(app.label.toString(), color = tokens.ink) }
                        }
                    }
                    2 -> CardsPage(apps, ::openApp, cardsFocus)
                    3 -> InboxPage()
                    4 -> QuickPage(openPanel, quickFocus)
                }
            }
            Canvas(Modifier.fillMaxWidth().height(tokens.progress)) {
                val step = size.width / pages.size
                val x = (pager.currentPage + pager.currentPageOffsetFraction + 0.5f) * step
                drawCircle(tokens.accent, radius = size.height / 2f, center = Offset(x, size.height / 2f))
            }
            Row(Modifier.fillMaxWidth()) {
                pages.forEachIndexed { index, label ->
                    TextButton(
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues()
                    ) {
                        Text(stringResource(label), style = tokens.label.copy(color = tokens.ink), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageMessage(label: Int) {
    val tokens = LocalTokens.current
    Box(Modifier.fillMaxSize().padding(tokens.margin), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(stringResource(label), style = tokens.secondary)
    }
}

@Composable
private fun TodayPage(foreground: Int) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    val calendarAllowed = remember(foreground) {
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }
    val event by produceState<String?>(null, foreground, calendarAllowed, now.time / 60000) {
        value = if (!calendarAllowed) null else withContext(Dispatchers.IO) {
            runCatching {
                CalendarContract.Instances.query(
                    context.contentResolver, arrayOf(CalendarContract.Instances.TITLE),
                    now.time, now.time + 30L * 24 * 60 * 60 * 1000
                )?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(tokens.margin)) {
        item { Text(DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(now), style = tokens.value) }
        item { Text(DateFormat.getDateInstance(DateFormat.FULL, locale).format(now), style = tokens.state) }
        if (calendarAllowed) item { Text(event ?: stringResource(R.string.calendar_empty), style = tokens.secondary) }
    }
}

@Composable
private fun InboxPage() {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val entries by EnyoNotificationService.entries.collectAsState()
    if (entries.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(tokens.margin)) {
            Text(stringResource(R.string.inbox_empty), style = tokens.secondary)
            TextButton(onClick = { context.launchSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                Text(stringResource(R.string.notification_access), color = tokens.ink)
            }
        }
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(tokens.margin)) {
        entries.groupBy { it.packageName }.forEach { (source, notifications) ->
            item {
                val label = runCatching { context.packageManager.getApplicationInfo(source, 0).loadLabel(context.packageManager) }
                    .getOrDefault(source)
                Text(label.toString(), style = tokens.state)
            }
            items(notifications, key = { it.key }) { entry ->
                val notification = entry.notification
                Column(Modifier.padding(vertical = tokens.gap)) {
                    val extras = notification.extras
                    Text(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(), style = tokens.state)
                    Text(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(), style = tokens.secondary)
                    notification.actions.orEmpty().firstOrNull { it.remoteInputs.isNullOrEmpty() }?.let { action ->
                        TextButton(onClick = { runCatching { action.actionIntent.send() } }) {
                            Text(action.title.toString(), color = tokens.ink)
                        }
                    }
                }
            }
        }
    }
}
