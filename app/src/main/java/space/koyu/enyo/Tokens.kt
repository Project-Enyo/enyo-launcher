package space.koyu.enyo

import android.app.WallpaperManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class CardSize { Expanded, Medium, CompactHeight, Tiny }

// ADB measured 1080 by 2400 pixels, 420 dpi, and about 411 by 914 dp on API 37.
// The status source was 132 pixels and the inactive navigation source was 63 pixels.
// Both ADB transports reported the same handset. No Enyo install was present.
// The future square displays remain unverified.
internal fun resolveCardSize(width: Dp, height: Dp): CardSize = when {
    minOf(width, height) >= 600.dp -> CardSize.Expanded
    minOf(width, height) >= 480.dp -> CardSize.Medium
    minOf(width, height) >= 360.dp -> CardSize.CompactHeight
    else -> CardSize.Tiny
}

/** Colors from the system dynamic scheme, so Enyo follows the wallpaper derived system tint. */
internal data class SystemPalette(
    val accent: Color,
    val container: Color,
    val onAccent: Color,
    val onContainer: Color,
    val background: Color,
    val surface: Color,
    val quiet: Color,
    val ink: Color
)

internal fun systemPalette(context: Context, dark: Boolean): SystemPalette? = runCatching {
    val colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    SystemPalette(
        colors.primary, colors.primaryContainer, colors.onPrimary, colors.onPrimaryContainer,
        colors.background, colors.surfaceContainerHigh, colors.onSurfaceVariant, colors.onSurface
    ).takeIf { palette ->
        listOf(palette.accent, palette.container, palette.background, palette.surface)
            .all { it.isSpecified && it.alpha > 0f }
    }
}.getOrNull()

@Composable
internal fun rememberTokens(dark: Boolean, size: CardSize): Tokens {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var wallpaperRevision by remember(context) { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val wallpaper = WallpaperManager.getInstance(context)
        val listener = WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) wallpaperRevision++
        }
        val listening = runCatching { wallpaper.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper())) }.isSuccess
        onDispose { if (listening) runCatching { wallpaper.removeOnColorsChangedListener(listener) } }
    }
    val palette = remember(context, configuration, dark, wallpaperRevision) { systemPalette(context, dark) }
    return remember(dark, size, palette) { Tokens(dark, size, palette) }
}

internal class Tokens(private val dark: Boolean, val size: CardSize, palette: SystemPalette? = null) {
    private fun tone(light: Long, night: Long) = Color(if (dark) night else light)
    private fun dimension(expanded: Int, medium: Int, compact: Int, tiny: Int = compact) =
        listOf(expanded, medium, compact, tiny)[size.ordinal]

    // The fixed warm tones only apply when the system provides no dynamic scheme.
    val background = palette?.background ?: tone(0xFFF6F3E9, 0xFF161711)
    val surface = palette?.surface ?: tone(0xFFEEE9DB, 0xFF28291F)
    val quiet = palette?.quiet ?: tone(0xFF5B5749, 0xFFBEB8A5)
    val ink = palette?.ink ?: tone(0xFF29271D, 0xFFF7F1E0)
    val accent = palette?.accent ?: tone(0xFF3F6338, 0xFFAACD97)
    val accentContainer = palette?.container ?: tone(0xFFDCE8CA, 0xFF34482C)
    val onAccent = palette?.onAccent ?: background
    val onAccentContainer = palette?.onContainer ?: ink
    val widgetSurface = lerp(surface, accentContainer, 0.78f)
    val widgetTrack = accent.copy(alpha = 0.22f)
    val dockSurface = lerp(surface, accentContainer, 0.48f)
    val settingsBackground = lerp(background, accentContainer, 0.40f)
    val wallpaperTint = lerp(Color.Black, accent, 0.42f).copy(alpha = 0.14f)

    val margin = dimension(20, 16, 12).dp
    val gap = dimension(12, 10, 8).dp
    val padding = dimension(16, 14, 12).dp
    val radius = dimension(28, 24, 20).dp
    val cardHeight = dimension(144, 96, 84, 96).dp
    val stripHeight = dimension(88, 72, 64, 96).dp
    val heldTarget = dimension(288, 192, 128, 96).dp
    val cover = dimension(176, 160, 184, 80).dp
    val mediaTinyHeight = 136.dp
    val mediaSeekHeight = 184.dp
    val mediaCompactCover = dimension(96, 80, 72, 52).dp
    val mediaTitle = TextStyle(fontSize = dimension(24, 22, 21, 17).sp, fontWeight = FontWeight.SemiBold, color = ink)
    val touch = 48.dp
    val bottomClearance = 24.dp
    val chevronSize = 12.dp
    val stroke = dimension(4, 4, 3).dp
    val mediaControlSize = 52.dp
    val mediaControlGlyph = 32.dp
    val mediaWaveAmplitude = 1.5.dp
    val mediaWaveLength = 20.dp
    val mediaWaveCycleMillis = 2_500L
    val mediaWaveTransitionPeriods = 1.5f
    val mediaSeekThumbHeight = 12.dp
    val mediaSeekThumbGap = 4.dp
    val mediaTrackOpacity = 0.30f
    val mediaProgressHeight = stroke + mediaWaveAmplitude * 2
    val navigationDotSize = 4.dp
    val navigationDotSpace = 8.dp
    val focusStroke = 2.dp
    val focusOffset = 2.dp
    val hoverOpacity = 0.45f
    val appEdgeFade = 18.dp
    val toggleWidth = 48.dp
    val toggleHeight = 26.dp
    val toggleInset = 4.dp
    val wallpaperThumbnail = 108.dp
    val wallpaperDeleteBubble = 26.dp
    val todayWidgetHeight = dimension(144, 112, 108, 112).dp
    val notificationTrayHeight = dimension(240, 210, 180, 148).dp
    // One type scale for every surface: 24 title, 18 headline, 16 body, 14 supporting, 13 caption.
    // Nothing in Enyo is set below 12 sp.
    val settingsTitle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = ink)
    val rowTitle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = onAccentContainer)
    val rowSummary = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = quiet)
    val pillLabel = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = onAccentContainer)
    val caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = quiet)
    val articleTitle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = ink)
    val articleBody = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, color = ink)
    val listRowHeight = 56.dp
    val listRowPadding = 8.dp
    val listLeading = 40.dp
    val listCardInset = 6.dp
    val listLabelGap = 6.dp
    val listAvatar = 40.dp
    val pillHeight = 34.dp
    val disabledOpacity = 0.38f
    val noticeAppIcon = 24.dp
    val todayCellMinimum = 88.dp
    val todayColumnsMinimum = 4
    val todayColumnsMaximum = 6
    val todayRemoveZone = 72.dp
    val todayHandleReach = 28.dp
    val todayHandle = 16.dp
    val todayLiftScale = 1.04f
    val widgetPreview = 64.dp
    val newsThumbnail = 72.dp
    val noteEditorHeight = 180.dp
    val unreadDot = 12.dp
    val inboxThreadPreview = 8
    val threadAppIcon = 16.dp
    val bubbleMaxWidth = 300.dp
    val widgetPickerHeight = 420.dp
    val noticeDismissGlyph = 18.dp
    val folderMaxHeight = 300.dp
    val dockHoldMillis = 500L
    val controlHoldMillis = 250L
    val dockIconSize = 46.dp
    val dockBottomSpace = 36.dp
    val dockInnerPadding = 7.dp
    val dockOpacity = 0.93f
    val dockBorderOpacity = 0.20f
    val sheetScrim = 0.36f
    val searchScrim = 0.78f
    val searchPullDistance = 84.dp
    val searchPullAnimationMillis = 90
    val sheetArrivalMillis = 230
    val settingsTransitionMillis = 280
    val sheetMaxWidth = 480.dp
    val sheetRadius = dimension(30, 26, 24).dp
    val dockCellWidth = dimension(68, 62, 58, 48).dp
    val appCellWidth = dimension(94, 86, 78, 68).dp
    val appCellHeight = dimension(100, 96, 88, 80).dp
    val iconSize = 56.dp
    val appGlyphSize = 30.dp
    val iconRadius = 9.dp
    val artworkRadius = 18.dp
    val glyphSize = dimension(22, 21, 20, 16).dp
    val glyphStroke = 1.7.dp
    val iconPadding = 0.14f
    val iconRasterCorner = iconRadius / iconSize
    val iconMonoPadding = 0.04f
    val iconSaturation = 0.64f
    val iconBackgroundTolerance = 20
    val recentVisible = 3
    val recentCarouselGap = 20.dp
    val recentEdgeScrollZone = 56.dp
    val recentEdgeScrollSpeed = 560.dp
    val stackDropCenterFraction = 0.24f
    val stackSpread = 116.dp
    val stackLift = 10.dp
    val stackFanDrop = dimension(18, 16, 14).dp
    val stackFanSideFraction = 0.68f
    val stackSpreadFraction = 0.46f
    val stackExpandedWidth = 0.82f
    val recentWidthFraction = 0.66f
    val recentHeightFraction = 0.38f
    val recentHeightUse = 0.85f
    val recentDefaultAspect = 0.66f
    val recentTallAspect = 0.45f
    val recentSquareAspect = 0.9f
    val recentRotation = 4f
    val recentOffset = 0.16f
    val recentDismissFraction = 0.23f
    val recentExitMillis = 350
    val recentFlingVelocity = 850.dp
    val recentFlingExitMillis = 180
    val elevation = listOf(1.dp, dimension(3, 2, 2).dp, dimension(8, 6, 5).dp)

    val state = TextStyle(fontSize = dimension(20, 18, 18).sp, lineHeight = dimension(26, 24, 24).sp, fontWeight = FontWeight.SemiBold, color = ink)
    val secondary = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = quiet)
    val label = TextStyle(
        fontSize = dimension(13, 13, 12).sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        color = quiet
    )
    val value = TextStyle(fontSize = dimension(36, 32, 28).sp, fontFeatureSettings = "tnum", color = ink)
}

internal val LocalTokens = staticCompositionLocalOf<Tokens> { error("Missing Enyo tokens") }
