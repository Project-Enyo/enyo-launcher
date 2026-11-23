package space.koyu.enyo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.PI
import kotlin.math.sin

/** The palm of Enyo's icon, in the icon's 108 unit space, from enyo_foreground. */
private val palm: Path = PathParser().parsePathString(
    "M49 80 C49 67 53 54 62 40 L67 42 C60 54 58 67 58 80 Z M64 42 C53 29 44 28 34 31 C43 21 58 24 65 36 C69 26 80 25 87 32 " +
        "C77 30 70 34 67 40 C81 34 90 40 92 49 C82 43 73 42 67 44 C79 47 84 54 82 63 C76 53 69 48 64 45 C52 44 44 47 34 54 " +
        "C37 43 48 37 61 40 C50 35 40 36 31 41 C37 30 50 30 64 42 Z"
).toPath()

private fun window(progress: Float, start: Float, end: Float) = ((progress - start) / (end - start)).coerceIn(0f, 1f)

private fun ease(value: Float) = FastOutSlowInEasing.transform(value)

/**
 * Enyo's icon coming to life: the sky fades in, the sun rises from behind the sea, the palm grows
 * from its base and then sways while the sea rolls. Drawn from the icon's own shapes and colors.
 */
@Composable
internal fun AnimatedEnyoIcon(modifier: Modifier = Modifier, delayMillis: Int = 0, assembled: Boolean = false) {
    // Once the icon has come together, later views show it whole and only let it move.
    val appear = remember { Animatable(if (assembled) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (assembled) return@LaunchedEffect
        kotlinx.coroutines.delay(delayMillis.toLong())
        appear.animateTo(1f, tween(1_800, easing = LinearEasing))
    }
    val motion = rememberInfiniteTransition(label = "icon")
    val time by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Restart), label = "iconTime")
    Canvas(modifier) {
        val unit = size.minDimension / 108f
        val progress = appear.value
        val wave = sin(time * 2 * PI.toFloat())
        // Round, like the launcher shows its icons.
        val shape = Path().apply { addOval(androidx.compose.ui.geometry.Rect(0f, 0f, 108f * unit, 108f * unit)) }
        clipPath(shape) {
            // The sky.
            drawRect(
                Brush.verticalGradient(0f to Color(0xFF6600E8), 0.5f to Color(0xFFB600C8), 0.78f to Color(0xFFFF4389), 1f to Color(0xFFFFAA55),
                    startY = 0f, endY = 108f * unit),
                alpha = ease(window(progress, 0f, 0.3f))
            )
            // The sun rises to where the icon has it.
            val rise = ease(window(progress, 0.15f, 0.7f))
            val sunY = (78f + (1f - rise) * 34f) * unit
            drawCircle(
                Brush.verticalGradient(listOf(Color(0xFFFFE17A), Color(0xFFFF9854)), startY = sunY - 27f * unit, endY = sunY + 27f * unit),
                radius = 27f * unit, center = Offset(53f * unit, sunY), alpha = window(progress, 0.15f, 0.35f)
            )
            // The sea, its surface rolling a little.
            val swell = 65f + wave * 2.2f * window(progress, 0.6f, 1f)
            drawPath(Path().apply {
                moveTo(0f, 88f * unit)
                quadraticTo(54f * unit, swell * unit, 108f * unit, 88f * unit)
                lineTo(108f * unit, 108f * unit); lineTo(0f, 108f * unit); close()
            }, Color(0xFF430073), alpha = ease(window(progress, 0f, 0.3f)))
            // The palm grows from its base, then sways in the wind.
            val grow = ease(window(progress, 0.45f, 0.95f))
            if (grow > 0f) {
                val sway = sin(time * 2 * PI.toFloat() + 0.6f) * 3f * window(progress, 0.9f, 1f)
                scale(unit, unit, pivot = Offset.Zero) {
                    // The icon's group: scaled 0.9 around the middle and moved 5 units left.
                    translate(-5f, 0f) {
                        scale(0.9f, 0.9f, pivot = Offset(54f, 54f)) {
                            rotate(sway, pivot = Offset(53.5f, 80f)) {
                                scale(0.55f + 0.45f * grow, 0.55f + 0.45f * grow, pivot = Offset(53.5f, 80f)) {
                                    drawPath(palm, Brush.linearGradient(listOf(Color.White, Color(0xFFF9D4F9)), start = Offset.Zero, end = Offset(108f, 108f)),
                                        alpha = grow)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
