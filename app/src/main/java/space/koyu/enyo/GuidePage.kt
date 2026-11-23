package space.koyu.enyo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** What a chapter's picture shows. */
private enum class Pictogram { Launcher, Sections, Today, Widgets, Apps, Cards, Stacks, Search, Inbox, Chats, Banners, Quick, LocalAi, Data }

private data class Chapter(val title: Int, val body: Int, val picture: Pictogram)

/** The chapters, each building on the ones before it. */
private val chapters = listOf(
    Chapter(R.string.guide_launcher_title, R.string.guide_launcher_body, Pictogram.Launcher),
    Chapter(R.string.guide_sections_title, R.string.guide_sections_body, Pictogram.Sections),
    Chapter(R.string.guide_today_title, R.string.guide_today_body, Pictogram.Today),
    Chapter(R.string.guide_widgets_title, R.string.guide_widgets_body, Pictogram.Widgets),
    Chapter(R.string.guide_apps_title, R.string.guide_apps_body, Pictogram.Apps),
    Chapter(R.string.guide_cards_title, R.string.guide_cards_body, Pictogram.Cards),
    Chapter(R.string.guide_stacks_title, R.string.guide_stacks_body, Pictogram.Stacks),
    Chapter(R.string.guide_search_title, R.string.guide_search_body, Pictogram.Search),
    Chapter(R.string.guide_inbox_title, R.string.guide_inbox_body, Pictogram.Inbox),
    Chapter(R.string.guide_chats_title, R.string.guide_chats_body, Pictogram.Chats),
    Chapter(R.string.guide_banners_title, R.string.guide_banners_body, Pictogram.Banners),
    Chapter(R.string.guide_quick_title, R.string.guide_quick_body, Pictogram.Quick),
    Chapter(R.string.guide_ai_title, R.string.guide_ai_body, Pictogram.LocalAi),
    Chapter(R.string.guide_data_title, R.string.guide_data_body, Pictogram.Data)
)

/** The parts of the guide: a heading and the chapters it groups, by index. */
private val parts = listOf(
    R.string.guide_part_start to 0..1,
    R.string.guide_part_home to 2..6,
    R.string.guide_part_talk to 7..10,
    R.string.guide_part_more to 11..13
)

/** Chapters the person has read, so the list can show their progress. */
private fun readChapters(context: android.content.Context): Set<Int> =
    context.getSharedPreferences("guide", android.content.Context.MODE_PRIVATE).getStringSet("read", emptySet()).orEmpty()
        .mapNotNull { it.toIntOrNull() }.toSet()

private fun markRead(context: android.content.Context, index: Int) {
    val prefs = context.getSharedPreferences("guide", android.content.Context.MODE_PRIVATE)
    prefs.edit().putStringSet("read", prefs.getStringSet("read", emptySet()).orEmpty() + index.toString()).apply()
}

/** Settings → Guide: how to use Enyo, chapter by chapter, from what a launcher is to its details. */
@Composable
internal fun GuidePage(close: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tokens = LocalTokens.current
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    var read by remember { mutableStateOf(readChapters(context)) }
    val current = open
    if (current == null) FullPage(stringResource(R.string.guide), close) {
        item(key = "intro") {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(tokens.radius)).background(tokens.widgetSurface).padding(tokens.padding),
                verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                GuidePicture(Pictogram.Launcher)
                Text(stringResource(R.string.guide_intro), style = tokens.body)
                // How far the person got, and where to carry on.
                val next = chapters.indices.firstOrNull { it !in read }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(tokens.widgetTrack)) {
                        Box(Modifier.fillMaxWidth(read.size.toFloat() / chapters.size).fillMaxHeight().clip(CircleShape).background(tokens.accent))
                    }
                    Text(stringResource(R.string.guide_progress, read.size, chapters.size), style = tokens.caption)
                }
                PillButton(stringResource(if (read.isEmpty()) R.string.guide_start else if (next == null) R.string.guide_again else R.string.guide_continue), filled = true) {
                    open = next ?: 0
                }
            }
        }
        parts.forEach { (heading, range) ->
            item(key = "part$heading") { SectionLabel(stringResource(heading)) }
            item(key = "chapters$heading") {
                ListCard {
                    range.forEach { index ->
                        val chapter = chapters[index]
                        ListRow(stringResource(chapter.title), Modifier.appPress({ open = index }),
                            summary = stringResource(R.string.guide_chapter_number, index + 1),
                            leading = {
                                Box(Modifier.size(tokens.listAvatar).clip(RoundedCornerShape(tokens.radius / 3))) {
                                    GuidePicture(chapter.picture, Modifier.fillMaxSize(), thumbnail = true)
                                }
                            },
                            trailing = {
                                if (index in read) EnyoGlyph(Glyph.Check, color = tokens.accent, description = stringResource(R.string.guide_read))
                                else EnyoGlyph(Glyph.Forward, color = tokens.quiet)
                            })
                    }
                }
            }
        }
    } else GuideChapters(current, { index -> markRead(context, index); read = readChapters(context) }) { open = null }
}

/** The chapters as pages to swipe through, with the guide's progress on top and the way on at the bottom. */
@Composable
private fun GuideChapters(start: Int, seen: (Int) -> Unit, back: () -> Unit) {
    val tokens = LocalTokens.current
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start) { chapters.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { seen(pager.currentPage) }
    androidx.compose.ui.window.Dialog(onDismissRequest = back,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(tokens.settingsBackground).safeDrawingPadding()) {
            Box(Modifier.padding(horizontal = tokens.margin, vertical = tokens.gap)) {
                SettingsHeading(stringResource(R.string.guide_chapter, pager.currentPage + 1, chapters.size), back)
            }
            // One segment per chapter, filled up to the current one.
            Row(Modifier.fillMaxWidth().padding(horizontal = tokens.margin), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                chapters.indices.forEach { index ->
                    Box(Modifier.weight(1f).height(4.dp).clip(CircleShape)
                        .background(if (index <= pager.currentPage) tokens.accent else tokens.widgetTrack))
                }
            }
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f), pageSpacing = tokens.margin) { page ->
                val chapter = chapters[page]
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(tokens.margin), verticalArrangement = Arrangement.spacedBy(tokens.padding)) {
                    item {
                        Box {
                            GuidePicture(chapter.picture)
                            Box(Modifier.align(Alignment.TopStart).padding(tokens.gap).size(tokens.listAvatar).clip(CircleShape).background(tokens.accent),
                                contentAlignment = Alignment.Center) {
                                Text("${page + 1}", style = tokens.rowTitle.copy(color = tokens.onAccent, fontWeight = FontWeight.Bold))
                            }
                        }
                    }
                    item {
                        Text(stringResource(chapter.title), Modifier.semantics { heading() },
                            style = tokens.state.copy(fontWeight = FontWeight.SemiBold, fontSize = tokens.state.fontSize * 1.2f))
                    }
                    item {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(tokens.radius)).background(tokens.widgetSurface).padding(tokens.padding),
                            verticalArrangement = Arrangement.spacedBy(tokens.padding)) {
                            stringResource(chapter.body).split("\n\n").forEach { Text(it.trim(), style = tokens.body) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(tokens.margin), horizontalArrangement = Arrangement.spacedBy(tokens.gap),
                verticalAlignment = Alignment.CenterVertically) {
                val page = pager.currentPage
                if (page > 0) PillButton(stringResource(R.string.guide_previous)) { scope.launch { pager.animateScrollToPage(page - 1) } }
                Spacer(Modifier.weight(1f))
                if (page < chapters.lastIndex) PillButton(stringResource(R.string.guide_next), filled = true) { scope.launch { pager.animateScrollToPage(page + 1) } }
                else PillButton(stringResource(R.string.guide_finish), filled = true, onClick = back)
            }
        }
    }
}

/** A chapter's picture: a phone with the part the chapter is about drawn in the accent color. */
@Composable
private fun GuidePicture(picture: Pictogram, modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(1.7f), thumbnail: Boolean = false) {
    val tokens = LocalTokens.current
    val label = stringResource(R.string.guide_picture)
    val ink = tokens.ink
    val accent = tokens.accent
    val soft = tokens.accent.copy(alpha = 0.3f)
    val surface = tokens.settingsBackground
    val block = tokens.widgetSurface
    Box(modifier.clip(RoundedCornerShape(if (thumbnail) 0.dp else tokens.radius)).background(tokens.accentContainer)
        .then(if (thumbnail) Modifier else Modifier.semantics { contentDescription = label })) {
        Canvas(Modifier.fillMaxSize().padding(if (thumbnail) 4.dp else tokens.padding)) {
            val phoneHeight = size.height
            val phone = Rect(Offset((size.width - phoneHeight * 0.52f) / 2, 0f), Size(phoneHeight * 0.52f, phoneHeight))
            val screen = Rect(phone.left + phone.width * 0.06f, phone.top + phone.height * 0.04f, phone.right - phone.width * 0.06f, phone.bottom - phone.height * 0.04f)
            val unit = screen.width / 10f
            fun box(r: Rect, color: Color, radius: Float = unit * 0.6f) = drawRoundRect(color, r.topLeft, r.size, CornerRadius(radius))
            fun line(from: Offset, to: Offset, color: Color = ink, width: Float = unit * 0.45f) = drawLine(color, from, to, width, StrokeCap.Round)
            fun arrow(from: Offset, to: Offset, color: Color = ink) {
                line(from, to, color)
                val angle = kotlin.math.atan2(to.y - from.y, to.x - from.x)
                val head = unit * 1.3f
                for (side in listOf(-0.5f, 0.5f)) line(to, Offset(to.x - head * kotlin.math.cos(angle + side), to.y - head * kotlin.math.sin(angle + side)), color)
            }
            // The phone itself.
            drawRoundRect(ink, phone.topLeft, phone.size, CornerRadius(unit * 1.8f), style = Stroke(unit * 0.5f))
            box(screen, surface, unit * 1.2f)
            fun row(top: Float, height: Float) = Rect(screen.left + unit, top, screen.right - unit, top + height)
            fun navBar(highlight: Int?) {
                val y = screen.bottom - unit * 1.6f
                for (i in 0 until 5) {
                    val x = screen.left + screen.width * (i + 0.5f) / 5
                    drawCircle(if (i == highlight) accent else soft, unit * (if (i == highlight) 0.75f else 0.55f), Offset(x, y))
                }
            }
            when (picture) {
                Pictogram.Launcher -> {
                    for (r in 0 until 4) for (c in 0 until 4) {
                        val x = screen.left + unit * 1.2f + c * unit * 2.1f
                        val y = screen.top + unit * 2f + r * unit * 2.4f
                        box(Rect(x, y, x + unit * 1.5f, y + unit * 1.5f), if (r == 0 && c == 0) accent else block, unit * 0.5f)
                    }
                    box(Rect(screen.center.x - unit * 2f, screen.bottom - unit * 1.2f, screen.center.x + unit * 2f, screen.bottom - unit * 0.6f), accent, unit)
                    arrow(Offset(phone.right + unit * 4f, phone.bottom - unit * 1f), Offset(phone.right + unit * 1.2f, phone.bottom - unit * 1f), accent)
                }
                Pictogram.Sections -> {
                    navBar(2)
                    box(row(screen.top + unit * 2f, unit * 9f), block)
                    arrow(Offset(phone.left - unit * 1.5f, phone.center.y), Offset(phone.left - unit * 5f, phone.center.y), accent)
                    arrow(Offset(phone.right + unit * 1.5f, phone.center.y), Offset(phone.right + unit * 5f, phone.center.y), accent)
                }
                Pictogram.Today -> {
                    box(row(screen.top + unit * 1.5f, unit * 3f), accent)
                    val half = (screen.width - unit * 3f) / 2
                    box(Rect(screen.left + unit, screen.top + unit * 5f, screen.left + unit + half, screen.top + unit * 8f), block)
                    box(Rect(screen.right - unit - half, screen.top + unit * 5f, screen.right - unit, screen.top + unit * 8f), block)
                    box(row(screen.top + unit * 9f, unit * 4f), block)
                    navBar(0)
                }
                Pictogram.Widgets -> {
                    box(row(screen.top + unit * 0.8f, unit * 1.6f), soft)
                    val widget = Rect(screen.left + unit * 1.5f, screen.top + unit * 5f, screen.right - unit * 1.5f, screen.top + unit * 10f)
                    drawRoundRect(accent, widget.topLeft, widget.size, CornerRadius(unit * 0.6f),
                        style = Stroke(unit * 0.35f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(unit, unit * 0.6f))))
                    drawCircle(accent, unit * 0.7f, widget.bottomRight)
                    arrow(Offset(widget.center.x, widget.top - unit * 0.6f), Offset(widget.center.x, screen.top + unit * 2.8f), accent)
                    navBar(0)
                }
                Pictogram.Apps -> {
                    for (r in 0 until 4) for (c in 0 until 4) {
                        val x = screen.left + unit * 1.2f + c * unit * 2.1f
                        val y = screen.top + unit * 1.5f + r * unit * 2.3f
                        box(Rect(x, y, x + unit * 1.5f, y + unit * 1.5f), block, unit * 0.5f)
                    }
                    for (c in 0 until 5) {
                        val x = screen.left + unit * 0.8f + c * unit * 1.75f
                        box(Rect(x, screen.bottom - unit * 4.4f, x + unit * 1.3f, screen.bottom - unit * 3.1f), accent, unit * 0.4f)
                    }
                    navBar(1)
                }
                Pictogram.Cards -> {
                    val w = unit * 4.2f
                    val top = screen.top + unit * 3f
                    box(Rect(screen.left - unit * 0.2f, top + unit, screen.left - unit * 0.2f + w * 0.6f, top + unit * 8f), soft)
                    box(Rect(screen.right - w * 0.6f + unit * 0.2f, top + unit, screen.right + unit * 0.2f, top + unit * 8f), soft)
                    box(Rect(screen.center.x - w / 2, top, screen.center.x + w / 2, top + unit * 10f), accent)
                    arrow(Offset(screen.center.x, top - unit * 0.4f), Offset(screen.center.x, screen.top + unit * 0.4f), ink)
                    navBar(2)
                }
                Pictogram.Stacks -> {
                    val w = unit * 3.6f
                    val top = screen.top + unit * 3f
                    for (i in 2 downTo 0) box(Rect(screen.left + unit * 1.2f + i * unit * 0.6f, top + i * unit * 0.6f, screen.left + unit * 1.2f + w + i * unit * 0.6f, top + unit * 8f + i * unit * 0.6f), if (i == 0) accent else soft)
                    box(Rect(screen.right - unit * 1.2f - w, top + unit * 2f, screen.right - unit * 1.2f, top + unit * 10f), block)
                    arrow(Offset(screen.right - unit * 1.2f - w / 2, top + unit * 1.4f), Offset(screen.left + unit * 3.2f, top - unit * 1.2f), accent)
                    navBar(2)
                }
                Pictogram.Search -> {
                    box(row(screen.top + unit * 1.2f, unit * 2f), accent, unit)
                    for (i in 0 until 4) box(row(screen.top + unit * (4.4f + i * 2.4f), unit * 1.6f), block)
                    arrow(Offset(phone.right + unit * 2.5f, phone.top + unit * 1f), Offset(phone.right + unit * 2.5f, phone.top + unit * 6f), accent)
                }
                Pictogram.Inbox -> {
                    for (i in 0 until 4) drawCircle(if (i == 0) accent else soft, unit * 0.9f, Offset(screen.left + unit * 1.8f + i * unit * 2.2f, screen.top + unit * 2.2f))
                    drawCircle(ink, unit * 0.35f, Offset(screen.left + unit * 2.5f, screen.top + unit * 1.5f))
                    for (i in 0 until 4) {
                        val y = screen.top + unit * (4.6f + i * 2.6f)
                        drawCircle(soft, unit * 0.8f, Offset(screen.left + unit * 1.8f, y + unit * 0.8f))
                        box(Rect(screen.left + unit * 3.2f, y + unit * 0.3f, screen.right - unit, y + unit * 1.3f), block, unit * 0.4f)
                    }
                    navBar(3)
                }
                Pictogram.Chats -> {
                    box(Rect(screen.left + unit, screen.top + unit * 2f, screen.left + unit * 6.5f, screen.top + unit * 4f), block)
                    box(Rect(screen.right - unit * 6.5f, screen.top + unit * 5f, screen.right - unit, screen.top + unit * 7f), accent)
                    box(Rect(screen.left + unit, screen.top + unit * 8f, screen.left + unit * 5.5f, screen.top + unit * 12f), soft)
                    // Two ticks: read.
                    for (shift in listOf(0f, unit * 0.7f)) {
                        val base = Offset(screen.right - unit * 2.2f + shift, screen.top + unit * 7.9f)
                        line(Offset(base.x - unit * 0.5f, base.y - unit * 0.4f), base, accent, unit * 0.3f)
                        line(base, Offset(base.x + unit * 0.8f, base.y - unit * 1f), accent, unit * 0.3f)
                    }
                    box(row(screen.bottom - unit * 3.2f, unit * 1.8f), block, unit)
                }
                Pictogram.Banners -> {
                    for (i in 0 until 3) box(row(screen.top + unit * (1.5f + i * 2.6f), unit * 1.8f), soft)
                    val banner = Rect(screen.left + unit * 0.6f, screen.bottom - unit * 5.6f, screen.right - unit * 0.6f, screen.bottom - unit * 3f)
                    box(banner, accent)
                    arrow(Offset(banner.center.x, banner.top - unit * 0.4f), Offset(banner.center.x, banner.top - unit * 3.6f), ink)
                    navBar(null)
                }
                Pictogram.Quick -> {
                    val half = (screen.width - unit * 3f) / 2
                    box(Rect(screen.left + unit, screen.top + unit * 1.5f, screen.left + unit + half, screen.top + unit * 4.5f), accent)
                    box(Rect(screen.right - unit - half, screen.top + unit * 1.5f, screen.right - unit, screen.top + unit * 4.5f), block)
                    for (i in 0 until 2) {
                        val y = screen.top + unit * (6.5f + i * 2.6f)
                        box(row(y, unit * 1.2f), block, unit)
                        box(Rect(screen.left + unit, y, screen.left + unit + (screen.width - unit * 2) * (0.7f - i * 0.25f), y + unit * 1.2f), accent, unit)
                    }
                    drawCircle(soft, unit * 1.4f, Offset(screen.center.x, screen.top + unit * 13f))
                    navBar(4)
                }
                Pictogram.LocalAi -> {
                    box(Rect(screen.right - unit * 6.5f, screen.top + unit * 2f, screen.right - unit, screen.top + unit * 4f), block)
                    box(Rect(screen.left + unit, screen.top + unit * 5f, screen.left + unit * 7f, screen.top + unit * 9f), accent)
                    box(Rect(screen.left + unit, screen.top + unit * 9.6f, screen.left + unit * 7f, screen.top + unit * 11.4f), soft)
                    // A spark: the assistant, and that it stays on the phone.
                    val spark = Offset(phone.right + unit * 3.4f, phone.top + unit * 4f)
                    val path = Path().apply {
                        moveTo(spark.x, spark.y - unit * 2f); lineTo(spark.x + unit * 0.5f, spark.y - unit * 0.5f)
                        lineTo(spark.x + unit * 2f, spark.y); lineTo(spark.x + unit * 0.5f, spark.y + unit * 0.5f)
                        lineTo(spark.x, spark.y + unit * 2f); lineTo(spark.x - unit * 0.5f, spark.y + unit * 0.5f)
                        lineTo(spark.x - unit * 2f, spark.y); lineTo(spark.x - unit * 0.5f, spark.y - unit * 0.5f); close()
                    }
                    drawPath(path, accent)
                }
                Pictogram.Data -> {
                    // A padlock over the archive, and an arrow out to a backup folder.
                    val body = Rect(screen.center.x - unit * 2.4f, screen.top + unit * 6f, screen.center.x + unit * 2.4f, screen.top + unit * 10f)
                    drawArc(ink, 180f, 180f, false, Offset(body.left + unit * 0.8f, body.top - unit * 2.6f), Size(body.width - unit * 1.6f, unit * 5.2f), style = Stroke(unit * 0.6f))
                    box(body, accent)
                    drawCircle(surface, unit * 0.6f, body.center)
                    val folder = Rect(phone.right + unit * 1.8f, phone.center.y - unit * 1.4f, phone.right + unit * 6.4f, phone.center.y + unit * 2f)
                    box(folder, soft, unit * 0.4f)
                    arrow(Offset(phone.right - unit * 0.2f, phone.center.y), Offset(folder.left - unit * 0.3f, phone.center.y), accent)
                }
            }
        }
    }
}
