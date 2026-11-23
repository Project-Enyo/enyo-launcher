package space.koyu.enyo

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** An image a UnifiedInbox app shared, once it arrived. */
@Composable
internal fun rememberInboxImage(packageName: String, id: String?): ImageBitmap? =
    rememberArchive<ImageBitmap?>(null, packageName, id) { context ->
        id?.let { ArchiveData.inboxImage(context, packageName, it) }?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }

private val spoiler = Regex("""\|\|(.+?)\|\|""", RegexOption.DOT_MATCHES_ALL)

/** The text of a message with its `||spoilers||` covered until [revealed]. */
internal fun spoilerText(text: String, revealed: Boolean): String =
    if (revealed) spoiler.replace(text) { it.groupValues[1] } else spoiler.replace(text) { "▒".repeat(it.groupValues[1].length.coerceIn(3, 12)) }

internal fun hasSpoiler(text: String) = spoiler.containsMatchIn(text)

/** Opens a web link, a place or a file in the app that handles it. */
internal fun Context.openWeb(url: String) = MessageContent.webUrl(url)?.let { launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } ?: false

private fun Context.openPlace(place: InboxLocationInfo): Boolean {
    val label = place.name?.replace(Regex("[()]"), "")?.let { "($it)" }.orEmpty()
    val coordinates = String.format(Locale.ROOT, "%.6f,%.6f", place.lat, place.lng)
    return launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coordinates?q=${Uri.encode(coordinates + label)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) ||
        openWeb(String.format(Locale.ROOT, "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=16/%.6f/%.6f", place.lat, place.lng, place.lat, place.lng))
}

private fun Context.openFile(file: File, mime: String?): Boolean {
    val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.searchfiles", file) }.getOrNull() ?: return false
    return launchSafely(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "application/octet-stream")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun duration(millis: Long): String = DateUtils.formatElapsedTime(millis / 1000)

/**
 * The parts of a shared message beyond its text: what it quotes, view once, files, previews, music
 * and places. [open] shows a file in the media viewer.
 */
@Composable
internal fun InboxMessageParts(message: ArchivedMessage, quoted: ArchivedMessage?, revealed: Boolean, open: (InboxAttachmentInfo) -> Unit, text: @Composable () -> Unit) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val content = message.content
    quoted?.let { quote ->
        Row(
            Modifier.padding(bottom = tokens.gap / 2).clip(RoundedCornerShape(tokens.radius / 3)).background(tokens.widgetTrack)
                .padding(horizontal = tokens.gap, vertical = tokens.gap / 2),
            horizontalArrangement = Arrangement.spacedBy(tokens.gap / 2), verticalAlignment = Alignment.CenterVertically
        ) {
            EnyoGlyph(Glyph.Reply, Modifier.size(tokens.threadAppIcon), color = tokens.quiet)
            Column {
                Text(if (quote.outgoing) stringResource(R.string.inbox_you) else quote.sender ?: "", style = tokens.pillLabel.copy(color = tokens.accent), maxLines = 1)
                Text(spoilerText(quote.text, false).replace('\n', ' '), style = tokens.rowSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (content?.once == true) {
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.gap), verticalAlignment = Alignment.CenterVertically) {
            EnyoGlyph(Glyph.Hidden, color = tokens.quiet)
            Text(stringResource(R.string.inbox_view_once), style = tokens.rowTitle.copy(fontWeight = FontWeight.Normal))
        }
        return
    }
    val visual = content?.attachments.orEmpty().filter { it.visual }
    if (visual.isNotEmpty()) Column(Modifier.padding(bottom = tokens.gap / 2), verticalArrangement = Arrangement.spacedBy(tokens.gap / 2)) {
        visual.forEach { attachment -> VisualAttachment(message.packageName, attachment) { open(attachment) } }
    }
    content?.attachments.orEmpty().filter { it.playable }.forEach { VoiceAttachment(message, it) }
    content?.attachments.orEmpty().filter { !it.visual && !it.playable }.forEach { attachment -> FileAttachment(message, attachment) }
    text()
    content?.previews.orEmpty().forEach { preview -> PreviewCard(message.packageName, preview) }
    content?.music?.let { music ->
        Row(
            Modifier.padding(top = tokens.gap / 2).clip(RoundedCornerShape(tokens.radius / 3)).background(tokens.widgetTrack)
                .appPress({ music.url?.let { context.openWeb(it) } }).padding(tokens.gap),
            horizontalArrangement = Arrangement.spacedBy(tokens.gap), verticalAlignment = Alignment.CenterVertically
        ) {
            val art = rememberInboxImage(message.packageName, music.image)
            Box(Modifier.size(tokens.listAvatar).clip(RoundedCornerShape(tokens.radius / 4)).background(tokens.accentContainer), contentAlignment = Alignment.Center) {
                if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else EnyoGlyph(Glyph.Music, color = tokens.onAccentContainer)
            }
            Column {
                Text(music.title, style = tokens.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(music.artist, style = tokens.rowSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    content?.location?.let { place ->
        val now = System.currentTimeMillis()
        val live = place.live && (place.until == null || place.until > now)
        Row(
            Modifier.padding(top = tokens.gap / 2).clip(RoundedCornerShape(tokens.radius / 3)).background(tokens.widgetTrack)
                .appPress({ context.openPlace(place) }).padding(tokens.gap),
            horizontalArrangement = Arrangement.spacedBy(tokens.gap), verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(tokens.listAvatar).clip(CircleShape).background(if (live) tokens.accent else tokens.accentContainer), contentAlignment = Alignment.Center) {
                EnyoGlyph(Glyph.Location, color = if (live) tokens.onAccent else tokens.onAccentContainer)
            }
            Column {
                Text(place.name ?: stringResource(if (place.live) R.string.inbox_live_location else R.string.inbox_location),
                    style = tokens.rowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = when {
                    live -> place.until?.let { stringResource(R.string.inbox_live_until, DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_TIME)) }
                        ?: stringResource(R.string.inbox_live_now)
                    place.live -> stringResource(R.string.inbox_live_ended)
                    else -> String.format(Locale.getDefault(), "%.5f, %.5f", place.lat, place.lng)
                }
                Text(detail, style = tokens.rowSummary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun VisualAttachment(packageName: String, attachment: InboxAttachmentInfo, open: () -> Unit) {
    val tokens = LocalTokens.current
    val thumbnail = rememberInboxImage(packageName, attachment.thumbnail)
    var hidden by remember(attachment.id) { mutableStateOf(attachment.spoiler) }
    val ratio = if (attachment.width != null && attachment.height != null) (attachment.width.toFloat() / attachment.height).coerceIn(0.6f, 1.8f)
        else thumbnail?.let { (it.width.toFloat() / it.height).coerceIn(0.6f, 1.8f) } ?: 1.33f
    val sticker = attachment.kind == "sticker"
    val label = attachment.name ?: stringResource(kindLabel(attachment.kind))
    Box(
        Modifier.then(if (sticker) Modifier.size(tokens.iconSize * 2.5f) else Modifier.fillMaxWidth().aspectRatio(ratio))
            .clip(RoundedCornerShape(tokens.radius / 3)).background(if (sticker) Color.Transparent else tokens.widgetTrack)
            .appPress({ if (hidden) hidden = false else open() }).semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        if (thumbnail != null) Image(thumbnail, null, Modifier.fillMaxSize().then(if (hidden) Modifier.blur(tokens.radius) else Modifier),
            contentScale = if (sticker) ContentScale.Fit else ContentScale.Crop)
        else EnyoGlyph(if (attachment.kind == "video") Glyph.Video else Glyph.Photo, color = tokens.quiet)
        if (hidden) Text(stringResource(R.string.inbox_spoiler), Modifier.clip(CircleShape).background(tokens.settingsBackground)
            .padding(horizontal = tokens.gap, vertical = tokens.gap / 2), style = tokens.pillLabel)
        else if (attachment.kind == "video" || attachment.kind == "gif") Row(
            Modifier.align(Alignment.BottomStart).padding(tokens.gap / 2).clip(CircleShape).background(tokens.settingsBackground.copy(alpha = 0.8f))
                .padding(horizontal = tokens.gap / 2, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            EnyoGlyph(if (attachment.kind == "gif") Glyph.Photo else Glyph.Play, Modifier.size(tokens.threadAppIcon), color = tokens.ink)
            Text(if (attachment.kind == "gif") "GIF" else attachment.duration?.let(::duration).orEmpty(), style = tokens.pillLabel)
        }
    }
}

private fun kindLabel(kind: String) = when (kind) {
    "image" -> R.string.inbox_photo
    "video" -> R.string.inbox_video
    "gif" -> R.string.inbox_gif
    "sticker" -> R.string.inbox_sticker
    "voice" -> R.string.inbox_voice
    "audio" -> R.string.inbox_audio
    else -> R.string.inbox_file
}

@Composable
private fun FileAttachment(message: ArchivedMessage, attachment: InboxAttachmentInfo) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(attachment.id) { mutableStateOf<Int?>(null) }
    Row(
        Modifier.padding(bottom = tokens.gap / 2).clip(RoundedCornerShape(tokens.radius / 3)).background(tokens.widgetTrack)
            .appPress({
                if (state == R.string.inbox_loading) return@appPress
                state = R.string.inbox_loading
                scope.launch {
                    val file = fetch(context, message, attachment)
                    state = if (file != null && context.openFile(file, attachment.mime)) null else R.string.inbox_file_unavailable
                }
            }).padding(tokens.gap),
        horizontalArrangement = Arrangement.spacedBy(tokens.gap), verticalAlignment = Alignment.CenterVertically
    ) {
        EnyoGlyph(Glyph.File, color = tokens.accent)
        Column(Modifier.weight(1f, fill = false)) {
            Text(attachment.name ?: stringResource(R.string.inbox_file), style = tokens.rowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state?.let { stringResource(it) } ?: attachment.size?.let { Formatter.formatShortFileSize(context, it) }.orEmpty(), style = tokens.rowSummary, maxLines = 1)
        }
    }
}

private suspend fun fetch(context: Context, message: ArchivedMessage, attachment: InboxAttachmentInfo): File? {
    val conversation = message.inboxConversation ?: return null
    val id = message.apiId ?: return null
    return InboxHost.file(context, message.packageName, conversation, id, attachment)
}

/** One voice message or audio file plays at a time, across the thread. */
private object InboxPlayback {
    val playing = MutableStateFlow<String?>(null)
    private var player: MediaPlayer? = null

    fun start(key: String, file: File, done: () -> Unit) {
        stop()
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(file.path)
                setOnCompletionListener { stop(); done() }
                prepare()
                start()
            }
        }.getOrNull()
        playing.value = if (player != null) key else null
    }

    fun position() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    fun length() = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    fun stop() {
        runCatching { player?.release() }
        player = null
        playing.value = null
    }
}

@Composable
private fun VoiceAttachment(message: ArchivedMessage, attachment: InboxAttachmentInfo) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = "${message.id}/${attachment.id}"
    val playing by InboxPlayback.playing.collectAsState()
    val active = playing == key
    var loading by remember(key) { mutableStateOf(false) }
    var failed by remember(key) { mutableStateOf(false) }
    var progress by remember(key) { mutableFloatStateOf(0f) }
    var elapsed by remember(key) { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        while (active) {
            val length = InboxPlayback.length().coerceAtLeast(1)
            elapsed = InboxPlayback.position().toLong()
            progress = elapsed.toFloat() / length
            delay(100)
        }
    }
    DisposableEffect(key) { onDispose { if (InboxPlayback.playing.value == key) InboxPlayback.stop() } }
    val label = stringResource(if (active) R.string.inbox_pause else R.string.inbox_play)
    Row(
        Modifier.padding(bottom = tokens.gap / 2).widthIn(min = 200.dp),
        horizontalArrangement = Arrangement.spacedBy(tokens.gap), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(tokens.listAvatar).clip(CircleShape).background(tokens.accent).appPress({
                if (active) { InboxPlayback.stop(); return@appPress }
                if (loading) return@appPress
                loading = true
                failed = false
                scope.launch {
                    val file = fetch(context, message, attachment)
                    loading = false
                    if (file == null) failed = true
                    else InboxPlayback.start(key, file) { progress = 0f; elapsed = 0L }
                }
            }).semantics { contentDescription = label },
            contentAlignment = Alignment.Center
        ) { EnyoGlyph(if (active) Glyph.Pause else if (attachment.kind == "voice") Glyph.Microphone else Glyph.Play, color = tokens.onAccent) }
        Column(Modifier.weight(1f)) {
            val levels = attachment.waveform.ifEmpty { List(32) { 0.35f } }
            val played = tokens.accent
            val rest = tokens.quiet.copy(alpha = 0.45f)
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val count = levels.size
                val slot = size.width / count
                val bar = (slot * 0.6f).coerceAtLeast(1f)
                levels.forEachIndexed { index, level ->
                    val height = (size.height * level.coerceIn(0.08f, 1f))
                    drawRoundRect(if (index.toFloat() / count <= progress && (active || progress > 0f)) played else rest,
                        topLeft = Offset(index * slot, (size.height - height) / 2), size = Size(bar, height), cornerRadius = CornerRadius(bar / 2))
                }
            }
            Text(
                when {
                    failed -> stringResource(R.string.inbox_file_unavailable)
                    loading -> stringResource(R.string.inbox_loading)
                    active -> duration(elapsed)
                    else -> attachment.duration?.let(::duration) ?: stringResource(kindLabel(attachment.kind))
                },
                style = tokens.rowSummary, maxLines = 1
            )
        }
    }
}

@Composable
private fun PreviewCard(packageName: String, preview: InboxPreviewInfo) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val image = rememberInboxImage(packageName, preview.image)
    Column(
        Modifier.padding(top = tokens.gap / 2).fillMaxWidth().clip(RoundedCornerShape(tokens.radius / 3)).background(tokens.widgetTrack)
            .appPress({ context.openWeb(preview.url) })
    ) {
        if (image != null) Image(image, null, Modifier.fillMaxWidth().aspectRatio(1.91f), contentScale = ContentScale.Crop)
        Column(Modifier.padding(tokens.gap), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(preview.site ?: Uri.parse(preview.url).host.orEmpty(), style = tokens.pillLabel.copy(color = tokens.accent), maxLines = 1)
            preview.title?.let { Text(it, style = tokens.rowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            preview.description?.let { Text(it, style = tokens.rowSummary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Reactions under a message; tapping one joins it, or takes the person's own back. */
@Composable
internal fun ReactionRow(reactions: List<MessageReaction>, outgoing: Boolean, react: ((String, Boolean) -> Unit)?) {
    if (reactions.isEmpty()) return
    val tokens = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(tokens.gap / 2, if (outgoing) Alignment.End else Alignment.Start)
    ) {
        reactions.groupBy { it.emoji }.forEach { (emoji, group) ->
            val mine = group.any { it.sender.isEmpty() }
            Row(
                Modifier.clip(CircleShape).background(if (mine) tokens.accentContainer else tokens.widgetSurface)
                    .then(if (react != null) Modifier.appPress({ react(emoji, mine) }) else Modifier)
                    .padding(horizontal = tokens.gap, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Text(emoji, style = tokens.rowSummary.copy(color = tokens.ink))
                if (group.size > 1) Text("${group.size}", style = tokens.pillLabel)
            }
        }
    }
}

/** A full screen view of a shared image, GIF, sticker or video, fetched from the app that owns it. */
@Composable
internal fun InboxMediaViewer(message: ArchivedMessage, attachment: InboxAttachmentInfo, close: () -> Unit) {
    val tokens = LocalTokens.current
    val context = LocalContext.current
    val file by produceState<File?>(null, message.id, attachment.id) { value = fetch(context, message, attachment) }
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(message.id, attachment.id) { delay(125_000L); waited = true }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding(), contentAlignment = Alignment.Center) {
            val current = file
            when {
                current == null -> Text(stringResource(if (waited) R.string.inbox_file_unavailable else R.string.inbox_loading), color = Color.White, style = tokens.rowTitle)
                attachment.kind == "video" -> AndroidView({ viewContext ->
                    VideoView(viewContext).apply {
                        setMediaController(MediaController(viewContext).also { it.setAnchorView(this) })
                        setVideoURI(Uri.fromFile(current))
                        setOnPreparedListener { start() }
                    }
                }, Modifier.fillMaxWidth(), onRelease = { it.stopPlayback() })
                attachment.kind == "gif" || attachment.mime == "image/gif" || attachment.mime == "image/webp" -> AndroidView({ viewContext ->
                    ImageView(viewContext).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(current)) }.getOrNull()?.let { drawable ->
                            setImageDrawable(drawable)
                            (drawable as? AnimatedImageDrawable)?.start()
                        }
                    }
                }, Modifier.fillMaxSize())
                else -> {
                    val image by produceState<ImageBitmap?>(null, current) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                ImageDecoder.decodeBitmap(ImageDecoder.createSource(current)) { decoder, info, _ ->
                                    val longest = maxOf(info.size.width, info.size.height)
                                    if (longest > 2560) decoder.setTargetSampleSize((longest + 2559) / 2560)
                                }.asImageBitmap()
                            }.getOrNull()
                        }
                    }
                    image?.let { Image(it, attachment.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
            }
            Box(Modifier.align(Alignment.TopStart).padding(tokens.gap).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                .appPress(close).padding(tokens.gap).semantics { contentDescription = context.getString(R.string.back) }) {
                EnyoGlyph(Glyph.Close, color = Color.White)
            }
            if (current != null) Box(Modifier.align(Alignment.TopEnd).padding(tokens.gap).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                .appPress({ context.openFile(current, attachment.mime) }).padding(tokens.gap)
                .semantics { contentDescription = context.getString(R.string.inbox_open_with) }) {
                EnyoGlyph(Glyph.External, color = Color.White)
            }
        }
    }
}
