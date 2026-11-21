package space.koyu.enyo

import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

internal val LocalOriginalIcons = staticCompositionLocalOf { false }
internal val LocalBrandIcons = compositionLocalOf { emptyMap<String, android.graphics.drawable.Icon>() }

internal enum class Glyph(val drawable: Int) {
    Today(R.drawable.tabler_sun),
    Apps(R.drawable.tabler_apps),
    Cards(R.drawable.tabler_cards),
    Inbox(R.drawable.tabler_inbox),
    Quick(R.drawable.tabler_adjustments_horizontal),
    Search(R.drawable.tabler_search),
    Settings(R.drawable.tabler_settings),
    Messages(R.drawable.tabler_messages),
    Calculator(R.drawable.tabler_calculator),
    Folder(R.drawable.tabler_folder),
    Camera(R.drawable.tabler_camera),
    Dhl(R.drawable.tabler_truck_delivery),
    Transit(R.drawable.tabler_train),
    Back(R.drawable.tabler_arrow_left),
    Forward(R.drawable.tabler_chevron_right),
    Close(R.drawable.tabler_x),
    Location(R.drawable.tabler_map_pin),
    LocationOff(R.drawable.tabler_map_pin_off),
    Cloud(R.drawable.tabler_cloud),
    CloudFog(R.drawable.tabler_cloud_fog),
    CloudRain(R.drawable.tabler_cloud_rain),
    CloudSnow(R.drawable.tabler_cloud_snow),
    CloudStorm(R.drawable.tabler_cloud_storm),
    Battery(R.drawable.tabler_battery),
    Music(R.drawable.tabler_music),
    Play(R.drawable.tabler_player_play),
    Pause(R.drawable.tabler_player_pause),
    PreviousTrack(R.drawable.tabler_player_skip_back),
    NextTrack(R.drawable.tabler_player_skip_forward),
}

private data class IconKey(
    val component: android.content.ComponentName,
    val source: String,
    val pixelSize: Int,
    val density: Int,
    val original: Boolean,
    val color: Int,
    val notificationSource: Int
)

private val iconCache = object : LruCache<IconKey, androidx.compose.ui.graphics.ImageBitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: IconKey, value: androidx.compose.ui.graphics.ImageBitmap): Int = value.width * value.height * 4
}
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val iconDispatcher = Dispatchers.Default.limitedParallelism(1)

@Composable
internal fun AppIcon(app: LauncherActivityInfo, modifier: Modifier = Modifier, displaySize: androidx.compose.ui.unit.Dp = LocalTokens.current.iconSize, label: String? = null) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val originals = LocalOriginalIcons.current
    val component = app.componentName
    val size = with(density) { tokens.iconSize.roundToPx() }
    val originalSize = (160 * density.density).roundToInt()
    val arches = component.packageName == "de.mcdonalds.mcdonaldsinfoapp"
    val brand = component.packageName in brandedNotificationApps
    val mark = if (!originals && brand) LocalBrandIcons.current[component.packageName] else null
    val noteSource = if (mark?.type == android.graphics.drawable.Icon.TYPE_RESOURCE) mark.resId else mark?.hashCode() ?: 0
    val dedicated = dedicatedGlyph(component.packageName)
    val key = IconKey(component, app.applicationInfo.sourceDir,
        size, originalSize, originals, if (originals) 0 else tokens.ink.toArgb(), noteSource)
    val cached = remember(key) { iconCache.get(key) }
    val icon = if (cached != null || (!originals && (dedicated != null || arches))) cached
    else produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, key) {
        value = withContext(iconDispatcher) { renderIcon(app, key, tokens, context, mark, brand) }
    }.value
    val shape = RoundedCornerShape(tokens.iconRadius)
    Box(
        modifier.size(displaySize)
            .then(if (originals) Modifier else Modifier
                .background(if (brand || arches) tokens.brandSurface else tokens.accentContainer, shape)),
        contentAlignment = Alignment.Center
    ) {
        when {
            !originals && arches -> Image(
                painterResource(R.drawable.mcdonalds_arches), null, Modifier.size(minOf(tokens.appGlyphSize, displaySize * 0.55f)),
                colorFilter = ColorFilter.tint(tokens.brandInk)
            )
            !originals && dedicated != null -> EnyoGlyph(dedicated, Modifier.size(minOf(tokens.appGlyphSize, displaySize * 0.55f)))
            icon != null -> Image(
                icon, null, Modifier.fillMaxSize(),
                colorFilter = if (!originals && brand) ColorFilter.tint(tokens.brandInk) else null
            )
            else -> {
                val fallback = (label ?: app.label.toString()).trim()
                val letter = fallback.codePoints().findFirst().orElse('?'.code)
                Text(String(Character.toChars(letter)).uppercase(), style = tokens.state)
            }
        }
    }
}

private fun dedicatedGlyph(packageName: String) = when (packageName) {
    "com.android.settings" -> Glyph.Settings
    "com.android.calculator2" -> Glyph.Calculator
    "com.android.documentsui", "com.google.android.documentsui" -> Glyph.Folder
    "app.grapheneos.camera" -> Glyph.Camera
    "de.dhl.paket" -> Glyph.Dhl
    transitPackage -> Glyph.Transit
    else -> null
}

@Composable
internal fun WarmAppIcons(apps: List<LauncherActivityInfo>) {
    val tokens = LocalTokens.current
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val originals = LocalOriginalIcons.current
    val size = with(density) { tokens.iconSize.roundToPx() }
    val dpi = (160 * density.density).roundToInt()
    val color = if (originals) 0 else tokens.ink.toArgb()
    LaunchedEffect(apps, size, dpi, originals, color) {
        withContext(iconDispatcher) {
            for (app in apps.take(iconCache.maxSize() / (size * size * 8))) {
                currentCoroutineContext().ensureActive()
                val component = app.componentName
                val pkg = component.packageName
                if (!originals && (dedicatedGlyph(pkg) != null ||
                        pkg == "de.mcdonalds.mcdonaldsinfoapp" || pkg in brandedNotificationApps)) continue
                val key = IconKey(component, app.applicationInfo.sourceDir,
                    size, dpi, originals, color, 0)
                renderIcon(app, key, tokens, context, null, false)
                yield()
            }
        }
    }
}

private fun renderIcon(
    app: LauncherActivityInfo,
    key: IconKey,
    tokens: Tokens,
    context: android.content.Context,
    mark: android.graphics.drawable.Icon?,
    brand: Boolean
) = iconCache.get(key) ?: runCatching {
    val size = key.pixelSize
    val originals = key.original
    val source = app.getIcon(key.density).mutate()
    val monochrome = mark?.loadDrawable(context)?.mutate() ?:
        if (!originals && Build.VERSION.SDK_INT >= 33 && source is AdaptiveIconDrawable) {
            source.monochrome?.mutate()?.apply { setTint(key.color) }
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
        val canvas = android.graphics.Canvas(it)
        if (!originals) {
            val radius = size * tokens.iconRasterCorner
            val outline = android.graphics.Path().apply {
                addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(outline)
        }
        drawable.draw(canvas)
        if (!originals && source !is AdaptiveIconDrawable) {
            extractLegacyForeground(it, padding, tokens.iconBackgroundTolerance)
        }
        it.prepareToDraw()
    }.asImageBitmap().also { iconCache.put(key, it) }
}.getOrNull()

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
    val pending = IntArray(pixels.size)
    var head = 0
    var tail = 0
    fun add(x: Int, y: Int) {
        if (x !in padding..edge || y !in padding..edge) return
        val i = y * width + x
        if (!visited[i] && near(pixels[i])) {
            visited[i] = true
            pending[tail++] = i
        }
    }
    for (i in padding..edge) {
        add(i, padding)
        add(i, edge)
        add(padding, i)
        add(edge, i)
    }
    var removed = 0
    while (head < tail) {
        val i = pending[head++]
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
internal fun EnyoGlyph(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    color: Color = LocalTokens.current.ink,
    description: String? = null
) {
    Image(
        painter = painterResource(glyph.drawable),
        contentDescription = description,
        modifier = modifier.size(LocalTokens.current.glyphSize),
        colorFilter = ColorFilter.tint(color)
    )
}
