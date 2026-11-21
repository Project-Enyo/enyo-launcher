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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map
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
    val catalog by produceState(emptyList<Pair<LauncherActivityInfo, String>>(), foreground, packageCycle) {
        value = withContext(Dispatchers.IO) {
            val collator = Collator.getInstance()
            runCatching {
                val hiddenPackages = setOf(
                    context.packageName, "com.android.stk", "com.android.stk2",
                    "app.attestation.auditor", "app.grapheneos.info"
                )
                launcher.getActivityList(null, Process.myUserHandle())
                    .filterNot { it.componentName.packageName in hiddenPackages }
                    .map { app -> app to app.label.toString() }
                    .sortedWith(compareBy(collator) { it.second })
            }.getOrDefault(emptyList())
        }
    }
    val apps = remember(catalog) { catalog.map { it.first } }
    WarmAppIcons(apps)
    val labels = remember(catalog) { catalog.associate { it.first.componentName to it.second } }
    val transit = remember(apps) { apps.filter { it.componentName.packageName == transitPackage } }
    val providers = remember(apps) {
        apps.filter { it.componentName.packageName in messagingPackages }.distinctBy { it.componentName.packageName }
    }
    var messagesPicker by remember { mutableStateOf(false) }
    val library = remember(apps) {
        apps.filterNot { it.componentName.packageName in messagingPackages }.distinctBy {
            if (it.componentName.packageName == transitPackage) transitPackage else it.componentName.flattenToString()
        }
    }
    var transitPicker by remember { mutableStateOf(false) }
    var appMenu by remember { mutableStateOf<LauncherActivityInfo?>(null) }
    val pins by remember(context) { context.pins() }.collectAsState(emptyList())
    val installed = remember(apps) { apps.map { it.componentName }.toSet() }
    val pinned = remember(apps, pins) { pins.mapNotNull { id -> apps.firstOrNull { it.componentName == id } } }
    val pages = listOf(R.string.today, R.string.apps, R.string.cards, R.string.inbox, R.string.quick)
    val pager = rememberPagerState(initialPage = 2) { pages.size }
    val scope = rememberCoroutineScope()
    var surface by rememberSaveable { mutableIntStateOf(0) }
    var query by remember { mutableStateOf(TextFieldValue()) }
    var searchPullFraction by remember { mutableFloatStateOf(0f) }
    var appsAtTop by remember { mutableStateOf(true) }
    val searchVisible = surface == 1 && !transitPicker && !messagesPicker && appMenu == null
    val searchReveal by animateFloatAsState(
        if (searchVisible) 1f else searchPullFraction,
        tween(tokens.searchPullAnimationMillis),
        label = "search reveal"
    )
    val setupComplete by remember(context) { context.searchPreferences.data.map { it[setupSeen] == true } }
        .collectAsState(null)
    fun closeSetup() {
        if (setupComplete == false) {
            scope.launch {
                context.searchPreferences.edit { it[setupSeen] = true }
                surface = 0
            }
        } else surface = 2
    }
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
    if (messagesPicker && appMenu == null) AppFolder(
        title = stringResource(R.string.messages),
        items = providers,
        close = { messagesPicker = false },
        open = { app -> messagesPicker = false; openApp(app) },
        actions = { appMenu = it },
        footer = if (providers.isEmpty()) {{ Text(stringResource(R.string.messages_no_providers), style = tokens.secondary) }} else null
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
                    scope.launch { context.changePin(component, !pinnedApp, installed) }
                }
            ) { Text(stringResource(if (pinnedApp) R.string.dock_unpin else R.string.dock_pin)) }
            if (!pinnedApp && pinned.size >= dockCapacity) Text(stringResource(R.string.dock_full), style = tokens.secondary)
        }
    }
    BackHandler(surface != 0 || pager.currentPage != 2) {
        if (surface != 0) surface = 0 else scope.launch { pager.animateScrollToPage(2) }
    }
    when (if (setupComplete == false) 4 else surface) {

        2 -> PreferencesScreen({ surface = 0 }, packageCycle, { surface = 3 }, { surface = 4 })
        3 -> AppearanceScreen { surface = 2 }
        4 -> {
            BackHandler { closeSetup() }
            PermissionSetup(setupComplete == false, ::closeSetup)
        }
        else -> WallpaperBackdrop { Box { Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(bottom = tokens.bottomClearance)
                .searchPull(
                    enabled = surface == 0, appGrid = { pager.currentPage == 1 },
                    appGridAtTop = { appsAtTop }, update = { searchPullFraction = it }
                ) { query = TextFieldValue(); surface = 1 }

        ) {
            if (surface == 1 || searchReveal > 0f) Spacer(Modifier.height(tokens.touch))
            else Row(Modifier.fillMaxWidth().padding(horizontal = tokens.margin), horizontalArrangement = Arrangement.SpaceBetween) {
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
            HorizontalPager(
                pager, Modifier.weight(1f),
                userScrollEnabled = surface == 0 && searchPullFraction == 0f
            ) { page ->
                when (page) {
                    0 -> TodayBoard(foreground, pager.settledPage == 0 && surface == 0)
                    1 -> AppsGrid(library, onAtTopChanged = { appsAtTop = it }, extra = {
                        AppTile(stringResource(R.string.messages), { messagesPicker = true }) {
                            androidx.compose.material3.Surface(
                                modifier = Modifier.size(tokens.iconSize),
                                color = tokens.accentContainer,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(tokens.iconRadius)
                            ) {
                                Box(contentAlignment = Alignment.Center) { EnyoGlyph(Glyph.Messages, Modifier.size(tokens.appGlyphSize)) }
                            }
                        }
                    }) { app ->
                        val consolidated = app.componentName.packageName == transitPackage
                        AppTile(
                            label = if (consolidated) stringResource(R.string.transit) else labels[app.componentName].orEmpty(),
                            open = { if (consolidated) transitPicker = true else openApp(app) },
                            actions = if (consolidated) null else ({ appMenu = app })
                        ) { AppIcon(app, label = labels[app.componentName]) }
                    }
                    2 -> Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { CardsPage(apps, ::openApp, cardsFocus, surface == 0 && searchPullFraction == 0f) }
                        PinnedDock(
                            pinned, { scope.launch { pager.animateScrollToPage(1) } },
                            ::openApp, { appMenu = it }, labels,
                            move = { component, anchor, after -> scope.launch { context.movePin(component, anchor, after) } },
                            remove = { component -> scope.launch { context.changePin(component, false, installed) } }
                        )
                    }
                    3 -> InboxPage(apps, ::openApp)
                    4 -> QuickPage(openPanel, quickFocus, searchOverlay = surface == 1, visible = pager.currentPage == 4)
                }
            }
            if (surface == 0) NotificationBanners(apps, ::openApp)
            Row(Modifier.fillMaxWidth()) {
                pages.forEachIndexed { index, label ->
                    val labelText = stringResource(label)
                    Column(
                        modifier = Modifier.weight(1f).heightIn(min = tokens.touch)
                            .appPress({ scope.launch { pager.animateScrollToPage(index) } })
                            .semantics { if (pager.currentPage == index) selected = true },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        EnyoGlyph(pageGlyphs[index])
                        Text(labelText, style = tokens.label.copy(color = tokens.ink), maxLines = 1)
                    }
                }
            }
            Canvas(Modifier.fillMaxWidth().height(tokens.navigationDotSpace)) {
                val step = size.width / pages.size
                val x = (pager.currentPage + pager.currentPageOffsetFraction + 0.5f) * step
                drawCircle(tokens.accent, radius = tokens.navigationDotSize.toPx() / 2f, center = Offset(x, size.height / 2f))
            }
        }
        if (searchVisible || (surface == 0 && searchReveal > 0f)) {
            SearchOverlay(searchReveal, searchVisible, { surface = 0 }) {
                SearchScreen(
                    query, { query = it }, library, ::openApp, { surface = 0 }, { surface = 2 },
                    packageCycle, transit, { transitPicker = true }, { messagesPicker = true }, searchVisible
                )
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

internal const val transitPackage = "de.schildbach.oeffi"
