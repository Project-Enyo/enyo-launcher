package space.koyu.enyo

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * Shared frame for every settings surface: one background, the safe drawing insets, and the page
 * margin handed to the scrolling content. The optional footer stays pinned below the content.
 */
@Composable
internal fun SettingsScreen(
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val tokens = LocalTokens.current
    Column(Modifier.fillMaxSize().background(tokens.settingsBackground).safeDrawingPadding().then(modifier)) {
        Box(Modifier.weight(1f)) { content(PaddingValues(tokens.margin)) }
        footer?.let { Box(Modifier.padding(horizontal = tokens.margin).padding(bottom = tokens.margin)) { it() } }
    }
}

/** Small accent label that names the card below it. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    Text(
        text, modifier.padding(start = tokens.padding, top = tokens.gap, bottom = tokens.listLabelGap)
            .semantics { heading() },
        style = tokens.label.copy(color = tokens.accent)
    )
}

/** One rounded surface that groups the rows of a section. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalTokens.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = tokens.widgetSurface,
        contentColor = tokens.onAccentContainer,
        shape = RoundedCornerShape(tokens.radius)
    ) {
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            Column(Modifier.padding(vertical = tokens.listCardInset), content = content)
        }
    }
}

/** A titled section: the label outside, the content inside one card with row padding. */
@Composable
internal fun SettingsPanel(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        title?.let { SectionLabel(it) }
        ListCard(content = content)
    }
}

/**
 * The shared list row: a fixed leading slot, a title with an optional summary, and a trailing
 * control. Rows keep one minimum height so every list reads at the same rhythm.
 */
@Composable
internal fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    summaryLines: Int = 3,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val tokens = LocalTokens.current
    Row(
        modifier.fillMaxWidth().heightIn(min = tokens.listRowHeight)
            .graphicsLayer { alpha = if (enabled) 1f else tokens.disabledOpacity }
            .padding(horizontal = tokens.padding, vertical = tokens.listRowPadding),
        horizontalArrangement = Arrangement.spacedBy(tokens.padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) Box(Modifier.size(tokens.listLeading), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f)) {
            Text(title, style = tokens.rowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (summary != null) Text(summary, style = tokens.rowSummary, maxLines = summaryLines, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

/** Plain explanatory text inside a card, aligned with row titles. */
@Composable
internal fun ListNote(text: String) {
    val tokens = LocalTokens.current
    Text(text, Modifier.padding(horizontal = tokens.padding, vertical = tokens.listRowPadding), style = tokens.rowSummary)
}

@Composable
internal fun SettingsHeading(title: String, back: (() -> Unit)? = null) {
    val tokens = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().padding(bottom = tokens.gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.gap)
    ) {
        if (back != null) {
            val label = androidx.compose.ui.res.stringResource(R.string.back)
            Box(
                Modifier.size(tokens.touch).clip(CircleShape).appPress(back)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center
            ) { EnyoGlyph(Glyph.Back) }
        } else Spacer(Modifier.width(tokens.gap))
        Text(title, Modifier.semantics { heading() }, style = tokens.settingsTitle)
    }
}

@Composable
internal fun SettingsLink(title: String, summary: String? = null, glyph: Glyph, open: () -> Unit) {
    val tokens = LocalTokens.current
    ListRow(
        title, Modifier.appPress(open), summary = summary,
        leading = { EnyoGlyph(glyph, color = tokens.onAccentContainer) },
        trailing = { EnyoGlyph(Glyph.Forward, Modifier.size(tokens.chevronSize * 1.5f), color = tokens.quiet) }
    )
}

/** A row that runs an action, dimmed and inert while it is unavailable. */
@Composable
internal fun SettingsAction(title: String, glyph: Glyph, enabled: Boolean = true, summary: String? = null, run: () -> Unit) {
    val tokens = LocalTokens.current
    ListRow(
        title, if (enabled) Modifier.appPress(run) else Modifier, summary = summary, enabled = enabled,
        leading = { EnyoGlyph(glyph, color = tokens.onAccentContainer) }
    )
}

@Composable
internal fun SettingsToggle(
    title: String,
    checked: Boolean,
    summary: String? = null,
    glyph: Glyph? = null,
    change: (Boolean) -> Unit
) {
    val tokens = LocalTokens.current
    // Stored values arrive after the first frame, so only a user change animates the thumb.
    var touched by remember { mutableStateOf(false) }
    ListRow(
        title,
        Modifier.toggleable(
            checked, interactionSource = null, indication = null, role = Role.Switch,
            onValueChange = { touched = true; change(it) }
        ),
        summary = summary,
        leading = glyph?.let { { EnyoGlyph(it, color = tokens.onAccentContainer) } },
        trailing = { EnyoSwitch(checked, animate = touched) }
    )
}

/** Switch visual shared by every toggle: a filled accent track when on, an outlined track when off. */
@Composable
internal fun EnyoSwitch(checked: Boolean, animate: Boolean = true) {
    val tokens = LocalTokens.current
    val travel = tokens.toggleWidth - tokens.toggleHeight
    val target = if (checked) travel else androidx.compose.ui.unit.Dp(0f)
    val animated by animateDpAsState(target, label = "switch thumb")
    val position = if (animate) animated else target
    Box(
        Modifier.size(tokens.toggleWidth, tokens.toggleHeight)
            .background(if (checked) tokens.accent else tokens.widgetTrack, CircleShape)
            .border(tokens.glyphStroke / 2, if (checked) tokens.accent else tokens.quiet, CircleShape),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier.offset(x = position).size(tokens.toggleHeight).padding(tokens.toggleInset)
                .background(if (checked) tokens.onAccent else tokens.quiet, CircleShape)
        )
    }
}

/** A compact rounded action, used for row level buttons such as Allow or Reply. */
@Composable
internal fun PillButton(label: String, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = LocalTokens.current
    Box(
        Modifier.heightIn(min = tokens.pillHeight).clip(CircleShape)
            .background(if (filled) tokens.accent else tokens.widgetTrack)
            .graphicsLayer { alpha = if (enabled) 1f else tokens.disabledOpacity }
            .then(if (enabled) Modifier.appPress(onClick) else Modifier)
            .padding(horizontal = tokens.padding),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = tokens.pillLabel.copy(color = if (filled) tokens.onAccent else tokens.onAccentContainer), maxLines = 1)
    }
}
