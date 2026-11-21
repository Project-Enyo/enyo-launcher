package space.koyu.enyo

import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*

internal fun Modifier.cardInput(
    ownFocusTarget: Boolean = false,
    activate: (() -> Unit)? = null
): Modifier = composed {
    val tokens = LocalTokens.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }

    this
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent {
            val activation = it.key == Key.Enter || it.key == Key.NumPadEnter || it.key == Key.Spacebar
            if (!focused || !activation || activate == null) false
            else {
                if (it.type == KeyEventType.KeyUp) activate()
                true
            }
        }
        .hoverable(hover)
        .drawWithContent {
            drawContent()
            if (focused || hovered) {
                val stroke = tokens.focusStroke.toPx()
                val outside = tokens.focusOffset.toPx()
                drawRoundRect(
                    color = tokens.accent.copy(alpha = if (focused) 1f else tokens.hoverOpacity),
                    topLeft = Offset(-outside, -outside),
                    size = Size(size.width + outside * 2, size.height + outside * 2),
                    cornerRadius = CornerRadius(tokens.radius.toPx() + outside),
                    style = Stroke(stroke)
                )
            }
        }
        .then(if (ownFocusTarget) Modifier.focusable() else Modifier)
}
