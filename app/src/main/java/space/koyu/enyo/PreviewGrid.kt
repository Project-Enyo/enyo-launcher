package space.koyu.enyo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PreviewGrid(openPanel: () -> Unit) {
    val tokens = LocalTokens.current
    var volume by rememberSaveable { mutableFloatStateOf(0.45f) }
    var brightness by rememberSaveable { mutableFloatStateOf(0.65f) }
    val keyboard = WindowInsets.isImeVisible
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(tokens.background)
            .safeDrawingPadding()
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
            .padding(horizontal = tokens.margin)
            .padding(top = tokens.margin, bottom = tokens.bottomClearance)
    ) {
        val tiny = tokens.size == CardSize.Tiny
        val compact = tokens.size == CardSize.CompactHeight
        val density = LocalDensity.current
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = with(density) { tokens.gap.roundToPx() }
        val standard = with(density) { tokens.cardHeight.roundToPx() }
        val strip = with(density) { tokens.stripHeight.roundToPx() }
        val lowerRows = standard * 2 + strip + gap * 3
        val heldTarget = with(density) {
            if (compact && !keyboard) (height - lowerRows).coerceAtLeast(tokens.heldTarget.roundToPx())
            else tokens.heldTarget.roundToPx()
        }
        val scroll = tiny || keyboard || heldTarget + lowerRows > height
        val scrollModifier = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(scrollModifier, verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
            ContentCard(Modifier.height(with(density) { heldTarget.toDp() })) {
                Box(Modifier.padding(tokens.padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.now_playing), style = tokens.state)
                        Text(stringResource(R.string.no_media), style = tokens.secondary)
                    }
                }
            }
            val wireless: @Composable (Modifier) -> Unit = { modifier ->
                DoorCard(
                    stringResource(R.string.wireless),
                    stringResource(R.string.network_detail), openPanel, modifier
                )
            }
            val bluetooth: @Composable (Modifier) -> Unit = { modifier ->
                DoorCard(stringResource(R.string.bluetooth), null, {}, modifier)
            }
            val volumeCard: @Composable (Modifier) -> Unit = { modifier ->
                ControlCard(stringResource(R.string.volume), volume, { volume = it }, modifier)
            }
            val brightnessCard: @Composable (Modifier) -> Unit = { modifier ->
                ControlCard(stringResource(R.string.brightness), brightness, { brightness = it }, modifier)
            }
            if (tiny) {
                wireless(Modifier)
                bluetooth(Modifier)
                volumeCard(Modifier)
                brightnessCard(Modifier)
            } else {
                // Round the gap first. Split the remaining integer pixels and give the remainder to column two.
                val available = (width - gap).coerceAtLeast(0)
                val first = available / 2
                val second = available - first
                val firstColumn = Modifier.width(with(density) { first.toDp() })
                val secondColumn = Modifier.width(with(density) { second.toDp() })
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    wireless(firstColumn)
                    bluetooth(secondColumn)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    volumeCard(firstColumn)
                    brightnessCard(secondColumn)
                }
            }
            ContentCard(Modifier.height(tokens.stripHeight)) {
                Box(Modifier.padding(tokens.padding), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.torch), style = tokens.state, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
