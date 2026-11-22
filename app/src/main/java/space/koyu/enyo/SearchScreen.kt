package space.koyu.enyo

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CalendarContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal val Context.searchPreferences by preferencesDataStore("search")
private val searchHistoryEnabled = booleanPreferencesKey("history_enabled")
private val searchHistory = stringPreferencesKey("history")
internal val setupSeen = booleanPreferencesKey("setup_seen")
private val defaultAssistant = stringPreferencesKey("assistant")

private enum class Assistant(val appId: String, val label: Int) {
    Claude("com.anthropic.claude", R.string.assistant_claude),
    ChatGPT("com.openai.chatgpt", R.string.assistant_chatgpt),
    Kagi("com.kagi.assistant", R.string.assistant_kagi),
    Gemini("com.google.android.apps.bard", R.string.assistant_gemini)
}

private data class InstalledAssistant(
    val assistant: Assistant,
    val launch: Intent,
    val icon: Bitmap,
    val textShare: ComponentName?
)

private fun assistantTextShare() = Intent(Intent.ACTION_SEND).setType("text/plain")

@Composable
private fun installedAssistants(packageCycle: Int): List<InstalledAssistant> {
    val context = LocalContext.current
    val foreground = foregroundCycle()
    val tokens = LocalTokens.current
    val size = with(androidx.compose.ui.platform.LocalDensity.current) { tokens.touch.roundToPx() }
    val installed by produceState(emptyList<InstalledAssistant>(), foreground, packageCycle, size) {
        value = withContext(Dispatchers.IO) {
            val manager = context.packageManager
            Assistant.entries.mapNotNull { assistant ->
                ensureActive()
                runCatching {
                    val intent = manager.getLaunchIntentForPackage(assistant.appId) ?: return@mapNotNull null
                    val activity = manager.resolveActivity(intent, 0)?.activityInfo ?: return@mapNotNull null
                    if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return@mapNotNull null
                    val drawable = activity.loadIcon(manager)
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(Canvas(bitmap))
                    val shareActivity = manager.queryIntentActivities(
                        assistantTextShare().setPackage(assistant.appId),
                        PackageManager.MATCH_DEFAULT_ONLY
                    ).firstOrNull { match ->
                        val candidate = match.activityInfo
                        candidate.packageName == assistant.appId && candidate.exported && candidate.enabled &&
                            candidate.applicationInfo.enabled && (candidate.permission == null ||
                            context.checkSelfPermission(candidate.permission) == PackageManager.PERMISSION_GRANTED)
                    }?.activityInfo
                    val textShare = shareActivity?.let { ComponentName(it.packageName, it.name) }
                    InstalledAssistant(assistant, intent, bitmap, textShare)
                }.getOrNull()
            }
        }
    }
    return installed
}

private data class Contact(val name: String, val number: String)
private data class SettingsDestination(val label: Int, val action: String)
private val settingsDestinations = listOf(
    SettingsDestination(R.string.android_settings, Settings.ACTION_SETTINGS),
    SettingsDestination(R.string.wireless, Settings.ACTION_WIRELESS_SETTINGS),
    SettingsDestination(R.string.bluetooth, Settings.ACTION_BLUETOOTH_SETTINGS),
    SettingsDestination(R.string.brightness, Settings.ACTION_DISPLAY_SETTINGS),
    SettingsDestination(R.string.notification_access, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
)

private enum class Command { Call, Message, Calendar, Web, Settings }
private data class Confirmation(val title: String, val intent: Intent)

@Composable
internal fun SearchOverlay(reveal: Float, active: Boolean, close: () -> Unit, content: @Composable () -> Unit) {
    val tokens = LocalTokens.current
    val dismiss by rememberUpdatedState(close)
    val blankTap = if (active) Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = true)
            if (waitForUpOrCancellation() != null) dismiss()
        }
    } else Modifier
    Box(Modifier.fillMaxSize().then(blankTap)) {
        Box(Modifier.matchParentSize()
            .drawBehind { drawRect(tokens.widgetSurface.copy(alpha = tokens.searchScrim * reveal)) })
        Box(
            Modifier.fillMaxSize()
                .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
                .graphicsLayer {
                    val distance = tokens.searchPullDistance.toPx()
                    translationY = -(1f - reveal) * distance
                    alpha = reveal
                }
        ) { content() }
    }
}

@Composable
internal fun SearchScreen(
    query: TextFieldValue,
    onQuery: (TextFieldValue) -> Unit,
    apps: List<LauncherActivityInfo>,
    openApp: (LauncherActivityInfo) -> Unit,
    back: () -> Unit,
    preferences: () -> Unit,
    packageCycle: Int,
    transit: List<LauncherActivityInfo>,
    openTransit: () -> Unit,
    openMessages: () -> Unit,
    active: Boolean
) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val focus = remember { FocusRequester() }
    var selected by remember { mutableStateOf<Assistant?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var status by remember { mutableIntStateOf(0) }
    val installed = installedAssistants(packageCycle)
    val store = context.searchPreferences
    val preferred by remember(store) { store.data.map { it[defaultAssistant] } }.collectAsState(null)
    val scope = rememberCoroutineScope()
    val history by remember(store) { store.data.map { it[searchHistory].orEmpty() } }.collectAsState("")
    val defaultOption = installed.defaultOption(preferred)
    val activeAssistant = selected?.let { choice -> installed.find { it.assistant == choice } ?: defaultOption }
    fun record(term: String) {
        if (term.isBlank()) return
        scope.launch {
            store.edit { preferences ->
                if (preferences[searchHistoryEnabled] == true) {
                    val previous = preferences[searchHistory].orEmpty().lineSequence().filter { it.isNotBlank() }
                    preferences[searchHistory] = (sequenceOf(term.replace('\n', ' ')) + previous)
                        .distinct().take(12).joinToString("\n")
                }
            }
        }
    }
    val commands = listOf(
        Command.Call to stringResource(R.string.command_call),
        Command.Message to stringResource(R.string.command_message),
        Command.Calendar to stringResource(R.string.command_calendar),
        Command.Web to stringResource(R.string.command_web),
        Command.Settings to stringResource(R.string.command_settings)
    )
    val input = query.text.trim()
    val command = commands.firstOrNull { input.startsWith(it.second + " ", ignoreCase = true) }
    val term = if (command == null) input else input.drop(command.second.length).trim()
    val transitLabel = stringResource(R.string.transit)
    val messagesLabel = stringResource(R.string.messages)
    val appResults by produceState(emptyList<LauncherActivityInfo>(), apps, input, command) {
        value = if (input.isBlank() || command != null) emptyList() else withContext(Dispatchers.Default) {
            apps.filter { app ->
                ensureActive()
                if (app.componentName.packageName == transitPackage) {
                    transitLabel.contains(input, ignoreCase = true) || transit.any { it.label.contains(input, ignoreCase = true) }
                } else app.label.contains(input, ignoreCase = true)
            }
        }
    }
    val foreground = foregroundCycle()
    val contacts by produceState(emptyList<Contact>(), term, foreground, command) {
        value = emptyList()
        if (term.isNotBlank() && command?.first in listOf(null, Command.Call, Command.Message)
            && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        ) value = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                val result = runCatching {
                    val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(term))
                    context.contentResolver.query(
                        uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null, signal
                    )?.use { cursor ->
                        buildList {
                            while (size < 20 && cursor.moveToNext()) {
                                val number = cursor.getString(1) ?: continue
                                add(Contact(cursor.getString(0).orEmpty(), number))
                            }
                        }.distinct()
                    }.orEmpty()
                }.getOrDefault(emptyList())
                continuation.resume(result)
            }
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(active) {
        if (active) {
            focus.requestFocus()
            keyboard?.show()
        } else if (query.text.isNotEmpty()) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    DisposableEffect(active) { onDispose { if (active) keyboard?.hide() } }

    fun open(intent: Intent) {
        if (!context.launchSafely(intent)) status = R.string.action_unavailable
    }
    fun confirmContact(contact: Contact, message: Boolean) {
        confirmation = Confirmation(
            contact.name.ifBlank { contact.number },
            if (message) Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", contact.number, null))
            else Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.number, null))
        )
    }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .padding(horizontal = tokens.margin).padding(top = tokens.gap),
        verticalArrangement = Arrangement.spacedBy(tokens.gap)
    ) {
        val backLabel = stringResource(R.string.back)
        val preferencesLabel = stringResource(R.string.preferences)
        val clearLabel = stringResource(R.string.clear_search)
        val searchLabel = stringResource(R.string.search)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = back, enabled = active, modifier = Modifier.semantics { contentDescription = backLabel }) {
                EnyoGlyph(Glyph.Back)
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(tokens.radius),
                color = tokens.widgetSurface
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    enabled = active,
                    singleLine = true,
                    textStyle = tokens.state.copy(color = tokens.onAccentContainer),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    cursorBrush = SolidColor(tokens.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        .semantics { contentDescription = searchLabel },
                    decorationBox = { field ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = tokens.touch).padding(start = tokens.padding),
                            horizontalArrangement = Arrangement.spacedBy(tokens.gap),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EnyoGlyph(Glyph.Search, color = tokens.onAccentContainer)
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (query.text.isEmpty()) Text(searchLabel, style = tokens.secondary, color = tokens.onAccentContainer)
                                field()
                            }
                            if (query.text.isNotEmpty()) IconButton(
                                onClick = { onQuery(TextFieldValue()) },
                                modifier = Modifier.semantics { contentDescription = clearLabel }
                            ) { EnyoGlyph(Glyph.Close, color = tokens.onAccentContainer) }
                            else Spacer(Modifier.width(tokens.padding))
                        }
                    }
                )
            }
            IconButton(onClick = preferences, enabled = active, modifier = Modifier.semantics { contentDescription = preferencesLabel }) {
                EnyoGlyph(Glyph.Settings)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(bottom = tokens.bottomClearance),
            verticalArrangement = Arrangement.spacedBy(tokens.gap)
        ) {
            if (status != 0) item { Text(stringResource(status), style = tokens.secondary) }
            if (input.isBlank() && history.isNotBlank()) {
                item { SearchSection(R.string.search_history_recent) }
                items(history.lines()) { previous ->
                    TextButton(onClick = { onQuery(TextFieldValue(previous, TextRange(previous.length))) }) {
                        Text(previous, color = tokens.ink)
                    }
                }
            }
            if (input.isNotBlank()) {
                val showMessages = command == null && messagesLabel.contains(input, ignoreCase = true)
                if (appResults.isNotEmpty() || showMessages) item { SearchSection(R.string.apps) }
                if (showMessages) item { SearchButton(R.string.messages, openMessages) }
                items(appResults, key = { it.componentName.flattenToString() }) { app ->
                    val consolidated = app.componentName.packageName == transitPackage
                    Surface(shape = RoundedCornerShape(tokens.radius), color = tokens.surface) {
                        LibraryRow(app, label = if (consolidated) transitLabel else app.label.toString()) {
                            record(input)
                            if (consolidated) openTransit() else openApp(app)
                        }
                    }
                }
                if (command?.first in listOf(null, Command.Settings)) {
                    val results = settingsDestinations.filter {
                        context.getString(it.label).contains(term, ignoreCase = true)
                    }
                    if (results.isNotEmpty()) item { SearchSection(R.string.android_settings) }
                    results.forEach { destination ->
                        item { SearchButton(destination.label) { open(Intent(destination.action)) } }
                    }
                }
                if (contacts.isNotEmpty()) item { SearchSection(R.string.contacts_results) }
                items(contacts) { contact ->
                    Surface(shape = RoundedCornerShape(tokens.radius), color = tokens.surface) {
                        Column(Modifier.fillMaxWidth().padding(tokens.padding)) {
                            Text(stringResource(R.string.contact_result, contact.name, contact.number), style = tokens.state)
                            Row {
                                if (command?.first != Command.Message) SearchButton(R.string.call) { confirmContact(contact, false) }
                                if (command?.first != Command.Call) SearchButton(R.string.message) { confirmContact(contact, true) }
                            }
                        }
                    }
                }
                if (command?.first == Command.Calendar && term.isNotBlank()) item {
                    SearchButton(R.string.calendar_prepare) {
                        confirmation = Confirmation(term, Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.Events.TITLE, term))
                    }
                }
                if (command?.first in listOf(null, Command.Web) && term.isNotBlank()) item {
                    SearchButton(R.string.web_search) {
                        val uri = Uri.parse("https://duckduckgo.com/").buildUpon().appendQueryParameter("q", term).build()
                        record(input)
                        open(Intent(Intent.ACTION_VIEW, uri))
                    }
                }
            }
            if (active && installed.isNotEmpty()) item {
                SearchButton(R.string.assistant_search) {
                    if (selected == null) selected = (defaultOption ?: installed.first()).assistant
                    else choosing = true
                }
                activeAssistant?.let { option ->
                    if (installed.size > 1) SearchButton(R.string.change_assistant) { choosing = true }
                    val name = stringResource(option.assistant.label)
                    var shareFailed by remember(option, input) { mutableStateOf(false) }
                    val recipient = option.textShare
                    if (recipient != null && !shareFailed) {
                        if (input.isNotBlank()) TextButton(onClick = {
                            val share = assistantTextShare().setComponent(recipient)
                                .putExtra(Intent.EXTRA_TEXT, input)
                            if (context.launchSafely(share)) record(input) else shareFailed = true
                        }) {
                            Text(stringResource(R.string.assistant_share_query, name), color = tokens.ink)
                        }
                    } else {
                        Text(stringResource(R.string.assistant_copy_explanation, name), style = tokens.secondary)
                        if (input.isNotBlank()) SearchButton(R.string.assistant_copy_open) {
                            val manager = context.getSystemService(ClipboardManager::class.java)
                            manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.search), input))
                            record(input)
                            open(option.launch)
                        }
                    }
                }
            }
        }
    }
    if (choosing && installed.isNotEmpty()) {
        val ordered = installed.sortedByDescending { it == defaultOption }
        EnyoSheet(stringResource(R.string.assistant_search), { choosing = false }) {
            ordered.forEach { option -> AssistantButton(option) { selected = option.assistant; choosing = false } }
        }
    }
    confirmation?.let { action ->
        EnyoSheet(stringResource(R.string.confirm_action), { confirmation = null }) {
            Text(action.title, style = tokens.secondary)
            Row {
                SearchButton(R.string.cancel) { confirmation = null }
                SearchButton(R.string.continue_action) { confirmation = null; open(action.intent) }
            }
        }
    }
}

@Composable
private fun SearchSection(label: Int) {
    val tokens = LocalTokens.current
    Text(stringResource(label), style = tokens.label, modifier = Modifier.padding(top = tokens.gap))
}

@Composable
private fun AssistantButton(option: InstalledAssistant, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    TextButton(onClick = onClick) {
        Image(option.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(tokens.touch))
        Spacer(Modifier.width(tokens.gap))
        Text(stringResource(option.assistant.label), color = tokens.ink)
    }
}

@Composable
private fun SearchButton(label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(label), color = LocalTokens.current.ink) }
}

@Composable
internal fun PreferencesScreen(back: () -> Unit, packageCycle: Int, wallpaper: () -> Unit, setup: () -> Unit) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val store = context.searchPreferences
    val scope = rememberCoroutineScope()
    val installed = installedAssistants(packageCycle)
    val preferred by remember(store) { store.data.map { it[defaultAssistant] } }.collectAsState(null)
    val appearance = context.appearancePreferences
    val originals = LocalOriginalIcons.current
    val historyOn by remember(store) { store.data.map { it[searchHistoryEnabled] == true } }.collectAsState(false)
    val hasHistory by remember(store) { store.data.map { !it[searchHistory].isNullOrBlank() } }.collectAsState(false)
    var confirmClearHistory by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().background(tokens.settingsBackground).safeDrawingPadding(),
        contentPadding = PaddingValues(tokens.margin),
        verticalArrangement = Arrangement.spacedBy(tokens.gap)
    ) {
        item { SettingsHeading(stringResource(R.string.preferences), back) }
        item {
            SettingsPanel(stringResource(R.string.appearance_title)) {
                SettingsLink(stringResource(R.string.wallpaper), stringResource(R.string.wallpaper_settings_summary),
                    Glyph.Today, wallpaper)
                SettingsToggle(stringResource(R.string.icon_originals), originals) { enabled ->
                    scope.launch { appearance.edit { it[originalIcons] = enabled } }
                }
            }
        }
        item {
            SettingsPanel(stringResource(R.string.search)) {
                SettingsToggle(stringResource(R.string.search_history), historyOn) { enabled ->
                    scope.launch {
                        store.edit {
                            it[searchHistoryEnabled] = enabled
                            if (!enabled) it.remove(searchHistory)
                        }
                    }
                }
                TextButton(
                    enabled = hasHistory,
                    onClick = { confirmClearHistory = true }
                ) { Text(stringResource(R.string.search_history_clear)) }
            }
        }
        if (installed.isNotEmpty()) item {
            DefaultAssistantCard(installed, preferred) { option ->
                scope.launch { store.edit { it[defaultAssistant] = option.assistant.appId } }
            }
        }
        item { OverlayPreference() }
        item { TaskClosePreference() }
        item {
            SettingsPanel(null) {
                SettingsLink(stringResource(R.string.setup_title), stringResource(R.string.setup_settings_summary),
                    Glyph.Settings, setup)
            }
        }
    }
    if (confirmClearHistory) {
        EnyoSheet(stringResource(R.string.search_history_clear), { confirmClearHistory = false }) {
            Text(stringResource(R.string.search_history_clear_message), style = tokens.secondary)
            Row {
                SearchButton(R.string.cancel) { confirmClearHistory = false }
                SearchButton(R.string.search_history_clear) {
                    confirmClearHistory = false
                    scope.launch { store.edit { it.remove(searchHistory) } }
                }
            }
        }
    }
}

private fun List<InstalledAssistant>.defaultOption(preferred: String?): InstalledAssistant? =
    firstOrNull { it.assistant.appId == preferred } ?: firstOrNull()

@Composable
private fun DefaultAssistantCard(
    installed: List<InstalledAssistant>,
    preferred: String?,
    select: (InstalledAssistant) -> Unit
) {
    val tokens = LocalTokens.current
    val active = installed.defaultOption(preferred)
    SettingsPanel(stringResource(R.string.default_assistant)) {
        if (preferred != null && installed.none { it.assistant.appId == preferred }) {
            Assistant.entries.firstOrNull { it.appId == preferred }?.let { previous ->
                Text(
                    stringResource(R.string.default_assistant_unavailable, stringResource(previous.label)),
                    style = tokens.secondary,
                    color = tokens.onAccentContainer
                )
            }
        }
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            installed.forEach { option ->
                val selected = option == active
                Row(
                    Modifier.fillMaxWidth().heightIn(min = tokens.touch)
                        .selectable(
                            selected = selected,
                            interactionSource = null,
                            indication = null,
                            role = Role.RadioButton,
                            onClick = { select(option) }
                        ).padding(vertical = tokens.gap),
                    horizontalArrangement = Arrangement.spacedBy(tokens.padding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        remember(option.icon) { option.icon.asImageBitmap() },
                        contentDescription = null,
                        modifier = Modifier.size(tokens.touch)
                    )
                    Text(
                        stringResource(option.assistant.label),
                        Modifier.weight(1f),
                        style = tokens.state,
                        color = tokens.onAccentContainer
                    )
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = tokens.accent,
                            unselectedColor = tokens.onAccentContainer
                        )
                    )
                }
            }
        }
    }
}
