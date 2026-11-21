package space.koyu.enyo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

@Composable
internal fun EnyoSheet(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalTokens.current
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrival.animateTo(1f, tween(tokens.sheetArrivalMillis, easing = FastOutSlowInEasing)) }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val parent = LocalView.current.parent as? DialogWindowProvider
        SideEffect { parent?.window?.setDimAmount(0f) }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.matchParentSize()
                    .background(tokens.background.copy(alpha = tokens.sheetScrim * arrival.value))
                    .clickable(interactionSource = null, indication = null, onClick = close)
            )
            BoxWithConstraints(
                Modifier.fillMaxWidth().safeDrawingPadding().imePadding().padding(tokens.margin),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = tokens.sheetMaxWidth).fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .graphicsLayer {
                            translationY = size.height * (1f - arrival.value)
                            alpha = arrival.value
                        },
                    shape = RoundedCornerShape(tokens.sheetRadius),
                    color = tokens.surface,
                    shadowElevation = tokens.elevation.last()
                ) {
                    Column(
                        Modifier.padding(tokens.padding),
                        verticalArrangement = Arrangement.spacedBy(tokens.gap)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f).semantics { heading() }, style = tokens.state)
                            val label = androidx.compose.ui.res.stringResource(R.string.back)
                            androidx.compose.material3.IconButton(
                                onClick = close,
                                modifier = Modifier.semantics { contentDescription = label }
                            ) { EnyoGlyph(Glyph.Close) }
                        }
                        content()
                    }
                }
            }
        }
    }
}
