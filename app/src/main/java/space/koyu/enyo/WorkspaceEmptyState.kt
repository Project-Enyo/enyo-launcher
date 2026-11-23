package space.koyu.enyo

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** An empty page's message on a widget surface; with a [glyph] and [summary] it becomes a small illustrated card. */
@Composable
internal fun WorkspaceEmptyState(@StringRes message: Int, glyph: Glyph? = null, @StringRes summary: Int? = null) {
    val tokens = LocalTokens.current
    Surface(
        Modifier.padding(horizontal = tokens.margin).widthIn(max = 360.dp),
        color = tokens.widgetSurface,
        shape = RoundedCornerShape(tokens.radius),
        shadowElevation = tokens.elevation[0]
    ) {
        if (glyph == null) Text(
            stringResource(message),
            Modifier.padding(horizontal = tokens.padding, vertical = tokens.gap),
            style = tokens.state.copy(color = tokens.onAccentContainer),
            textAlign = TextAlign.Center
        ) else Column(
            Modifier.padding(horizontal = tokens.padding * 1.5f, vertical = tokens.padding * 1.5f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(tokens.listRowPadding)
        ) {
            Box(Modifier.size(tokens.iconSize).clip(CircleShape).background(tokens.accentContainer), contentAlignment = Alignment.Center) {
                EnyoGlyph(glyph, color = tokens.onAccentContainer)
            }
            Text(stringResource(message), style = tokens.state.copy(color = tokens.onAccentContainer), textAlign = TextAlign.Center)
            if (summary != null) Text(stringResource(summary), style = tokens.rowSummary, textAlign = TextAlign.Center)
        }
    }
}
