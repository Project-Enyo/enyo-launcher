package space.koyu.enyo

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@Composable
internal fun SettingsPanel(title: String?, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalTokens.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tokens.widgetSurface,
        contentColor = tokens.onAccentContainer,
        shape = RoundedCornerShape(tokens.sheetRadius),
        shadowElevation = tokens.elevation[0]
    ) {
        Column(Modifier.padding(tokens.padding), verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
            title?.let { Text(it, Modifier.semantics { heading() }, style = tokens.state) }
            content()
        }
    }
}

@Composable
internal fun SettingsHeading(title: String, back: (() -> Unit)? = null) {
    val tokens = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = tokens.gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.gap)
    ) {
        if (back != null) {
            val label = androidx.compose.ui.res.stringResource(R.string.back)
            Box(
                Modifier.size(tokens.touch).appPress(back)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center
            ) { EnyoGlyph(Glyph.Back) }
        }
        Text(title, Modifier.semantics { heading() }, style = tokens.settingsTitle)
    }
}

@Composable
internal fun SettingsLink(title: String, summary: String? = null, glyph: Glyph, open: () -> Unit) {
    val tokens = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = tokens.touch).appPress(open),
        horizontalArrangement = Arrangement.spacedBy(tokens.padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(tokens.touch), contentAlignment = Alignment.Center) { EnyoGlyph(glyph) }
        Column(Modifier.weight(1f).padding(vertical = tokens.gap)) {
            Text(title, style = tokens.state)
            if (summary != null) Text(summary, style = tokens.secondary)
        }
        EnyoGlyph(Glyph.Forward, color = tokens.quiet)
    }
}

@Composable
internal fun SettingsToggle(title: String, checked: Boolean, change: (Boolean) -> Unit) {
    val tokens = LocalTokens.current
    val position by animateDpAsState(
        if (checked) tokens.toggleWidth - tokens.toggleHeight else androidx.compose.ui.unit.Dp(0f),
        label = "switch thumb"
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = tokens.touch)
            .toggleable(checked, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.padding)
    ) {
        Text(title, Modifier.weight(1f), style = tokens.state)
        Box(
            Modifier.size(tokens.toggleWidth, tokens.toggleHeight)
                .background(if (checked) tokens.accentContainer else tokens.background, CircleShape),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier.offset(x = position).size(tokens.toggleHeight).padding(tokens.toggleInset)
                    .background(if (checked) tokens.accent else tokens.quiet, CircleShape)
            )
        }
    }
}
