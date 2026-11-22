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
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.layout.layout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalConfiguration
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
    weatherTap: Long,
    setTyping: (((KeyEvent) -> Boolean)?) -> Unit
) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val foreground = foregroundCycle()
    // Links people to contacts again on each return, for example after contacts access was granted.
    LaunchedEffect(foreground) { ArchiveData.resolveAll(context) }
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
    val messagesLabel = stringResource(R.string.messages)
    val transitLabel = stringResource(R.string.transit)
    val locale = LocalConfiguration.current.locales[0]
    fun appLabel(app: LauncherActivityInfo): String =
        if (app.componentName.packageName == transitPackage) transitLabel else labels[app.componentName].orEmpty()

    val appCollator = remember(locale) { Collator.getInstance(locale) }
    val library = remember(apps, labels, transitLabel, appCollator) {
        apps.filterNot { it.componentName.packageName in messagingPackages }.distinctBy {
            if (it.componentName.packageName == transitPackage) transitPackage else it.componentName.flattenToString()
        }.sortedWith(compareBy(appCollator) { appLabel(it) })
    }
    val messagesIndex = remember(library, labels, transitLabel, messagesLabel, appCollator) {
        library.indexOfFirst { appCollator.compare(appLabel(it), messagesLabel) >= 0 }
            .takeIf { it >= 0 } ?: library.size
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
    // The reveal follows the pull outside composition, so dragging and animating never recompose the launcher.
    val searchReveal = remember { Animatable(0f) }
    LaunchedEffect(searchVisible) {
        snapshotFlow { if (searchVisible) 1f else searchPullFraction }
            .collectLatest { searchReveal.animateTo(it, tween(tokens.searchPullAnimationMillis)) }
    }
    val searchShown by remember { derivedStateOf { searchReveal.value > 0f } }
    val searchPulling by remember { derivedStateOf { searchPullFraction != 0f } }
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
    LaunchedEffect(weatherTap) {
        if (weatherTap > 0) {
            surface = 0
            pager.scrollToPage(0)
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
    // One stable action, so recomposing the launcher never hands every page and tile a new callback.
    val currentOpenApp by rememberUpdatedState<(LauncherActivityInfo) -> Unit>(::openApp)
    val openAppAction = remember { { app: LauncherActivityInfo -> currentOpenApp(app) } }

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
    // Remembered tile content keeps one lambda instance, so launcher recompositions never rerun every app tile.
    val messagesTile = remember<@Composable () -> Unit>(messagesLabel) {
        { AppTile(messagesLabel, { messagesPicker = true }) { MessagesAppIcon() } }
    }
    val appTile = remember<@Composable (LauncherActivityInfo) -> Unit>(transitLabel, labels, openAppAction) {
        { app ->
            val consolidated = app.componentName.packageName == transitPackage
            AppTile(
                label = if (consolidated) transitLabel else labels[app.componentName].orEmpty(),
                open = { if (consolidated) transitPicker = true else openAppAction(app) },
                actions = if (consolidated) null else ({ appMenu = app })
            ) { AppIcon(app, label = labels[app.componentName]) }
        }
    }
    val screen = if (setupComplete == false) 4 else surface
    val settingsOpen = screen in fullScreens
    // Settings stays composed and drawn from startup, so opening it only slides an existing layer.
    val settingsSlide = remember { Animatable(0f) }
    LaunchedEffect(settingsOpen) {
        settingsSlide.animateTo(if (settingsOpen) 1f else 0f, tween(tokens.settingsTransitionMillis))
    }
    val covered by remember { derivedStateOf { settingsSlide.value == 1f } }
    val nested = if (screen == 3 || screen == 4) screen else 0
    val nestedScreens = updateTransition(nested, label = "nested screens")
    val settingsCovered = nestedScreens.currentState != 0 && nestedScreens.targetState != 0
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val focusManager = LocalFocusManager.current
    var launcherFocused by remember { mutableStateOf(false) }
    var settingsFocused by remember { mutableStateOf(false) }
    LaunchedEffect(covered, launcherFocused) { if (covered && launcherFocused) focusManager.clearFocus(force = true) }
    LaunchedEffect(settingsOpen, settingsFocused) { if (!settingsOpen && settingsFocused) focusManager.clearFocus(force = true) }
    // The launcher stays composed and drawn under full screen surfaces so closing them never rebuilds the pages.
    Box(Modifier.graphicsLayer { alpha = if (covered) 0f else 1f }.inert(covered).onFocusChanged { launcherFocused = it.hasFocus }) { WallpaperBackdrop { Box { Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(bottom = tokens.bottomClearance)
                .searchPull(
                    enabled = surface == 0, appGrid = { pager.currentPage == 1 },
                    appGridAtTop = { appsAtTop }, update = { searchPullFraction = it }
                ) { query = TextFieldValue(); surface = 1 }

        ) {
            // Search draws its own bar in this place, so the header only fades in the draw phase.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = tokens.margin)
                    .graphicsLayer { alpha = 1f - searchReveal.value },
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
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
            // Every page stays composed so a switch never composes the full app grid in one frame.
            HorizontalPager(
                pager, Modifier.weight(1f),
                beyondViewportPageCount = pages.size - 1,
                userScrollEnabled = !searchPulling
            ) { page ->
                // Each page lives in its own view, so a swipe leaves the page semantics trees untouched.
                PageHost { when (page) {
                    0 -> TodayBoard(foreground, pager.settledPage == 0 && screen !in fullScreens, weatherTap)
                    1 -> AppsGrid(library, messagesIndex, onAtTopChanged = { appsAtTop = it }, extra = messagesTile, tile = appTile)
                    2 -> Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) { CardsPage(apps, openAppAction, cardsFocus, !searchPulling) }
                        PinnedDock(
                            pinned, { scope.launch { pager.animateScrollToPage(1) } },
                            openAppAction, { appMenu = it }, labels,
                            move = { component, anchor, after -> scope.launch { context.movePin(component, anchor, after) } },
                            remove = { component -> scope.launch { context.changePin(component, false, installed) } }
                        )
                    }
                    3 -> InboxPage(apps, openAppAction)
                    // Quick listeners start once Inbox or Quick has settled, never in the middle of a swipe.
                    4 -> QuickPage(openPanel, quickFocus, searchOverlay = { surface == 1 }, visible = pager.settledPage >= 3 && screen !in 2..4)
                } }
            }
            // Banners stay composed and are only unplaced under search or settings.
            Box(Modifier.placedWhen(surface == 0)) { NotificationBanners(apps, openAppAction) }
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
        // Search stays composed from startup and is only placed while it is revealed.
        Box(Modifier.placedWhen(searchVisible || (surface == 0 && searchShown))) {
            SearchOverlay({ searchReveal.value }, searchVisible, { surface = 0 }) {
                SearchScreen(
                    query, { query = it }, library, openAppAction, { surface = 0 }, { surface = 2 },
                    packageCycle, transit, { transitPicker = true }, { messagesPicker = true }, searchVisible,
                    openInbox = { surface = 0; scope.launch { pager.scrollToPage(3) } }
                )
            }
        }
        } }
    }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                translationX = (1f - settingsSlide.value) * size.width * direction
                alpha = if (settingsSlide.value == 0f || settingsCovered) 0f else 1f
            }
            .inert(!settingsOpen || settingsCovered)
            .onFocusChanged { settingsFocused = it.hasFocus }
            .then(if (settingsOpen) Modifier.blockPointersBelow() else Modifier)
    ) { PreferencesScreen({ surface = 0 }, packageCycle, { surface = 3 }, { surface = 4 }) }
    nestedScreens.AnimatedContent(
        transitionSpec = {
            val motion = tween<IntOffset>(tokens.settingsTransitionMillis)
            val forward = targetState != 0
            slideInHorizontally(motion) { width -> if (forward) width else 0 }
                .togetherWith(slideOutHorizontally(motion) { width -> if (forward) 0 else width })
                .using(null)
        }
    ) { target ->
        if (target != 0) Box(Modifier.fillMaxSize().blockPointersBelow()) {
            if (target == 3) AppearanceScreen { surface = 2 } else {
                BackHandler { closeSetup() }
                PermissionSetup(setupComplete == false, ::closeSetup)
            }
        }
    }
}

private val fullScreens = 2..4

/** Hides covered content from accessibility and keeps focus from entering it. */
private fun Modifier.inert(inert: Boolean) = then(if (inert) Modifier.clearAndSetSemantics {} else Modifier)
    .focusProperties { onEnter = { if (inert) cancelFocusChange() } }
    .focusGroup()

/** Measures the content but leaves it unplaced while hidden, so it keeps its state without drawing or taking input. */
private fun Modifier.placedWhen(shown: Boolean) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { if (shown) placeable.place(0, 0) }
}

/** Claims every pointer over a full screen surface so blank areas never reach the launcher below. */
private fun Modifier.blockPointersBelow() = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

private val pageGlyphs = listOf(Glyph.Today, Glyph.Apps, Glyph.Cards, Glyph.Inbox, Glyph.Quick)

@Composable
internal fun LibraryRow(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = tokens.touch).appPress(onClick)
            .padding(tokens.gap),
        horizontalArrangement = Arrangement.spacedBy(tokens.padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Text(label, style = tokens.state, color = tokens.onAccentContainer, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

internal const val transitPackage = "de.schildbach.oeffi"
