package space.koyu.enyo

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The person flyout behind an avatar: their numbers and addresses, the apps they write from, and
 * direct actions to call, write an e-mail, or open the chat in one of those apps.
 */
@Composable
internal fun ContactFlyout(
    summary: ThreadSummary,
    launchers: Map<String, android.content.pm.LauncherActivityInfo>,
    openThread: (() -> Unit)?,
    relatedThreads: List<ThreadRef> = listOf(summary.ref),
    close: () -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val routes = rememberArchive(emptyList<ChatRoute>(), relatedThreads) { context ->
        relatedThreads.flatMap { ArchiveData.routes(context, it) }
    }
    val details by produceState<ContactDetails?>(null, summary.contactLookup) {
        value = summary.contactLookup?.let { lookup -> withContext(Dispatchers.IO) { ArchiveData.contactDetails(context, lookup) } }
    }
    val active by EnyoNotificationService.entries.collectAsState()
    var failed by remember { mutableStateOf(false) }
    val phones = (details?.phones.orEmpty() + routes.mapNotNull { it.phone }).distinctBy { it.filter(Char::isDigit).takeLast(9) }
    val emails = details?.emails.orEmpty()
    // Messengers merged into the Messages hub are missing from the app list, so they are looked up here.
    val known = remember(launchers, routes) {
        val apps = context.getSystemService(android.content.pm.LauncherApps::class.java)
        launchers + routes.map { it.packageName }.distinct().filter { it !in launchers }.mapNotNull { pkg ->
            runCatching { apps.getActivityList(pkg, android.os.Process.myUserHandle()).firstOrNull() }.getOrNull()?.let { pkg to it }
        }
    }
    val label = { pkg: String -> known[pkg]?.label?.toString() ?: sourceLabel(context, pkg) }
    fun start(intent: Intent) {
        failed = !context.launchSafely(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (!failed) close()
    }
    EnyoSheet(summary.name, close) {
        LazyColumn(Modifier.heightIn(max = tokens.widgetPickerHeight), verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
            item(key = "head") {
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.padding), verticalAlignment = Alignment.CenterVertically) {
                    PersonAvatar(summary, tokens.flyoutAvatar)
                    Column(Modifier.weight(1f)) {
                        Text(summary.name, style = tokens.state, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(routes.map { label(it.packageName) }.distinct().joinToString(" · "), style = tokens.caption, maxLines = 2)
                    }
                }
            }
            item(key = "quick") {
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    phones.firstOrNull()?.let { phone ->
                        PillButton(stringResource(R.string.call), filled = true) { start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))) }
                    }
                    emails.firstOrNull()?.let { email ->
                        PillButton(stringResource(R.string.contact_email)) { start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null))) }
                    }
                    if (openThread != null) PillButton(stringResource(R.string.contact_thread)) { close(); openThread() }
                }
            }
            if (phones.isNotEmpty() || emails.isNotEmpty()) item(key = "reach") {
                SettingsPanel(stringResource(R.string.contact_details)) {
                    phones.forEach { phone ->
                        ListRow(
                            phone, Modifier.appPress({ start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))) }),
                            leading = { EnyoGlyph(Glyph.Phone, color = tokens.accent) },
                            trailing = { PillButton(stringResource(R.string.message)) { start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null))) } }
                        )
                    }
                    emails.forEach { email ->
                        SettingsAction(email, Glyph.Message) { start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null))) }
                    }
                }
            }
            if (routes.isNotEmpty()) item(key = "services") {
                SettingsPanel(stringResource(R.string.contact_services)) {
                    routes.distinctBy { it.packageName }.forEach { route ->
                        val live = active.filter { it.packageName == route.packageName && it.isMessagingNotice() }
                            .firstOrNull { entry -> entry.notification.shortcutId?.let { "shortcut:$it" == route.conversationKey } == true }
                        ListRow(
                            stringResource(R.string.thread_open_in, label(route.packageName)),
                            Modifier.appPress({ failed = !context.openChat(route, live); if (!failed) close() }),
                            summary = listOfNotNull(route.name, route.phone).distinct().joinToString(" · ").ifBlank { null }, summaryLines = 1,
                            leading = { known[route.packageName]?.let { AppIcon(it, displaySize = tokens.listAvatar) } }
                        )
                    }
                }
            }
            details?.uri?.let { uri ->
                item(key = "contact") {
                    ListCard { SettingsAction(stringResource(R.string.contact_open), Glyph.Contact) { start(Intent(Intent.ACTION_VIEW, uri)) } }
                }
            }
            if (failed) item(key = "failed") { ListCard { ListNote(stringResource(R.string.action_unavailable)) } }
        }
    }
}
