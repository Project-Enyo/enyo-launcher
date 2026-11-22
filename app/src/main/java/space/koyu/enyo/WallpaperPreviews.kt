package space.koyu.enyo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

internal data class WallpaperPreview(val bitmap: Bitmap?, val colors: List<Color>)
internal data class WallpaperPreviews(val home: WallpaperPreview?, val lock: WallpaperPreview?)

private val previewChanges = MutableStateFlow(0)

private fun Context.activeWallpaperPreviews() = File(filesDir, "wallpaper_previews")

private fun WallpaperManager.currentWallpaperId(which: Int): Int =
    runCatching { getWallpaperId(which) }.getOrDefault(0)

internal fun Context.saveWallpaperPreview(manager: WallpaperManager, id: Int, image: Bitmap, crop: Rect) {
    runCatching {
        val directory = activeWallpaperPreviews()
        if (!directory.isDirectory && !directory.mkdirs()) return
        val destination = File(directory, "$id.jpg")
        val temp = File(directory, "$id.part")
        val ratio = (wallpaperPreviewEdge.toFloat() / maxOf(crop.width(), crop.height())).coerceAtMost(1f)
        val width = (crop.width() * ratio).roundToInt().coerceAtLeast(1)
        val height = (crop.height() * ratio).roundToInt().coerceAtLeast(1)
        val thumbnail = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(thumbnail).drawBitmap(image, crop, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            if (temp.outputStream().use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, it) }) {
                temp.renameTo(destination)
            }
        } finally {
            thumbnail.recycle()
            temp.delete()
        }
        val active = setOf(id, manager.currentWallpaperId(WallpaperManager.FLAG_SYSTEM), manager.currentWallpaperId(WallpaperManager.FLAG_LOCK))
        directory.listFiles()?.forEach { file ->
            val cachedId = file.nameWithoutExtension.toIntOrNull()
            if (cachedId != null && cachedId !in active) file.delete()
        }
    }
    previewChanges.value = id
}

private fun Context.wallpaperPreview(manager: WallpaperManager, which: Int): WallpaperPreview {
    val requested = manager.currentWallpaperId(which)
    val effective = if (which == WallpaperManager.FLAG_LOCK && requested < 0) WallpaperManager.FLAG_SYSTEM else which
    val id = if (effective == which) requested else manager.currentWallpaperId(effective)
    val file = File(activeWallpaperPreviews(), "$id.jpg")
    val bitmap = if (id > 0) BitmapFactory.decodeFile(file.path) else null
    val colors = runCatching { manager.getWallpaperColors(effective) }.getOrNull()?.let {
        listOfNotNull(it.primaryColor, it.secondaryColor, it.tertiaryColor).map { tone -> Color(tone.toArgb()) }
    }.orEmpty()
    return WallpaperPreview(bitmap, colors)
}

@Composable
internal fun rememberWallpaperPreviews(manager: WallpaperManager): WallpaperPreviews {
    val context = LocalContext.current
    val foreground = foregroundCycle()
    val applied by previewChanges.collectAsState()
    var changes by remember(manager) { mutableIntStateOf(0) }
    DisposableEffect(manager) {
        val listener = WallpaperManager.OnColorsChangedListener { _, _ -> changes++ }
        val listening = runCatching { manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper())) }.isSuccess
        onDispose { if (listening) runCatching { manager.removeOnColorsChangedListener(listener) } }
    }
    val previews by produceState(WallpaperPreviews(null, null), foreground, changes, applied) {
        value = withContext(Dispatchers.IO) {
            WallpaperPreviews(
                context.wallpaperPreview(manager, WallpaperManager.FLAG_SYSTEM),
                context.wallpaperPreview(manager, WallpaperManager.FLAG_LOCK)
            )
        }
    }
    return previews
}

@Composable
internal fun CurrentWallpaperTile(title: Int, preview: WallpaperPreview?) {
    val tokens = LocalTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(tokens.gap / 2)) {
        Box(
            Modifier.fillMaxWidth().height(tokens.wallpaperThumbnail)
                .clip(RoundedCornerShape(tokens.artworkRadius)).background(tokens.widgetSurface),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = preview?.bitmap
            if (bitmap != null) {
                Image(remember(bitmap) { bitmap.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                val colors = preview?.colors.orEmpty()
                if (colors.isNotEmpty()) {
                    val gradient = if (colors.size == 1) colors + colors else colors
                    Box(Modifier.matchParentSize().background(Brush.linearGradient(gradient)))
                }
                if (preview != null) Text(
                    stringResource(R.string.wallpaper_preview_unavailable),
                    Modifier.background(tokens.widgetSurface, RoundedCornerShape(tokens.iconRadius))
                        .padding(horizontal = tokens.gap, vertical = tokens.gap / 2),
                    style = tokens.secondary, textAlign = TextAlign.Center
                )
            }
        }
        Text(stringResource(title), style = tokens.state)
    }
}

internal fun Context.notifyWallpaperApplied(which: Int) {
    val notifications = getSystemService(NotificationManager::class.java)
    if (!notifications.areNotificationsEnabled()) return
    val label = when (which) {
        WallpaperManager.FLAG_SYSTEM -> R.string.wallpaper_home
        WallpaperManager.FLAG_LOCK -> R.string.wallpaper_lock
        else -> R.string.wallpaper_both
    }
    runCatching {
        notifications.createNotificationChannel(NotificationChannel(
            wallpaperChannel, getString(R.string.wallpaper_notification_channel), NotificationManager.IMPORTANCE_LOW
        ))
        val notice = Notification.Builder(this, wallpaperChannel)
            .setSmallIcon(R.drawable.tabler_sun)
            .setContentTitle(getString(R.string.wallpaper_applied))
            .setContentText(getString(label))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        notifications.notify(wallpaperChannel, 1, notice)
    }
}

private const val wallpaperPreviewEdge = 512
private const val wallpaperChannel = "wallpaper_changes"
