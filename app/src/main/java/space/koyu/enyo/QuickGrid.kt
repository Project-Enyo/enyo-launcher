package space.koyu.enyo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickGrid(
    ignoreIme: Boolean,
    hasSeekBar: Boolean,
    held: @Composable (Modifier) -> Unit,
    rows: List<List<@Composable (Modifier) -> Unit>>,
    strip: (@Composable (Modifier) -> Unit)?
) {
    val tokens = LocalTokens.current
    val keyboard = !ignoreIme && WindowInsets.isImeVisible
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(tokens.margin)
    ) {
        val tiny = tokens.size == CardSize.Tiny
        val compact = tokens.size == CardSize.CompactHeight
        val density = LocalDensity.current
        val height = constraints.maxHeight
        val width = constraints.maxWidth
        val gap = with(density) { tokens.gap.roundToPx() }
        val standard = with(density) { tokens.cardHeight.roundToPx() }
        val stripHeight = if (strip == null) 0 else with(density) { tokens.stripHeight.roundToPx() } + gap
        val lowerRows = rows.size * (standard + gap) + stripHeight
        val heldHeight = with(density) {
            val defaultHeight = if (tiny) tokens.mediaTinyHeight else tokens.heldTarget
            val minimum = (if (hasSeekBar) maxOf(defaultHeight, tokens.mediaSeekHeight) else defaultHeight).roundToPx()
            if (compact && !keyboard) (height - lowerRows).coerceAtLeast(minimum) else minimum
        }
        val scroll = tiny || keyboard || heldHeight + lowerRows > height
        val scrollModifier = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(scrollModifier, verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
            held(Modifier.height(with(density) { heldHeight.toDp() }))
            if (tiny) rows.flatten().forEach { it(Modifier) }
            else {
                // Round the gap first. Split the remaining integer pixels and give the remainder to column two.
                val available = (width - gap).coerceAtLeast(0)
                val first = available / 2
                val second = available - first
                val widths = with(density) { listOf(first.toDp(), second.toDp()) }
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                        row.forEachIndexed { index, card -> card(Modifier.width(widths[index])) }
                    }
                }
            }
            strip?.invoke(Modifier.height(tokens.stripHeight))
        }
    }
}
