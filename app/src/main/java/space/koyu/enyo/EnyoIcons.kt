package space.koyu.enyo

import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal val LocalOriginalIcons = staticCompositionLocalOf { false }

internal enum class Glyph { Today, Apps, Cards, Inbox, Quick, Search, Settings, Messages, Calculator, Folder, Camera, Audio }

@Composable
internal fun AppIcon(app: LauncherActivityInfo, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    val density = LocalDensity.current
    val originals = LocalOriginalIcons.current
    val component = app.componentName
    val size = with(density) { tokens.iconSize.roundToPx() }
    val originalSize = (160 * density.density).roundToInt()
    val dedicated = when (component.packageName) {
        "com.android.settings" -> Glyph.Settings
        "com.android.calculator2" -> Glyph.Calculator
        "com.android.documentsui", "com.google.android.documentsui" -> Glyph.Folder
        "app.grapheneos.camera" -> Glyph.Camera
        else -> null
    }
    val icon by produceState<Bitmap?>(null, component, size, originals, tokens.ink) {
        value = if (!originals && dedicated != null) null else withContext(Dispatchers.Default) {
            runCatching {
                val source = app.getIcon(originalSize).mutate()
                val original = source
                val symbol = if (!originals && Build.VERSION.SDK_INT >= 33 && source is AdaptiveIconDrawable) {
                    source.monochrome?.mutate()?.apply { setTint(tokens.ink.toArgb()) }
                } else null
                val drawable = symbol ?: original
                val padding = when {
                    originals -> 0
                    symbol != null -> (size * tokens.iconMonoPadding).roundToInt()
                    else -> (size * tokens.iconPadding).roundToInt()
                }
                if (!originals && symbol == null) {
                    drawable.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(tokens.iconSaturation) })
                }
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(padding, padding, size - padding, size - padding)
                    drawable.draw(android.graphics.Canvas(it))
                }
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(tokens.iconRadius)
    Box(
        modifier.size(tokens.iconSize)
            .then(if (originals) Modifier else Modifier.clip(shape).background(tokens.accentContainer)),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = icon
        when {
            !originals && dedicated != null -> EnyoGlyph(dedicated, Modifier.size(tokens.appGlyphSize))
            bitmap != null -> Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize())
            else -> {
                val label = app.label.toString().trim()
                val letter = label.codePoints().findFirst().orElse('?'.code)
                Text(String(Character.toChars(letter)).uppercase(), style = tokens.state)
            }
        }
    }
}

@Composable
internal fun EnyoGlyph(glyph: Glyph, modifier: Modifier = Modifier, color: Color = LocalTokens.current.ink) {
    val tokens = LocalTokens.current
    Canvas(modifier.size(tokens.glyphSize)) {
        fun point(x: Float, y: Float) = Offset(x * size.width, y * size.height)
        val stroke = tokens.glyphStroke.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(color, point(x1, y1), point(x2, y2), stroke, cap = StrokeCap.Round)
        }
        fun circle(x: Float, y: Float, radius: Float, fill: Boolean = false) {
            if (fill) drawCircle(color, radius * size.width, point(x, y))
            else drawCircle(color, radius * size.width, point(x, y), style = Stroke(stroke))
        }
        fun box(left: Float, top: Float, right: Float, bottom: Float) {
            drawRoundRect(
                color,
                topLeft = point(left, top),
                size = Size((right - left) * size.width, (bottom - top) * size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.10f),
                style = Stroke(stroke)
            )
        }
        when (glyph) {
            Glyph.Today -> {
                circle(.50f, .50f, .20f)
                for (index in 0 until 8) {
                    val angle = index * Math.PI / 4
                    val x = kotlin.math.cos(angle).toFloat()
                    val y = kotlin.math.sin(angle).toFloat()
                    line(.50f + .32f * x, .50f + .32f * y, .50f + .42f * x, .50f + .42f * y)
                }
            }
            Glyph.Apps -> for (x in listOf(.29f, .71f)) for (y in listOf(.29f, .71f)) circle(x, y, .11f, true)
            Glyph.Cards -> {
                box(.25f, .23f, .80f, .84f)
                line(.14f, .68f, .14f, .22f)
                line(.14f, .22f, .59f, .12f)
            }
            Glyph.Inbox -> {
                box(.13f, .23f, .87f, .80f)
                line(.14f, .32f, .50f, .57f)
                line(.50f, .57f, .86f, .32f)
            }
            Glyph.Quick -> {
                line(.16f, .28f, .84f, .28f); circle(.41f, .28f, .105f, true)
                line(.16f, .52f, .84f, .52f); circle(.66f, .52f, .105f, true)
                line(.16f, .76f, .84f, .76f); circle(.36f, .76f, .105f, true)
            }
            Glyph.Search -> { circle(.43f, .43f, .25f); line(.62f, .62f, .82f, .82f) }
            Glyph.Settings -> {
                circle(.50f, .50f, .27f); circle(.50f, .50f, .10f)
                line(.50f, .11f, .50f, .23f); line(.50f, .77f, .50f, .89f)
                line(.11f, .50f, .23f, .50f); line(.77f, .50f, .89f, .50f)
            }
            Glyph.Messages -> {
                box(.14f, .20f, .86f, .70f)
                line(.30f, .70f, .23f, .86f); line(.23f, .86f, .46f, .70f)
                circle(.33f, .45f, .045f, true)
                circle(.50f, .45f, .045f, true)
                circle(.67f, .45f, .045f, true)
            }
            Glyph.Calculator -> {
                box(.24f, .13f, .76f, .87f)
                line(.34f, .36f, .66f, .36f)
                for (x in listOf(.38f, .62f)) for (y in listOf(.55f, .71f)) circle(x, y, .055f, true)
            }
            Glyph.Folder -> {
                val path = Path().apply {
                    moveTo(point(.11f, .76f).x, point(.11f, .76f).y)
                    for ((x, y) in listOf(.11f to .26f, .36f to .26f, .45f to .36f, .88f to .36f, .88f to .76f)) {
                        val p = point(x, y)
                        lineTo(p.x, p.y)
                    }
                    close()
                }
                drawPath(path, color, style = Stroke(stroke))
            }
            Glyph.Camera -> { box(.12f, .32f, .88f, .79f); circle(.50f, .55f, .15f); line(.32f, .22f, .62f, .22f) }
            Glyph.Audio -> { line(.43f, .23f, .43f, .71f); line(.43f, .23f, .78f, .18f); circle(.30f, .72f, .13f, true) }
        }
    }
}
