package space.koyu.enyo

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.content.ContentUris
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.text.Normalizer
import java.util.Locale

internal val Context.searchPreferences by preferencesDataStore("search")
private val searchHistoryEnabled = booleanPreferencesKey("history_enabled")
private val searchHistory = stringPreferencesKey("history")
internal val setupSeen = booleanPreferencesKey("setup_seen")
internal val defaultAssistant = stringPreferencesKey("assistant")

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

/** A person in search: an address book contact, a person from the message archive, or both. */
private data class Contact(val name: String, val number: String, val person: ThreadSummary)

private data class SearchPerson(val summary: ThreadSummary, val contact: Contact?, val threads: List<ThreadRef>)

private fun consolidatePeople(contacts: List<Contact>, archived: List<ThreadSummary>): List<SearchPerson> {
    val summaries = (contacts.map { it.person } + archived).distinctBy { it.ref }
    val byName = summaries.groupBy { person ->
        Normalizer.normalize(person.name.trim(), Normalizer.Form.NFC)
            .replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)
    }
    val groups = mutableMapOf<String, MutableList<ThreadSummary>>()
    for ((name, matches) in byName) {
        val lookups = matches.mapNotNull { it.contactLookup }.distinct()
        val appSources = matches.flatMap { it.packages }
        val ambiguous = lookups.size > 1 || appSources.distinct().size != appSources.size
        for (person in matches) {
            val lookup = person.contactLookup ?: lookups.singleOrNull()?.takeUnless { ambiguous }
            val id = when {
                lookup != null -> "contact:$lookup"
                ambiguous || name.isBlank() -> person.ref.toString()
                else -> "name:$name"
            }
            groups.getOrPut(id) { mutableListOf() }.add(person)
        }
    }
    return groups.values.map { group ->
        val contact = contacts.firstOrNull { candidate -> group.any { it.ref == candidate.person.ref } }
        val real = group.filter { (it.ref as? ThreadRef.Person)?.id?.let { id -> id >= 0 } == true }
        val primary = real.firstOrNull { it.contactLookup != null } ?: real.firstOrNull() ?: group.first()
        SearchPerson(primary.copy(
            name = contact?.name?.ifBlank { null } ?: primary.name,
            contactLookup = contact?.person?.contactLookup ?: primary.contactLookup,
            avatar = primary.avatar ?: group.firstNotNullOfOrNull { it.avatar },
            packages = group.flatMap { it.packages }.distinct()
        ), contact, real.map { it.ref })
    }
}

private data class CalendarHit(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String?)

/**
 * Searches title, description, and location of visible calendar instances from a month back to a
 * year ahead, soonest first. Runs locally through the calendar provider and needs READ_CALENDAR.
 */
private fun searchCalendar(context: Context, term: String, signal: CancellationSignal): List<CalendarHit> {
    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000
    val uri = CalendarContract.Instances.CONTENT_SEARCH_URI.buildUpon().also {
        ContentUris.appendId(it, now - calendarSearchPastDays * day)
        ContentUris.appendId(it, now + calendarSearchFutureDays * day)
        it.appendPath(term)
    }.build()
    val projection = arrayOf(
        CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION
    )
    val hits = context.contentResolver.query(
        uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null,
        "${CalendarContract.Instances.BEGIN} ASC", signal
    )?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(CalendarHit(
                    cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getLong(2), cursor.getLong(3),
                    cursor.getInt(4) == 1, cursor.getString(5)?.takeIf { it.isNotBlank() }
                ))
            }
        }
    }.orEmpty()
    // Upcoming events first, then the most recent past ones.
    val (upcoming, past) = hits.partition { it.end >= now }
    return (upcoming + past.reversed()).distinctBy { it.id to it.begin }.take(calendarSearchLimit)
}

private const val calendarSearchPastDays = 30L
private const val calendarSearchFutureDays = 365L
private const val calendarSearchLimit = 8
private data class SettingsDestination(val label: Int, val action: String)
private val settingsDestinations = listOf(
    SettingsDestination(R.string.android_settings, Settings.ACTION_SETTINGS),
    SettingsDestination(R.string.wireless, Settings.ACTION_WIRELESS_SETTINGS),
    SettingsDestination(R.string.bluetooth, Settings.ACTION_BLUETOOTH_SETTINGS),
    SettingsDestination(R.string.brightness, Settings.ACTION_DISPLAY_SETTINGS),
    SettingsDestination(R.string.notification_access, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
)

private enum class Command { Call, Message, Calendar, Web, Settings }

private val draftPhonePattern = Regex("""[+\d][\d ().-]{2,}""")

private data class Confirmation(val title: String, val intent: Intent)

@Composable
internal fun SearchOverlay(reveal: () -> Float, active: Boolean, close: () -> Unit, content: @Composable () -> Unit) {
    val tokens = LocalTokens.current
    val dismiss by rememberUpdatedState(close)
    val blankTap = if (active) Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = true)
            if (waitForUpOrCancellation() != null) dismiss()
        }
    } else Modifier
    Box(Modifier.fillMaxSize().then(blankTap)) {
        // The scrim fades through its own layer, so the reveal never re-records the launcher drawing.
        Box(Modifier.matchParentSize()
            .graphicsLayer { alpha = reveal() }
            .background(tokens.settingsBackground))
        Box(
            Modifier.fillMaxSize()
                .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
                .graphicsLayer {
                    val distance = tokens.searchPullDistance.toPx()
                    translationY = -(1f - reveal()) * distance
                    alpha = reveal()
                }
        ) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    active: Boolean,
    openInbox: () -> Unit,
    openIntent: (Intent) -> Boolean
) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val focus = remember { FocusRequester() }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var status by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val installed = installedAssistants(packageCycle)
    val store = context.searchPreferences
    val preferred by remember(store) { store.data.map { it[defaultAssistant] } }.collectAsState(null)
    val scope = rememberCoroutineScope()
    val history by remember(store) { store.data.map { it[searchHistory].orEmpty() } }.collectAsState("")
    val localReady = localAssistantReady()
    var localPrompt by remember { mutableStateOf<String?>(null) }
    val defaultOption = installed.defaultOption(preferred)
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
    val patternDraft = remember(input) { matchMessageDraft(input) }
    val localIntentAvailable = active && localReady && preferred == localAssistantId &&
        patternDraft == null && looksLikeMessageRequest(input)
    var interpret by remember(input, localIntentAvailable) { mutableStateOf(false) }
    var interpreting by remember(input, localIntentAvailable) { mutableStateOf(false) }
    var interpretedDraft by remember(input, localIntentAvailable) { mutableStateOf<MessageDraft?>(null) }
    var interpretationFailed by remember(input, localIntentAvailable) { mutableStateOf(false) }
    LaunchedEffect(input, localIntentAvailable, interpret) {
        if (localIntentAvailable && interpret) {
            interpreting = true
            interpretedDraft = interpretMessageDraft(context, input)
            interpretationFailed = interpretedDraft == null
            interpreting = false
        }
    }
    val draft = patternDraft ?: interpretedDraft
    val recipient = draft?.recipient
    val draftBody = draft?.body
    val command = if (draft != null) commands.first { it.first == Command.Message }
    else commands.firstOrNull { input.startsWith(it.second + " ", ignoreCase = true) }
    val term = recipient ?: if (command == null) input else input.drop(command.second.length).trim()
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
    val folder = fileSearchSource()
    val fileQuery = input.takeIf { active && command == null && it.length >= 2 }
    val findFolder = folder
    val fileKey = findFolder to fileQuery
    val fileResults by key(fileKey) { produceState(emptyList<FileHit>()) {
        value = emptyList()
        if (fileQuery != null && findFolder != null) {
            delay(250)
            try {
                findFiles(context, findFolder, fileQuery) { hits ->
                    withContext(Dispatchers.Main) { value = hits }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = emptyList()
            }
        }
    } }
    val files = fileResults
    val semanticEnabled = semanticSearchEnabled()
    val indexRevision by remember(store) { store.data.map { it[semanticRevision] ?: 0 } }.collectAsState(0)
    val semanticResults by key(fileKey, semanticEnabled) { produceState(emptyList<FileHit>(), indexRevision) {
        value = emptyList()
        if (semanticEnabled && fileQuery != null && findFolder != null && fileQuery.length >= 3) {
            delay(700)
            value = try {
                semanticFileMatches(context, findFolder, fileQuery)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            } catch (_: LinkageError) {
                emptyList()
            }
        }
    } }
    val relatedFiles = semanticResults.filter { hit -> files.none { it.file.uri == hit.file.uri } }
    val foreground = foregroundCycle()
    val events by produceState(emptyList<CalendarHit>(), term, foreground, command) {
        value = emptyList()
        if (term.isNotBlank() && command?.first in listOf(null, Command.Calendar)
            && context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        ) value = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                continuation.resume(runCatching { searchCalendar(context, term, signal) }.getOrDefault(emptyList()))
            }
        }
    }
    val messageHits by produceState(emptyList<MessageHit>(), term, command) {
        value = if (term.isBlank() || command != null) emptyList()
        else withContext(Dispatchers.IO) { ArchiveData.search(context, term) }
    }
    // People from the message archive, as Inbox shows them, including those without an address book entry.
    val people = rememberArchive(emptyList<ThreadSummary>()) { ArchiveData.threads(it).filter { summary -> summary.ref is ThreadRef.Person } }
    val archivePeople = remember(people, term, command) {
        if (term.isBlank() || command?.first !in listOf(null, Command.Call, Command.Message)) emptyList()
        else people.filter { it.name.contains(term, ignoreCase = true) }.take(10)
    }
    val contactResults by produceState("" to emptyList<Contact>(), term, foreground, command, people) {
        value = term to emptyList()
        if (term.isNotBlank() && command?.first in listOf(null, Command.Call, Command.Message)
            && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        ) value = term to withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                val result = runCatching {
                    val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(term))
                    // One result per person; the flyout lists every number and address.
                    val rows = context.contentResolver.query(
                        uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.LOOKUP_KEY), null, null, null, signal
                    )?.use { cursor ->
                        buildList {
                            while (size < 40 && cursor.moveToNext()) {
                                val number = cursor.getString(1) ?: continue
                                add(Triple(cursor.getString(0).orEmpty(), number, cursor.getString(2)))
                            }
                        }
                    }.orEmpty().distinctBy { it.third ?: it.second }
                    rows.take(20).map { (name, number, lookup) ->
                        val known = lookup?.let { key -> people.firstOrNull { it.contactLookup == key } }
                        Contact(name, number, known ?: ThreadSummary(
                            ThreadRef.Person(-1L - (lookup ?: number).hashCode().toLong().and(0xffffffffL)), name.ifBlank { number },
                            lookup, null, null, 0L, 0, emptyList()
                        ))
                    }
                }.getOrDefault(emptyList())
                continuation.resume(result)
            }
        }
    }
    val contacts = contactResults.second.takeIf { contactResults.first == term }.orEmpty()
    val searchPeople = remember(contacts, archivePeople) { consolidatePeople(contacts, archivePeople) }
    var flyout by remember { mutableStateOf<SearchPerson?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var fieldFocused by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            focus.requestFocus()
            keyboard?.show()
        } else if (fieldFocused) {
            // Search stays composed while hidden, so the field must not keep launcher typing.
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    DisposableEffect(active) { onDispose { if (active) keyboard?.hide() } }

    fun open(intent: Intent) {
        if (!openIntent(intent)) status = R.string.action_unavailable
    }
    // Opens the dialer or a message draft directly; neither places a call or sends anything by itself.
    fun reachContact(contact: Contact, message: Boolean) {
        record(input)
        open(
            if (message) Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", contact.number, null))
            else Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.number, null))
        )
    }
    fun openPerson(person: SearchPerson) {
        val contact = person.contact
        if (contact != null && command?.first in listOf(Command.Call, Command.Message)) {
            reachContact(contact, command?.first == Command.Message)
        } else {
            record(input)
            flyout = person
        }
    }
    val showMessages = input.isNotBlank() && command == null && messagesLabel.contains(input, ignoreCase = true)
    val settingsResults = if (input.isNotBlank() && command?.first in listOf(null, Command.Settings)) settingsDestinations.filter {
        context.getString(it.label).contains(term, ignoreCase = true)
    } else emptyList()
    val calendar = command?.first == Command.Calendar && term.isNotBlank()
    val web = command?.first in listOf(null, Command.Web) && term.isNotBlank()
    // One action hands the query to the default assistant; the choice itself lives in Settings.
    val useLocalAssistant = active && draft == null && localReady && preferred == localAssistantId
    val assistant = defaultOption?.takeIf { active && draft == null && !useLocalAssistant }
    fun launchResult(app: LauncherActivityInfo) {
        record(input)
        if (app.componentName.packageName == transitPackage) openTransit() else openApp(app)
    }
    fun openHit(hit: MessageHit) {
        record(input)
        ArchiveData.requestedThread.value = hit.thread
        openInbox()
    }
    fun openEvent(event: CalendarHit) {
        record(input)
        open(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end))
    }
    fun prepareEvent() {
        confirmation = Confirmation(term, Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, term))
    }
    fun ask(option: InstalledAssistant) {
        record(input)
        val recipient = option.textShare
        val shared = recipient != null && openIntent(
            assistantTextShare().setComponent(recipient).putExtra(Intent.EXTRA_TEXT, input)
        )
        if (!shared) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.search), input))
            open(option.launch)
        }
    }
    fun searchWeb() {
        val uri = Uri.parse("https://duckduckgo.com/").buildUpon().appendQueryParameter("q", term).build()
        record(input)
        open(Intent(Intent.ACTION_VIEW, uri))
    }
    fun openDraft(number: String) {
        open(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", draftBody))
    }
    val draftRecipients = when {
        draft == null -> emptyList()
        draftPhonePattern.matches(term) -> listOf(term to term)
        else -> contacts.map { it.name.ifBlank { it.number } to it.number }
    }
    // Submitting runs the topmost result, in the order the list shows them.
    val firstResult: (() -> Unit)? = if (input.isBlank()) null else when {
        draftRecipients.size == 1 -> { { openDraft(draftRecipients.single().second) } }
        draft != null -> null
        localIntentAvailable && !interpretationFailed -> if (interpret) null else ({ interpret = true })
        showMessages -> openMessages
        appResults.isNotEmpty() -> appResults.first().let { app -> { launchResult(app) } }
        settingsResults.isNotEmpty() -> settingsResults.first().let { destination -> { open(Intent(destination.action)) } }
        searchPeople.isNotEmpty() -> { { openPerson(searchPeople.first()) } }
        messageHits.isNotEmpty() -> messageHits.first().let { hit -> { openHit(hit) } }
        events.isNotEmpty() -> events.first().let { event -> { openEvent(event) } }
        files.isNotEmpty() -> { { open(files.first().file.viewIntent()) } }
        relatedFiles.isNotEmpty() -> { { open(relatedFiles.first().file.viewIntent()) } }
        calendar -> ::prepareEvent
        useLocalAssistant -> { { localPrompt = input } }
        assistant != null -> { { ask(assistant) } }
        web -> ::searchWeb
        else -> null
    }
    Column(
        // Pads for the keyboard's final height once, instead of relaying out search on every keyboard animation frame.
        Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.imeAnimationTarget)
        )
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { firstResult?.invoke() }),
                    cursorBrush = SolidColor(tokens.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        .onFocusChanged { fieldFocused = it.isFocused }
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
            if (status != 0) item { ListCard { ListNote(stringResource(status)) } }
            if (input.isBlank() && history.isNotBlank()) item {
                SettingsPanel(stringResource(R.string.search_history_recent)) {
                    history.lines().filter { it.isNotBlank() }.forEach { previous ->
                        ListRow(
                            previous, Modifier.appPress({ onQuery(TextFieldValue(previous, TextRange(previous.length))) }),
                            leading = { EnyoGlyph(Glyph.History, color = tokens.quiet) }
                        )
                    }
                    SettingsAction(stringResource(R.string.search_history_clear), Glyph.Trash) { confirmClear = true }
                }
            }
            if (localIntentAvailable && draft == null && !interpretationFailed) item {
                ListCard {
                    SettingsAction(stringResource(if (interpreting || interpret) R.string.search_interpreting_draft
                        else R.string.search_interpret_draft), Glyph.Message, enabled = !interpret) {
                        interpret = true
                    }
                }
            }
            if (interpretationFailed) item { ListCard { ListNote(stringResource(R.string.search_interpret_failed)) } }
            if (draft != null) item {
                SettingsPanel(stringResource(R.string.search_message_draft)) {
                    if (draftRecipients.isEmpty()) ListNote(stringResource(R.string.search_draft_no_recipient, term))
                    draftRecipients.forEach { (name, number) ->
                        SettingsAction(name, Glyph.Message, summary = draftBody) { openDraft(number) }
                    }
                }
            }
            if (input.isNotBlank() && draft == null) {
                if (appResults.isNotEmpty() || showMessages) item {
                    SettingsPanel(stringResource(R.string.apps)) {
                        if (showMessages) ListRow(
                            messagesLabel, Modifier.appPress(openMessages),
                            leading = { Box(Modifier.size(tokens.listAvatar)) { MessagesAppIcon() } }
                        )
                        appResults.forEach { app ->
                            key(app.componentName) {
                                val consolidated = app.componentName.packageName == transitPackage
                                ListRow(
                                    if (consolidated) transitLabel else app.label.toString(),
                                    Modifier.appPress({ launchResult(app) }),
                                    leading = { AppIcon(app, displaySize = tokens.listAvatar) }
                                )
                            }
                        }
                    }
                }
                if (settingsResults.isNotEmpty()) item {
                    SettingsPanel(stringResource(R.string.android_settings)) {
                        settingsResults.forEach { destination ->
                            SettingsAction(stringResource(destination.label), Glyph.Settings) { open(Intent(destination.action)) }
                        }
                    }
                }
                if (searchPeople.isNotEmpty()) item {
                    SettingsPanel(stringResource(R.string.contacts_results)) {
                        val callLabel = stringResource(R.string.call)
                        val messageLabel = stringResource(R.string.message)
                        searchPeople.forEach { person ->
                            val summary = person.summary
                            val contact = person.contact
                            val messengers = summary.packages.map { pkg ->
                                apps.firstOrNull { it.componentName.packageName == pkg }?.label?.toString() ?: sourceLabel(context, pkg)
                            }
                            ListRow(
                                summary.name,
                                Modifier.appPress({ openPerson(person) }),
                                summary = (listOfNotNull(contact?.number) + messengers).joinToString(" · ").ifBlank { null },
                                summaryLines = 1,
                                leading = { PersonAvatar(summary, tokens.listAvatar) },
                                trailing = contact?.let {
                                    {
                                        Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap / 2)) {
                                            if (command?.first != Command.Message) RoundIconButton(Glyph.Phone, callLabel) { reachContact(it, false) }
                                            if (command?.first != Command.Call) RoundIconButton(Glyph.Message, messageLabel) { reachContact(it, true) }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
                // Offline full text search over the saved messages; a hit opens its thread in Inbox.
                if (messageHits.isNotEmpty()) item {
                    SettingsPanel(stringResource(R.string.messages)) {
                        messageHits.forEach { hit ->
                            val app = apps.firstOrNull { it.componentName.packageName == hit.message.packageName }
                            ListRow(
                                hit.threadName,
                                Modifier.appPress({ openHit(hit) }),
                                summary = listOfNotNull(hit.message.sender?.takeIf { !hit.message.outgoing && it != hit.threadName }, hit.message.text).joinToString(": "),
                                summaryLines = 2,
                                leading = { if (app != null) AppIcon(app, displaySize = tokens.listAvatar) else EnyoGlyph(Glyph.Message, color = tokens.quiet) }
                            )
                        }
                    }
                }
                if (events.isNotEmpty()) item {
                    SettingsPanel(stringResource(R.string.calendar_results)) {
                        events.forEach { event ->
                            val time = remember(event) {
                                if (event.allDay) DateUtils.formatDateRange(
                                    context, java.util.Formatter(StringBuilder(), java.util.Locale.getDefault()), event.begin, event.end,
                                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL, "UTC"
                                ).toString()
                                else DateUtils.formatDateRange(
                                    context, event.begin, event.end,
                                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL
                                )
                            }
                            SettingsAction(
                                event.title.ifBlank { stringResource(R.string.calendar_untitled) }, Glyph.Calendar,
                                summary = listOfNotNull(time, event.location).joinToString(" · ")
                            ) { openEvent(event) }
                        }
                    }
                }
                if (files.isNotEmpty()) item {
                    SettingsPanel(stringResource(R.string.search_files)) {
                        files.forEach { hit ->
                            SettingsAction(hit.file.name, Glyph.Folder, summary = hit.excerpt) {
                                open(hit.file.viewIntent())
                            }
                        }
                    }
                }
                if (relatedFiles.isNotEmpty()) item {
                    SettingsPanel(stringResource(R.string.search_related_files)) {
                        relatedFiles.forEach { hit ->
                            SettingsAction(hit.file.name, if (hit.file.mime.startsWith("image/")) Glyph.Photo else Glyph.Folder) {
                                open(hit.file.viewIntent())
                            }
                        }
                    }
                }
                if (calendar) item {
                    ListCard {
                        SettingsAction(stringResource(R.string.calendar_prepare), Glyph.Calendar, summary = term) { prepareEvent() }
                    }
                }
                if (useLocalAssistant || assistant != null || web) item {
                    SettingsPanel(stringResource(R.string.search_web_section)) {
                        if (useLocalAssistant) SettingsAction(stringResource(R.string.local_ai), Glyph.Sparkles,
                            summary = input) { localPrompt = input }
                        if (assistant != null) {
                            val name = stringResource(assistant.assistant.label)
                            ListRow(
                                stringResource(R.string.assistant_ask, name),
                                Modifier.appPress({ ask(assistant) }),
                                summary = input,
                                summaryLines = 1,
                                leading = {
                                    Image(
                                        remember(assistant.icon) { assistant.icon.asImageBitmap() }, contentDescription = null,
                                        modifier = Modifier.size(tokens.listAvatar).clip(CircleShape)
                                    )
                                },
                                trailing = { EnyoGlyph(Glyph.Sparkles, color = tokens.accent) }
                            )
                        }
                        if (web) SettingsAction(stringResource(R.string.web_search), Glyph.World, summary = term) { searchWeb() }
                    }
                }
            }
        }
    }
    if (active && localReady) localPrompt?.let { prompt ->
        AssistantChats.Chat(null, prompt) { localPrompt = null }
    }
    flyout?.let { person ->
        val launchers = remember(apps) { apps.associateBy { it.componentName.packageName } }
        val thread = person.summary.ref.takeIf { it in person.threads }
        ContactFlyout(person.summary, launchers, openThread = thread?.let {
            { ArchiveData.requestedThread.value = it; openInbox() }
        }, relatedThreads = person.threads) { flyout = null }
    }
    if (confirmClear) {
        EnyoSheet(stringResource(R.string.search_history_clear), { confirmClear = false }) {
            Text(stringResource(R.string.search_history_clear_message), style = tokens.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                PillButton(stringResource(R.string.cancel)) { confirmClear = false }
                PillButton(stringResource(R.string.search_history_clear), filled = true) {
                    confirmClear = false
                    scope.launch { store.edit { it.remove(searchHistory) } }
                }
            }
        }
    }
    confirmation?.let { action ->
        EnyoSheet(stringResource(R.string.confirm_action), { confirmation = null }) {
            Text(action.title, style = tokens.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                PillButton(stringResource(R.string.cancel)) { confirmation = null }
                PillButton(stringResource(R.string.continue_action), filled = true) { confirmation = null; open(action.intent) }
            }
        }
    }
}

/** Round icon action inside a result row. */
@Composable
private fun RoundIconButton(glyph: Glyph, label: String, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    Box(
        Modifier.size(tokens.listAvatar).clip(CircleShape).background(tokens.widgetTrack)
            .appPress(onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { EnyoGlyph(glyph, Modifier.size(tokens.noticeAppIcon), color = tokens.onAccentContainer) }
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
    val localReady = localAssistantReady()
    val preferred by remember(store) { store.data.map { it[defaultAssistant] } }.collectAsState(null)
    val appearance = context.appearancePreferences
    val originals = LocalOriginalIcons.current
    val historyOn by remember(store) { store.data.map { it[searchHistoryEnabled] == true } }.collectAsState(false)
    val hasHistory by remember(store) { store.data.map { !it[searchHistory].isNullOrBlank() } }.collectAsState(false)
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearMessages by remember { mutableStateOf(false) }
    if (confirmClearMessages) EnyoSheet(stringResource(R.string.settings_history_clear), { confirmClearMessages = false }) {
        Text(stringResource(R.string.settings_history_clear_message), style = tokens.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
            PillButton(stringResource(R.string.cancel)) { confirmClearMessages = false }
            PillButton(stringResource(R.string.settings_history_clear), filled = true) {
                confirmClearMessages = false
                ArchiveData.deleteAll(context)
            }
        }
    }
    SettingsScreen { padding -> LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(tokens.gap)
    ) {
        item { SettingsHeading(stringResource(R.string.preferences), back) }
        item {
            SettingsPanel(stringResource(R.string.appearance_title)) {
                SettingsLink(stringResource(R.string.wallpaper), stringResource(R.string.wallpaper_settings_summary),
                    Glyph.Photo, wallpaper)
                SettingsToggle(stringResource(R.string.icon_originals), originals, glyph = Glyph.Apps) { enabled ->
                    scope.launch { appearance.edit { it[originalIcons] = enabled } }
                }
            }
        }
        item {
            SettingsPanel(stringResource(R.string.search)) {
                SearchFolderPreference()
                SemanticSearchPreference()
                SettingsToggle(stringResource(R.string.search_history), historyOn, glyph = Glyph.History) { enabled ->
                    scope.launch {
                        store.edit {
                            it[searchHistoryEnabled] = enabled
                            if (!enabled) it.remove(searchHistory)
                        }
                    }
                }
                SettingsAction(stringResource(R.string.search_history_clear), Glyph.Trash, enabled = hasHistory) {
                    confirmClearHistory = true
                }
            }
        }
        item { LocalAssistantPreference() }
        if (installed.isNotEmpty() || localReady) item {
            DefaultAssistantCard(installed, preferred, localReady) { assistant ->
                scope.launch { store.edit { it[defaultAssistant] = assistant } }
            }
        }
        item {
            SettingsPanel(stringResource(R.string.settings_history)) {
                ListNote(stringResource(R.string.settings_history_summary))
                SettingsAction(stringResource(R.string.settings_history_clear), Glyph.Trash) { confirmClearMessages = true }
            }
        }
        item { OverlayPreference() }
        item { TaskClosePreference() }
        item { PushPreference() }
        item { InboxHostPreference() }
        item { UpdatePreference() }
        item {
            SettingsPanel(stringResource(R.string.settings_system)) {
                SettingsLink(stringResource(R.string.setup_title), stringResource(R.string.setup_settings_summary),
                    Glyph.Settings, setup)
                SettingsLink(stringResource(R.string.android_settings), glyph = Glyph.Quick) {
                    context.launchSafely(Intent(Settings.ACTION_SETTINGS))
                }
                RestartPreference()
            }
        }
    } }
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
    localReady: Boolean,
    select: (String) -> Unit
) {
    val tokens = LocalTokens.current
    val localSelected = localReady && preferred == localAssistantId
    val active = installed.defaultOption(preferred).takeUnless { localSelected }
    SettingsPanel(stringResource(R.string.default_assistant)) {
        if (preferred != null && installed.none { it.assistant.appId == preferred }) {
            Assistant.entries.firstOrNull { it.appId == preferred }?.let { previous ->
                ListNote(stringResource(R.string.default_assistant_unavailable, stringResource(previous.label)))
            }
        }
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            if (localReady) ListRow(
                stringResource(R.string.local_ai),
                Modifier.selectable(selected = localSelected, interactionSource = null, indication = null,
                    role = Role.RadioButton, onClick = { select(localAssistantId) }),
                leading = { EnyoGlyph(Glyph.Sparkles) },
                trailing = { AssistantRadio(localSelected) }
            )
            installed.forEach { option ->
                val selected = option == active
                ListRow(
                    stringResource(option.assistant.label),
                    Modifier.selectable(
                        selected = selected, interactionSource = null, indication = null,
                        role = Role.RadioButton, onClick = { select(option.assistant.appId) }
                    ),
                    leading = {
                        Image(
                            remember(option.icon) { option.icon.asImageBitmap() }, contentDescription = null,
                            modifier = Modifier.size(tokens.listAvatar).clip(CircleShape)
                        )
                    },
                    trailing = { AssistantRadio(selected) }
                )
            }
        }
    }
}


@Composable
private fun AssistantRadio(selected: Boolean) {
    val tokens = LocalTokens.current
    RadioButton(selected = selected, onClick = null,
        colors = RadioButtonDefaults.colors(selectedColor = tokens.accent, unselectedColor = tokens.quiet))
}
