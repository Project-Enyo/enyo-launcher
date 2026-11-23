package space.koyu.enyo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.withSaveLayer

/**
 * Fades scrolling content out at the top and bottom edges. Only the edge strips go through an offscreen layer.
 * The conditions are read while drawing, so scrolling redraws the fade without recomposing.
 */
@Composable
internal fun Modifier.edgeFade(top: () -> Boolean = { true }, bottom: () -> Boolean = { true }): Modifier {
    val tokens = LocalTokens.current
    val layer = rememberGraphicsLayer()
    val fadeTop by rememberUpdatedState(top)
    val fadeBottom by rememberUpdatedState(bottom)
    return drawWithCache {
        val edge = minOf(tokens.appEdgeFade.roundToPx(), size.height.toInt() / 3).toFloat()
        val middleBottom = size.height - edge
        val topBounds = Rect(0f, 0f, size.width, edge)
        val bottomBounds = Rect(0f, middleBottom, size.width, size.height)
        val topMask = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = edge)
        val bottomMask = Brush.verticalGradient(
            listOf(Color.Black, Color.Transparent), startY = middleBottom, endY = size.height
        )
        val paint = Paint()
        onDrawWithContent {
            val atTop = edge > 0f && fadeTop()
            val atBottom = edge > 0f && fadeBottom()
            if (!atTop && !atBottom) {
                drawContent()
                return@onDrawWithContent
            }
            layer.record { this@onDrawWithContent.drawContent() }
            clipRect(top = if (atTop) edge else 0f, bottom = if (atBottom) middleBottom else size.height) {
                drawLayer(layer)
            }
            fun drawFadedEdge(bounds: Rect, mask: Brush) {
                clipRect(top = bounds.top, bottom = bounds.bottom) {
                    drawContext.canvas.withSaveLayer(bounds, paint) {
                        drawLayer(layer)
                        drawRect(mask, topLeft = bounds.topLeft, size = bounds.size, blendMode = BlendMode.DstIn)
                    }
                }
            }
            if (atTop) drawFadedEdge(topBounds, topMask)
            if (atBottom) drawFadedEdge(bottomBounds, bottomMask)
        }
    }
}
