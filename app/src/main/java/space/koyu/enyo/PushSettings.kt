package space.koyu.enyo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/** Settings for Enyo as a UnifiedPush distributor: on or off, the ntfy server, and the registered apps. */
@Composable
internal fun PushPreference() {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    var enabled by remember { mutableStateOf(PushDistributor.enabled(context)) }
    val registrations by PushDistributor.registrations.collectAsState()
    val connected by PushDistributor.connected.collectAsState()
    LaunchedEffect(Unit) { PushDistributor.load(context) }
    var server by remember { mutableStateOf(PushDistributor.server(context)) }
    var editing by remember { mutableStateOf(false) }
    SettingsPanel(stringResource(R.string.push_title)) {
        SettingsToggle(stringResource(R.string.push_enable), enabled, summary = stringResource(R.string.push_summary), glyph = Glyph.Push) {
            enabled = it
            PushDistributor.setEnabled(context, it)
        }
        if (enabled) {
            val foreground = foregroundCycle()
            var asked by remember { mutableIntStateOf(0) }
            val notifying = remember(foreground, asked) {
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val request = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { granted ->
                asked++
                // Android stops asking after two refusals; its notification settings still allow it.
                if (!granted) context.launchSafely(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                else PushConnection.restart(context)
            }
            if (!notifying) ListRow(
                stringResource(R.string.push_notification_permission),
                summary = stringResource(R.string.push_notification_permission_summary),
                leading = { EnyoGlyph(Glyph.Bell, color = tokens.accent) },
                trailing = { PillButton(stringResource(R.string.push_allow), filled = true) {
                    request.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } }
            )
            val unrestricted = remember(foreground) { PushDistributor.unrestricted(context) }
            if (!unrestricted) ListRow(
                stringResource(R.string.push_battery), Modifier.appPress({
                    context.launchSafely(PushDistributor.unrestrictIntent(context).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }),
                summary = stringResource(R.string.push_battery_summary),
                leading = { EnyoGlyph(Glyph.Battery, color = tokens.accent) },
                trailing = { PillButton(stringResource(R.string.push_allow), filled = true) {
                    context.launchSafely(PushDistributor.unrestrictIntent(context).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } }
            )
            ListRow(
                stringResource(R.string.push_server), Modifier.appPress({ editing = true }),
                summary = PushDistributor.server(context), summaryLines = 1,
                leading = { EnyoGlyph(Glyph.World, color = tokens.onAccentContainer) },
                trailing = { EnyoGlyph(Glyph.Edit, color = tokens.quiet) }
            )
            if (registrations.isEmpty()) ListNote(stringResource(R.string.push_no_apps))
            else {
                ListNote(stringResource(if (connected) R.string.push_connected else R.string.push_connecting))
                registrations.forEach { registration ->
                    ListRow(
                        sourceLabel(context, registration.packageName),
                        summary = registration.description ?: registration.topic, summaryLines = 1,
                        leading = { EnyoGlyph(Glyph.Bell, color = tokens.accent) },
                        trailing = { PillButton(stringResource(R.string.news_remove)) { PushDistributor.remove(context, registration) } }
                    )
                }
            }
        }
    }
    if (editing) EnyoSheet(stringResource(R.string.push_server), { editing = false }) {
        Text(stringResource(R.string.push_server_note), style = tokens.secondary)
        val hint = stringResource(R.string.push_server)
        fun save() { PushDistributor.setServer(context, server); server = PushDistributor.server(context); editing = false }
        BasicTextField(
            server, { server = it },
            Modifier.fillMaxWidth().heightIn(min = tokens.touch).clip(RoundedCornerShape(tokens.touch / 2)).background(tokens.widgetTrack)
                .padding(horizontal = tokens.padding, vertical = tokens.gap * 1.5f).semantics { contentDescription = hint },
            singleLine = true, textStyle = tokens.body, cursorBrush = SolidColor(tokens.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() })
        )
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap), verticalAlignment = Alignment.CenterVertically) {
            PillButton(stringResource(R.string.push_server_default)) { server = PushDistributor.defaultServer }
            Spacer(Modifier.weight(1f))
            PillButton(stringResource(R.string.notes_done), filled = true) { save() }
        }
    }
}
