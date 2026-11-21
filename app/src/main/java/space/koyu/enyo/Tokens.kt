package space.koyu.enyo

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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

internal class Tokens(private val dark: Boolean, val size: CardSize) {
    private fun tone(light: Long, night: Long) = Color(if (dark) night else light)
    private fun dimension(expanded: Int, medium: Int, compact: Int, tiny: Int = compact) =
        listOf(expanded, medium, compact, tiny)[size.ordinal]

    val background = tone(0xFFF6F3E9, 0xFF161711)
    val surface = tone(0xFFEEE9DB, 0xFF28291F)
    val quiet = tone(0xFF5B5749, 0xFFBEB8A5)
    val ink = tone(0xFF29271D, 0xFFF7F1E0)
    val accent = tone(0xFF3F6338, 0xFFAACD97)
    val accentContainer = tone(0xFFDCE8CA, 0xFF34482C)

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
    val mediaControlGlyph = 28.dp
    val mediaProgressStroke = 4.dp
    val mediaWaveAmplitude = 2.5.dp
    val mediaWaveLength = 18.dp
    val mediaWaveCycleMillis = 900L
    val mediaProgressHeight = mediaProgressStroke + mediaWaveAmplitude * 2
    val navigationDotSize = 4.dp
    val navigationDotSpace = 8.dp
    val focusStroke = 2.dp
    val focusOffset = 2.dp
    val hoverOpacity = 0.45f
    val wallpaperEdgeAlpha = if (dark) 0.84f else 0.87f
    val wallpaperCenterAlpha = if (dark) 0.40f else 0.36f
    val appEdgeFade = 18.dp
    val toggleWidth = 48.dp
    val toggleHeight = 26.dp
    val toggleInset = 4.dp
    val wallpaperThumbnail = 82.dp
    val todayWidgetHeight = dimension(144, 112, 108, 112).dp
    val notificationTrayHeight = dimension(240, 210, 180, 148).dp
    val settingsTitle = TextStyle(fontSize = 29.sp, fontWeight = FontWeight.SemiBold, color = ink)
    val folderMaxHeight = 300.dp
    val dockHoldMillis = 500L
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
    val sheetMaxWidth = 480.dp
    val sheetRadius = dimension(30, 26, 24).dp
    val dockCellWidth = dimension(68, 62, 58, 48).dp
    val emptySurface = if (dark) surface else ink
    val emptyInk = if (dark) ink else background
    val brandInk = Color.White
    val brandSurface = if (dark) accentContainer else accent
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
    val stackSpread = 116.dp
    val stackLift = 10.dp
    val stackSpreadFraction = 0.46f
    val stackExpandedWidth = 0.82f
    val recentWidthFraction = 0.66f
    val recentHeightFraction = 0.38f
    val recentHeightUse = 0.85f
    val recentDefaultAspect = 0.66f
    val recentTallAspect = 0.45f
    val recentSquareAspect = 0.9f
    val recentRotation = 3f
    val recentOffset = 0.16f
    val recentDismissFraction = 0.23f
    val recentExitMillis = 350
    val elevation = listOf(1.dp, dimension(3, 2, 2).dp, dimension(8, 6, 5).dp)

    val state = TextStyle(fontSize = dimension(18, 17, 16).sp, fontWeight = FontWeight.SemiBold, color = ink)
    val secondary = TextStyle(fontSize = 12.sp, color = quiet)
    val label = TextStyle(
        fontSize = dimension(11, 11, 10).sp,
        letterSpacing = dimension(6, 5, 0).let { (it / 10f).sp },
        color = quiet
    )
    val value = TextStyle(fontSize = dimension(36, 32, 28).sp, fontFeatureSettings = "tnum", color = ink)
}

internal val LocalTokens = staticCompositionLocalOf<Tokens> { error("Missing Enyo tokens") }
