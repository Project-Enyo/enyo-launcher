package space.koyu.enyo

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal class Tokens(private val dark: Boolean) {
    private fun tone(light: Long, night: Long) = Color(if (dark) night else light)

    val background = tone(0xFFF6F3E9, 0xFF161711)
    val surface = tone(0xFFEEE9DB, 0xFF28291F)
    val quiet = tone(0xFF5B5749, 0xFFBEB8A5)
    val ink = tone(0xFF29271D, 0xFFF7F1E0)
    val accent = tone(0xFF3F6338, 0xFFAACD97)
    val accentContainer = tone(0xFFDCE8CA, 0xFF34482C)

    val margin = 12.dp
    val gap = 8.dp
    val padding = 12.dp
    val radius = 20.dp
    val cardHeight = 84.dp
    val touch = 48.dp
    val bottomClearance = 24.dp
    val chevronSize = 12.dp
    val chevronStroke = 2.dp
    val stroke = 3.dp
    val elevation = listOf(1.dp, 2.dp, 5.dp)

    val state = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink)
    val secondary = TextStyle(fontSize = 12.sp, color = quiet)
    val value = TextStyle(fontSize = 28.sp, fontFeatureSettings = "tnum", color = ink)
}

internal val LocalTokens = staticCompositionLocalOf<Tokens> { error("Missing Enyo tokens") }
