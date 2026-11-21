package space.koyu.enyo

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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

private val Context.searchPreferences by preferencesDataStore("search")
private val searchHistoryEnabled = booleanPreferencesKey("history_enabled")
private val searchHistory = stringPreferencesKey("history")
private val setupSeen = booleanPreferencesKey("setup_seen")
private val defaultAssistant = stringPreferencesKey("assistant")

private enum class Assistant(val appId: String, val label: Int) {
    Claude("com.anthropic.claude", R.string.assistant_claude),
    ChatGPT("com.openai.chatgpt", R.string.assistant_chatgpt),
    Kagi("com.kagi.assistant", R.string.assistant_kagi),
    Gemini("com.google.android.apps.bard", R.string.assistant_gemini)
}

private data class InstalledAssistant(val assistant: Assistant, val launch: Intent, val icon: Bitmap)

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
                    if (!activity.enabled || !activity.applicationInfo.enabled) return@mapNotNull null
                    val drawable = activity.loadIcon(manager)
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(Canvas(bitmap))
                    InstalledAssistant(assistant, intent, bitmap)
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
internal fun SearchOnboarding() {
    val context = LocalContext.current
    val store = context.searchPreferences
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { store.edit { it[setupSeen] = true } }
    }
    val loaded by remember(store) { store.data.map { it[setupSeen] != true } }.collectAsState(false)
    if (loaded) {
        fun dismiss() { scope.launch { store.edit { it[setupSeen] = true } } }
        AlertDialog(
            onDismissRequest = { dismiss() },
            title = { Text(stringResource(R.string.contacts_permission)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LocalTokens.current.gap)) {
                    Text(stringResource(R.string.contacts_reason))
                    Text(stringResource(R.string.battery_discovery))
                }
            },
            confirmButton = {
                TextButton(onClick = { permission.launch(Manifest.permission.READ_CONTACTS) }) {
                    Text(stringResource(R.string.contacts_permission))
                }
            },
            dismissButton = { TextButton(onClick = { dismiss() }) { Text(stringResource(R.string.skip_setup)) } }
        )
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
    packageCycle: Int
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
    val matchesPreferred = installed.find { it.assistant.appId == preferred }
    val active = selected?.let { choice -> installed.find { it.assistant == choice } ?: matchesPreferred ?: installed.firstOrNull() }
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
    val appResults by produceState(emptyList<LauncherActivityInfo>(), apps, input, command) {
        value = if (input.isBlank() || command != null) emptyList() else withContext(Dispatchers.Default) {
            apps.filter { ensureActive(); it.label.contains(input, ignoreCase = true) }
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
    LaunchedEffect(Unit) { focus.requestFocus() }

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
    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        containerColor = tokens.background
    ) { inset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inset),
            contentPadding = PaddingValues(
                start = tokens.margin, end = tokens.margin, top = tokens.margin, bottom = tokens.bottomClearance
            )
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    SearchButton(R.string.back, back)
                    SearchButton(R.string.preferences, preferences)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text(stringResource(R.string.search), color = tokens.ink) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = tokens.ink,
                        unfocusedTextColor = tokens.ink,
                        focusedBorderColor = tokens.accent
                    )
                )
            }
            if (input.isBlank() && history.isNotBlank()) {
                items(history.lines()) { previous ->
                    TextButton(onClick = { onQuery(TextFieldValue(previous, TextRange(previous.length))) }) { Text(previous, color = tokens.ink) }
                }
            }
            if (installed.isNotEmpty()) item {
                SearchButton(R.string.assistant_search) {
                    if (selected == null) selected = (matchesPreferred ?: installed.first()).assistant
                    else choosing = true
                }
                if (active != null && installed.size > 1) {
                    SearchButton(R.string.change_assistant) { choosing = true }
                }
                active?.let {
                    val label = stringResource(it.assistant.label)
                    Text(stringResource(R.string.assistant_copy_explanation, label), style = tokens.secondary)
                    if (input.isNotBlank()) SearchButton(R.string.assistant_copy_open) {
                        val manager = context.getSystemService(ClipboardManager::class.java)
                        manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.search), input))
                        record(input)
                        open(it.launch)
                    }
                }
            }
            if (status != 0) item { Text(stringResource(status), style = tokens.secondary) }
            if (input.isNotBlank()) {
                items(appResults) { app ->
                    TextButton(onClick = { record(input); openApp(app) }) { Text(app.label.toString(), color = tokens.ink) }
                }
                if (command?.first in listOf(null, Command.Settings)) {
                    settingsDestinations.filter {
                        context.getString(it.label).contains(term, ignoreCase = true)
                    }.forEach { destination ->
                        item { SearchButton(destination.label) { open(Intent(destination.action)) } }
                    }
                }
                items(contacts) { contact ->
                    Column {
                        Text(stringResource(R.string.contact_result, contact.name, contact.number), style = tokens.state)
                        Row {
                            if (command?.first != Command.Message) SearchButton(R.string.call) { confirmContact(contact, false) }
                            if (command?.first != Command.Call) SearchButton(R.string.message) { confirmContact(contact, true) }
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
        }
    }
    if (choosing && installed.isNotEmpty()) {
        val ordered = installed.sortedByDescending { it.assistant.appId == preferred }
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.assistant_search)) },
            text = {
                Column {
                    ordered.forEach { option ->
                        AssistantButton(option) { selected = option.assistant; choosing = false }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(R.string.confirm_action)) },
            text = { Text(action.title) },
            confirmButton = {
                TextButton(onClick = { confirmation = null; open(action.intent) }) { Text(stringResource(R.string.continue_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
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
internal fun PreferencesScreen(back: () -> Unit, packageCycle: Int) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val store = context.searchPreferences
    val scope = rememberCoroutineScope()
    val installed = installedAssistants(packageCycle)
    val preferred by remember(store) { store.data.map { it[defaultAssistant] } }.collectAsState(null)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val historyOn by remember(store) { store.data.map { it[searchHistoryEnabled] == true } }.collectAsState(false)
    LazyColumn(
        Modifier.fillMaxSize().background(tokens.background).safeDrawingPadding(),
        contentPadding = PaddingValues(tokens.margin)
    ) {
        item {
            SearchButton(R.string.back, back)
            Text(stringResource(R.string.preferences), style = tokens.state)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = historyOn, onCheckedChange = { enabled ->
                    scope.launch {
                        store.edit {
                            it[searchHistoryEnabled] = enabled
                            if (!enabled) it.remove(searchHistory)
                        }
                    }
                })
                Spacer(Modifier.width(tokens.gap))
                Text(stringResource(R.string.search_history), style = tokens.state)
            }
            Text(stringResource(R.string.contacts_reason), style = tokens.secondary)
            SearchButton(R.string.contacts_permission) { permission.launch(Manifest.permission.READ_CONTACTS) }
            Text(stringResource(R.string.battery_discovery), style = tokens.secondary)
            SearchButton(R.string.calendar_permission) { permission.launch(Manifest.permission.READ_CALENDAR) }
            SearchButton(R.string.notification_access) {
                context.launchSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            if (installed.isNotEmpty()) {
                Text(stringResource(R.string.default_assistant), style = tokens.state)
                installed.find { it.assistant.appId == preferred }?.let {
                    Text(stringResource(R.string.current_assistant, stringResource(it.assistant.label)), style = tokens.secondary)
                }
            }
        }
        items(installed) { option ->
            AssistantButton(option) { scope.launch { store.edit { it[defaultAssistant] = option.assistant.appId } } }
        }
    }
}
