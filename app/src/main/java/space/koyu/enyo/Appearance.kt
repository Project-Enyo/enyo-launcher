package space.koyu.enyo

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

internal val Context.appearancePreferences by preferencesDataStore("appearance")
internal val originalIcons = booleanPreferencesKey("original_icons")
private val wallpaperChoice = stringPreferencesKey("wallpaper")

private enum class Backdrop(val label: Int, val image: Int? = null) {
    System(R.string.wallpaper_system),
    Meadow(R.string.wallpaper_meadow, R.drawable.wallpaper_meadow),
    Stream(R.string.wallpaper_stream, R.drawable.wallpaper_stream),
    Dunes(R.string.wallpaper_dunes, R.drawable.wallpaper_dunes),
    TidalFlats(R.string.wallpaper_tidal_flats, R.drawable.wallpaper_tidal_flats),
    Harbor(R.string.wallpaper_harbor, R.drawable.wallpaper_harbor),
    Castle(R.string.wallpaper_castle, R.drawable.wallpaper_castle),
    Bastei(R.string.wallpaper_bastei, R.drawable.wallpaper_bastei),
    Moselle(R.string.wallpaper_moselle, R.drawable.wallpaper_moselle),
    Forest(R.string.wallpaper_forest, R.drawable.wallpaper_forest),
    Custom(R.string.wallpaper_custom)
}

private fun Context.customWallpaper() = File(filesDir, "wallpaper.jpg")

private fun Context.decode(choice: Backdrop): Bitmap? = runCatching {
    when (choice) {
        Backdrop.System -> null
        Backdrop.Custom -> BitmapFactory.decodeFile(customWallpaper().path)
        else -> choice.image?.let { BitmapFactory.decodeResource(resources, it) }
    }
}.getOrNull()

@Composable
private fun currentBackdrop(): Backdrop {
    val context = LocalContext.current
    val store = context.appearancePreferences
    val prefs by remember(store) { store.data }.collectAsState(null)
    return Backdrop.entries.find { it.name == prefs?.get(wallpaperChoice) } ?: Backdrop.System
}

@Composable
internal fun WallpaperBackdrop(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val tint = LocalTokens.current.wallpaperTint
    val choice = currentBackdrop()
    val bitmap by produceState<Bitmap?>(null, choice) {
        value = withContext(Dispatchers.IO) { context.decode(choice) }
    }
    Box(Modifier.fillMaxSize()) {
        bitmap?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.matchParentSize().background(tint))
        content()
    }
}

@Composable
internal fun AppearanceScreen(back: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val store = context.appearancePreferences
    val scope = rememberCoroutineScope()
    val manager = remember(context) { WallpaperManager.getInstance(context) }
    val current = currentBackdrop()
    var preview by remember { mutableStateOf(current.takeUnless { it == Backdrop.System } ?: Backdrop.Meadow) }
    var notice by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var applying by remember { mutableStateOf(false) }
    var targets by remember { mutableStateOf(false) }
    val canApply = remember(manager) { manager.isWallpaperSupported && manager.isSetWallpaperAllowed }
    val bitmap by key(preview, revision) {
        produceState<Bitmap?>(null) {
            value = withContext(Dispatchers.IO) { context.decode(preview) }
        }
    }
    val gallery = remember { Backdrop.entries.filter { it.image != null }.chunked(2) }
    val galleryScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (withContext(Dispatchers.IO) { context.saveWallpaper(uri) }) {
                preview = Backdrop.Custom
                revision++
                notice = 0
            } else notice = R.string.wallpaper_unavailable
        }
    }
    fun applyWallpaper(which: Int) {
        val image = bitmap ?: return
        targets = false
        applying = true
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    manager.setBitmap(image, adjustCrop(image, context), false, which) != 0
                }.getOrDefault(false)
            }
            if (success && which and WallpaperManager.FLAG_SYSTEM != 0) {
                store.edit { it[wallpaperChoice] = Backdrop.System.name }
            }
            notice = if (success) R.string.wallpaper_applied else R.string.wallpaper_unavailable
            applying = false
        }
    }
    BackHandler(onBack = back)
    LazyColumn(
        Modifier.fillMaxSize().background(tokens.settingsBackground).safeDrawingPadding(),
        state = galleryScroll,
        contentPadding = PaddingValues(tokens.margin),
        verticalArrangement = Arrangement.spacedBy(tokens.gap)
    ) {
        item {
            SettingsHeading(stringResource(R.string.wallpaper), back)
            Text(stringResource(R.string.wallpaper_scope), style = tokens.secondary)
        }
        item {
            val label = stringResource(preview.label)
            Box(
                Modifier.fillMaxWidth().height(tokens.heldTarget).clip(RoundedCornerShape(tokens.radius))
                    .background(tokens.widgetSurface)
            ) {
                bitmap?.let { Image(it.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Text(label, Modifier.padding(top = tokens.gap), style = tokens.state)
        }
        item {
            SettingsPanel(null) {
                Text(stringResource(R.string.wallpaper_current, stringResource(current.label)), style = tokens.secondary)
                TextButton(enabled = bitmap != null, onClick = {
                    scope.launch {
                        store.edit { it[wallpaperChoice] = preview.name }
                        notice = R.string.wallpaper_enyo_applied
                    }
                }) { Text(stringResource(R.string.wallpaper_use_in_enyo)) }
                if (canApply) {
                    TextButton(enabled = bitmap != null && !applying, onClick = { targets = true }) {
                        Text(stringResource(R.string.wallpaper_apply_android))
                    }
                } else Text(stringResource(R.string.wallpaper_android_unavailable), style = tokens.secondary)
                if (notice != 0) Text(stringResource(notice), style = tokens.secondary)
            }
        }
        item {
            SettingsPanel(null) {
                SettingsLink(stringResource(R.string.wallpaper_pick), glyph = Glyph.Folder) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                SettingsLink(stringResource(R.string.wallpaper_follow_system), glyph = Glyph.Today) {
                    scope.launch {
                        store.edit { it[wallpaperChoice] = Backdrop.System.name }
                        notice = 0
                    }
                }
            }
        }
        item { Text(stringResource(R.string.wallpaper_gallery), style = tokens.state) }
        items(gallery, key = { it.first().name }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.gap)) {
                row.forEach { choice ->
                    val picture by produceState<Bitmap?>(null, choice) {
                        value = withContext(Dispatchers.IO) {
                            choice.image?.let {
                                BitmapFactory.decodeResource(context.resources, it, BitmapFactory.Options().apply { inSampleSize = 4 })
                            }
                        }
                    }
                    Column(
                        Modifier.weight(1f).appPress({
                            preview = choice
                            notice = 0
                            scope.launch { galleryScroll.animateScrollToItem(0) }
                        }),
                        verticalArrangement = Arrangement.spacedBy(tokens.gap)
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(tokens.wallpaperThumbnail)
                                .clip(RoundedCornerShape(tokens.artworkRadius)).background(tokens.widgetSurface)
                        ) {
                            picture?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Text(stringResource(choice.label), style = if (preview == choice) tokens.state else tokens.secondary)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    if (targets) EnyoSheet(stringResource(R.string.wallpaper_apply_android), { targets = false }) {
        TextButton(onClick = { applyWallpaper(WallpaperManager.FLAG_SYSTEM) }) {
            Text(stringResource(R.string.wallpaper_home))
        }
        TextButton(onClick = { applyWallpaper(WallpaperManager.FLAG_LOCK) }) {
            Text(stringResource(R.string.wallpaper_lock))
        }
        TextButton(onClick = { applyWallpaper(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }) {
            Text(stringResource(R.string.wallpaper_both))
        }
    }
}

private fun Context.saveWallpaper(uri: Uri): Boolean = runCatching {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
        val size = info.size
        val scale = (displayBitmapEdge.toFloat() / maxOf(size.width, size.height)).coerceAtMost(1f)
        decoder.setTargetSize((size.width * scale).roundToInt().coerceAtLeast(1), (size.height * scale).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val destination = customWallpaper()
    val temp = File(destination.parentFile, "wallpaper.part")
    val written = temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    bitmap.recycle()
    written && temp.renameTo(destination)
}.getOrDefault(false)

private fun adjustCrop(image: Bitmap, context: Context): Rect {
    val window = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
    val aspect = window.width().toFloat() / window.height().coerceAtLeast(1)
    val width = minOf(image.width, (image.height * aspect).roundToInt()).coerceAtLeast(1)
    val height = minOf(image.height, (image.width / aspect).roundToInt()).coerceAtLeast(1)
    val left = (image.width - width) / 2
    val top = (image.height - height) / 2
    return Rect(left, top, left + width, top + height)
}

private const val displayBitmapEdge = 2560
