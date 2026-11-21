package space.koyu.enyo

import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.util.LruCache
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
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal val LocalOriginalIcons = staticCompositionLocalOf { false }

internal enum class Glyph { Today, Apps, Cards, Inbox, Quick, Search, Settings, Messages, Calculator, Folder, Camera, Audio, Dhl, Transit }

private data class IconKey(
    val component: android.content.ComponentName,
    val source: String,
    val pixelSize: Int,
    val density: Int,
    val original: Boolean,
    val color: Int
)

private val fullColorBrands = setOf(
    "de.mcdonalds.mcdonaldsinfoapp", "com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go"
)

private val iconCache = object : LruCache<IconKey, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: IconKey, value: Bitmap): Int = value.byteCount
}
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val iconDispatcher = Dispatchers.Default.limitedParallelism(2)

@Composable
internal fun AppIcon(app: LauncherActivityInfo, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    val density = LocalDensity.current
    val originals = LocalOriginalIcons.current
    val component = app.componentName
    val size = with(density) { tokens.iconSize.roundToPx() }
    val originalSize = (160 * density.density).roundToInt()
    val arches = component.packageName == "de.mcdonalds.mcdonaldsinfoapp"
    val brand = component.packageName in fullColorBrands
    val dedicated = when (component.packageName) {
        "com.android.settings" -> Glyph.Settings
        "com.android.calculator2" -> Glyph.Calculator
        "com.android.documentsui", "com.google.android.documentsui" -> Glyph.Folder
        "app.grapheneos.camera" -> Glyph.Camera
        "de.dhl.paket" -> Glyph.Dhl
        transitPackage -> Glyph.Transit
        else -> null
    }
    val key = IconKey(component, app.applicationInfo.sourceDir, size, originalSize, originals, tokens.ink.toArgb())
    val icon by produceState(iconCache.get(key), key) {
        value = if (!originals && (dedicated != null || arches)) null else withContext(iconDispatcher) {
            iconCache.get(key) ?: runCatching {
                val source = app.getIcon(originalSize).mutate()
                val monochrome = if (!originals && !brand && Build.VERSION.SDK_INT >= 33 && source is AdaptiveIconDrawable) {
                    source.monochrome?.mutate()?.apply { setTint(tokens.ink.toArgb()) }
                } else null
                val foreground = if (!originals && source is AdaptiveIconDrawable) source.foreground?.mutate() else null
                val drawable = monochrome ?: foreground ?: source
                val padding = when {
                    originals -> 0
                    monochrome != null || foreground != null -> (size * tokens.iconMonoPadding).roundToInt()
                    else -> (size * tokens.iconPadding).roundToInt()
                }
                if (!originals && !brand && monochrome == null) {
                    drawable.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(tokens.iconSaturation) })
                }
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(padding, padding, size - padding, size - padding)
                    drawable.draw(android.graphics.Canvas(it))
                    if (!originals && source !is AdaptiveIconDrawable) {
                        extractLegacyForeground(it, padding, tokens.iconBackgroundTolerance)
                    }
                    iconCache.put(key, it)
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
            !originals && arches -> Image(
                painterResource(R.drawable.mcdonalds_arches), null, Modifier.size(tokens.appGlyphSize)
            )
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

private fun extractLegacyForeground(bitmap: Bitmap, padding: Int, tolerance: Int) {
    val edge = bitmap.width - padding - 1
    if (edge <= padding) return
    val width = bitmap.width
    val pixels = IntArray(width * bitmap.height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, bitmap.height)
    val seed = pixels[padding * width + padding]
    fun delta(value: Int, shift: Int) = kotlin.math.abs((value ushr shift and 255) - (seed ushr shift and 255))
    fun near(value: Int): Boolean =
        value ushr 24 >= 245 && delta(value, 0) <= tolerance &&
            delta(value, 8) <= tolerance && delta(value, 16) <= tolerance
    val corners = listOf(padding to padding, edge to padding, padding to edge, edge to edge)
    if (!corners.all { (x, y) -> near(pixels[y * width + x]) }) return
    val visited = BooleanArray(pixels.size)
    val pending = ArrayDeque<Int>()
    fun add(x: Int, y: Int) {
        if (x !in padding..edge || y !in padding..edge) return
        val i = y * width + x
        if (!visited[i] && near(pixels[i])) {
            visited[i] = true
            pending.add(i)
        }
    }
    for (i in padding..edge) {
        add(i, padding)
        add(i, edge)
        add(padding, i)
        add(edge, i)
    }
    var removed = 0
    while (pending.isNotEmpty()) {
        val i = pending.removeFirst()
        val x = i % width
        val y = i / width
        removed++
        add(x - 1, y)
        add(x + 1, y)
        add(x, y - 1)
        add(x, y + 1)
    }
    val area = (edge - padding + 1) * (edge - padding + 1)
    if (removed < area / 8 || removed > area * 19 / 20) return
    for (i in pixels.indices) if (visited[i]) pixels[i] = 0
    bitmap.setPixels(pixels, 0, width, 0, 0, width, bitmap.height)
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
                val gear = Path().apply {
                    val radii = listOf(.31f, .31f, .43f, .43f, .31f, .31f)
                    for (tooth in 0 until 8) {
                        radii.forEachIndexed { index, radius ->
                            val angle = (tooth + index / radii.size.toDouble()) * Math.PI / 4
                            val x = .50f + radius * kotlin.math.cos(angle).toFloat()
                            val y = .50f + radius * kotlin.math.sin(angle).toFloat()
                            val p = point(x, y)
                            if (tooth == 0 && index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                        }
                    }
                    close()
                }
                drawPath(gear, color, style = Stroke(stroke))
                circle(.50f, .50f, .15f)
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
            Glyph.Dhl -> {
                box(.36f, .24f, .89f, .76f)
                line(.61f, .25f, .61f, .48f)
                line(.13f, .39f, .29f, .39f)
                line(.07f, .52f, .30f, .52f)
                line(.15f, .65f, .29f, .65f)
            }
            Glyph.Transit -> {
                box(.24f, .17f, .76f, .73f)
                line(.25f, .48f, .75f, .48f)
                line(.37f, .74f, .28f, .87f)
                line(.63f, .74f, .72f, .87f)
                circle(.37f, .61f, .05f, true)
                circle(.63f, .61f, .05f, true)
            }
            Glyph.Audio -> { line(.43f, .23f, .43f, .71f); line(.43f, .23f, .78f, .18f); circle(.30f, .72f, .13f, true) }
        }
    }
}
