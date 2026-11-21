package space.koyu.enyo

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
                val hiddenPackages = setOf(
                    context.packageName, "com.android.stk", "com.android.stk2",
                    "app.attestation.auditor", "app.grapheneos.info"
                )
                launcher.getActivityList(null, Process.myUserHandle())
                    .filterNot { it.componentName.packageName in hiddenPackages }
                    .sortedWith(compareBy(collator) { it.label.toString() })
            }.getOrDefault(emptyList())
        }
    }
    val transit = remember(apps) { apps.filter { it.componentName.packageName == transitPackage } }
    val library = remember(apps) {
        apps.distinctBy {
            if (it.componentName.packageName == transitPackage) transitPackage else it.componentName.flattenToString()
        }
    }
    var transitPicker by remember { mutableStateOf(false) }
    var appMenu by remember { mutableStateOf<LauncherActivityInfo?>(null) }
    val pins by remember(context) { context.pins() }.collectAsState(emptyList())
    val pinned = remember(apps, pins) { pins.mapNotNull { id -> apps.firstOrNull { it.componentName == id } } }
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

    if (transitPicker && transit.isNotEmpty() && appMenu == null) AppFolder(
        title = stringResource(R.string.transit),
        items = transit,
        close = { transitPicker = false },
        open = { app -> transitPicker = false; openApp(app) },
        actions = { appMenu = it }
    )
    appMenu?.let { app ->
        val component = app.componentName
        val pinnedApp = component in pins
        val canUninstall = app.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
        EnyoSheet(app.label.toString(), { appMenu = null }) {
            TextButton(onClick = {
                appMenu = null
                runCatching { launcher.startAppDetailsActivity(component, app.user, null, null) }.onFailure {
                    context.launchSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", component.packageName, null)))
                }
            }) { Text(stringResource(R.string.app_info)) }
            if (canUninstall) TextButton(onClick = {
                appMenu = null
                context.launchSafely(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", component.packageName, null)))
            }) { Text(stringResource(R.string.app_uninstall)) }
            TextButton(
                enabled = pinnedApp || pinned.size < dockCapacity,
                onClick = {
                    appMenu = null
                    scope.launch { context.changePin(component, !pinnedApp, apps.map { it.componentName }.toSet()) }
                }
            ) { Text(stringResource(if (pinnedApp) R.string.dock_unpin else R.string.dock_pin)) }
            if (!pinnedApp && pinned.size >= dockCapacity) Text(stringResource(R.string.dock_full), style = tokens.secondary)
        }
    }
    SearchOnboarding()
    BackHandler(surface != 0 || pager.currentPage != 2) {
        if (surface != 0) surface = 0 else scope.launch { pager.animateScrollToPage(2) }
    }
    when (surface) {
        1 -> SearchScreen(query, { query = it }, library, ::openApp, { surface = 0 }, { surface = 2 }, packageCycle, transit) { transitPicker = true }
        2 -> PreferencesScreen({ surface = 0 }, packageCycle) { surface = 3 }
        3 -> AppearanceScreen { surface = 2 }
        else -> WallpaperBackdrop { Column(
            Modifier.fillMaxSize().safeDrawingPadding().windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(bottom = tokens.bottomClearance)

        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = tokens.margin), horizontalArrangement = Arrangement.SpaceBetween) {
                val searchLabel = stringResource(R.string.search)
                val preferencesLabel = stringResource(R.string.preferences)
                IconButton(
                    onClick = { query = TextFieldValue(); surface = 1 },
                    modifier = Modifier.focusRequester(searchFocus).semantics { contentDescription = searchLabel }
                ) { EnyoGlyph(Glyph.Search) }
                IconButton(
                    onClick = { surface = 2 },
                    modifier = Modifier.focusProperties { if (pager.currentPage == 4) next = quickFocus }
                        .semantics { contentDescription = preferencesLabel }
                ) { EnyoGlyph(Glyph.Settings) }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> TodayPage(foreground)
                    1 -> AppsGrid(library) { app ->
                        val consolidated = app.componentName.packageName == transitPackage
                        AppTile(
                            label = if (consolidated) stringResource(R.string.transit) else app.label.toString(),
                            open = { if (consolidated) transitPicker = true else openApp(app) },
                            actions = if (consolidated) null else ({ appMenu = app })
                        ) { AppIcon(app) }
                    }
                    2 -> Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { CardsPage(apps, ::openApp, cardsFocus) }
                        PinnedDock(pinned, { scope.launch { pager.animateScrollToPage(1) } }, ::openApp) { appMenu = it }
                    }
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
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            EnyoGlyph(pageGlyphs[index])
                            Text(stringResource(label), style = tokens.label.copy(color = tokens.ink), maxLines = 1)
                        }
                    }
                }
            }
        } }
    }
}

private val pageGlyphs = listOf(Glyph.Today, Glyph.Apps, Glyph.Cards, Glyph.Inbox, Glyph.Quick)

@Composable
internal fun LibraryRow(app: LauncherActivityInfo, label: String = app.label.toString(), onClick: () -> Unit) {
    val tokens = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = tokens.touch).appPress(onClick)
            .padding(horizontal = tokens.padding, vertical = tokens.gap),
        horizontalArrangement = Arrangement.spacedBy(tokens.padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app)
        Text(label, style = tokens.state, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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

internal const val transitPackage = "de.schildbach.oeffi"
