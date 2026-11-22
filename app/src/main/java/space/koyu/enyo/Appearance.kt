package space.koyu.enyo

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

internal val Context.appearancePreferences by preferencesDataStore("appearance")
internal val originalIcons = booleanPreferencesKey("original_icons")
private val wallpaperOrder = stringPreferencesKey("wallpaper_order")

private enum class Backdrop(
    val label: Int,
    val image: Int,
    val photographer: String? = null,
    val source: String? = null
) {
    Meadow(R.string.wallpaper_meadow, R.drawable.wallpaper_meadow),
    Stream(R.string.wallpaper_stream, R.drawable.wallpaper_stream),
    Ruegen(
        R.string.wallpaper_ruegen, R.drawable.wallpaper_ruegen, "W. Bulach",
        "https://commons.wikimedia.org/wiki/File:00_6974_Steilk%C3%BCste_vom_Kap_Arkona_(Ostseeinsel_R%C3%BCgen).jpg"
    ),
    TidalFlats(R.string.wallpaper_tidal_flats, R.drawable.wallpaper_tidal_flats),
    Kiel(
        R.string.wallpaper_kiel, R.drawable.wallpaper_kiel, "Diego Delso, delso.photo",
        "https://commons.wikimedia.org/wiki/File:Ayuntamiento,_Kiel,_Alemania,_2019-09-10,_DD_51-53_HDR.jpg"
    ),
    Castle(R.string.wallpaper_castle, R.drawable.wallpaper_castle),
    Bastei(R.string.wallpaper_bastei, R.drawable.wallpaper_bastei),
    Moselle(R.string.wallpaper_moselle, R.drawable.wallpaper_moselle),
    Forest(R.string.wallpaper_forest, R.drawable.wallpaper_forest)
}

private data class GalleryWallpaper(val id: String, val bundled: Backdrop? = null, val file: File? = null) {
    val label = bundled?.label ?: R.string.wallpaper_custom
}

private val bundledWallpapers = Backdrop.entries.map { GalleryWallpaper(it.name, bundled = it) }

private fun Context.wallpaperDirectory() = File(filesDir, "wallpapers")

private fun Context.savedWallpapers(): List<GalleryWallpaper> {
    val legacy = File(filesDir, "wallpaper.jpg")
    val files = wallpaperDirectory().listFiles().orEmpty()
        .filter { it.isFile && it.extension == "jpg" }
        .sortedBy { it.lastModified() }
    return (listOf(legacy).filter(File::isFile) + files).map { GalleryWallpaper(it.name, file = it) }
}

private fun Context.decode(wallpaper: GalleryWallpaper, thumbnail: Boolean = false): Bitmap? = runCatching {
    val options = if (thumbnail) BitmapFactory.Options().apply { inSampleSize = 4 } else null
    wallpaper.file?.let { BitmapFactory.decodeFile(it.path, options) }
        ?: wallpaper.bundled?.let { BitmapFactory.decodeResource(resources, it.image, options) }
}.getOrNull()

private fun orderedWallpapers(images: List<GalleryWallpaper>, order: String?): List<GalleryWallpaper> {
    val byId = images.associateBy { it.id }
    return (order.orEmpty().split(',').mapNotNull(byId::get) + images).distinctBy { it.id }
}

private data class WallpaperDrag(val id: String, val origin: Offset, val offset: Offset = Offset.Zero)

private fun LazyGridItemInfo.center() = Offset(offset.x + size.width / 2f, offset.y + size.height / 2f)

@Composable
internal fun WallpaperBackdrop(content: @Composable () -> Unit) {
    val tint = LocalTokens.current.wallpaperTint
    Box(Modifier.fillMaxSize()) {
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
    val haptics = LocalHapticFeedback.current
    val manager = remember(context) { WallpaperManager.getInstance(context) }
    val prefs by remember(store) { store.data }.collectAsState(null)
    var custom by remember { mutableStateOf(emptyList<GalleryWallpaper>()) }
    LaunchedEffect(context) {
        val saved = withContext(Dispatchers.IO) { context.savedWallpapers() }
        custom = (saved + custom).distinctBy { it.id }
    }
    val images = remember(custom) { bundledWallpapers + custom }
    val gallery = remember(images, prefs) { orderedWallpapers(images, prefs?.get(wallpaperOrder)) }
    val grid = rememberLazyGridState()
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<String?>(null) }
    var drag by remember { mutableStateOf<WallpaperDrag?>(null) }
    var notice by rememberSaveable { mutableIntStateOf(0) }
    var applying by remember { mutableStateOf(false) }
    val canApply = remember(manager) { manager.isWallpaperSupported && manager.isSetWallpaperAllowed }
    val addLabel = stringResource(R.string.wallpaper_add)
    val deleteLabel = stringResource(R.string.wallpaper_delete)
    val earlier = stringResource(R.string.wallpaper_move_earlier)
    val later = stringResource(R.string.wallpaper_move_later)

    fun select(wallpaper: GalleryWallpaper) {
        deletion = null
        notice = 0
        if (canApply) targetId = wallpaper.id else notice = R.string.wallpaper_android_unavailable
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val added = withContext(Dispatchers.IO) { context.importWallpaper(uri) }
            if (added == null) notice = R.string.wallpaper_unavailable
            else {
                custom = custom + added
                select(added)
            }
        }
    }

    fun move(from: String, to: String) {
        if (from == to) return
        scope.launch {
            store.edit { values ->
                val order = orderedWallpapers(images, values[wallpaperOrder]).map { it.id }.toMutableList()
                val target = order.indexOf(to)
                if (target >= 0 && order.remove(from)) {
                    order.add(target, from)
                    values[wallpaperOrder] = order.joinToString(",")
                }
            }
        }
    }

    fun delete(wallpaper: GalleryWallpaper) {
        val file = wallpaper.file ?: return
        deletion = null
        scope.launch {
            if (withContext(Dispatchers.IO) { file.delete() || !file.exists() }) {
                custom = custom.filterNot { it.id == wallpaper.id }
                store.edit { values ->
                    val order = values[wallpaperOrder].orEmpty().split(',').filter { it.isNotEmpty() && it != wallpaper.id }
                    values[wallpaperOrder] = order.joinToString(",")
                }
            } else notice = R.string.wallpaper_delete_failed
        }
    }

    fun apply(wallpaper: GalleryWallpaper, which: Int) {
        targetId = null
        notice = 0
        applying = true
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                val bitmap = context.decode(wallpaper) ?: return@withContext false
                try {
                    runCatching { manager.setBitmap(bitmap, adjustCrop(bitmap, context), false, which) != 0 }.getOrDefault(false)
                } finally {
                    bitmap.recycle()
                }
            }
            notice = if (success) R.string.wallpaper_applied else R.string.wallpaper_unavailable
            applying = false
        }
    }

    fun leave() {
        if (deletion != null) deletion = null else back()
    }

    BackHandler(onBack = ::leave)
    val clearDeletion = if (deletion == null) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures { deletion = null }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().background(tokens.settingsBackground).safeDrawingPadding().then(clearDeletion),
        state = grid,
        contentPadding = PaddingValues(tokens.margin),
        horizontalArrangement = Arrangement.spacedBy(tokens.gap),
        verticalArrangement = Arrangement.spacedBy(tokens.padding)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SettingsHeading(stringResource(R.string.wallpaper), ::leave)
            if (notice != 0) Text(stringResource(notice), style = tokens.secondary)
        }
        itemsIndexed(gallery, key = { _, item -> item.id }) { index, wallpaper ->
            val label = stringResource(wallpaper.label)
            val photo by produceState<Bitmap?>(null, wallpaper) {
                value = withContext(Dispatchers.IO) { context.decode(wallpaper, thumbnail = true) }
            }
            val dragging = drag?.id == wallpaper.id
            val currentMove by rememberUpdatedState<(String, String) -> Unit>(::move)
            val hold = Modifier.pointerInput(wallpaper.id, grid, haptics) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        deletion = wallpaper.id.takeIf { wallpaper.file != null }
                        val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == wallpaper.id }
                        drag = item?.let { WallpaperDrag(wallpaper.id, it.center()) }
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag?.let { drag = it.copy(offset = it.offset + amount) }
                    },
                    onDragEnd = {
                        drag?.takeIf { it.offset != Offset.Zero }?.let { moved ->
                            val center = moved.origin + moved.offset
                            val destination = grid.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key != moved.id && it.key is String && it.key != addWallpaperKey &&
                                    center.x >= it.offset.x && center.x <= it.offset.x + it.size.width &&
                                    center.y >= it.offset.y && center.y <= it.offset.y + it.size.height }
                            val key = destination?.key as? String
                            if (key != null) currentMove(moved.id, key)
                            deletion = null
                        }
                        drag = null
                    },
                    onDragCancel = { drag = null }
                )
            }
            Column(
                Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer {
                    val offset = drag?.takeIf { it.id == wallpaper.id }?.offset ?: Offset.Zero
                    translationX = offset.x
                    translationY = offset.y
                }.appPress({
                    if (deletion != null) deletion = null else if (!applying) select(wallpaper)
                }).then(hold).semantics {
                    contentDescription = label
                    customActions = buildList {
                        gallery.getOrNull(index - 1)?.let { anchor ->
                            add(CustomAccessibilityAction(earlier) { move(wallpaper.id, anchor.id); true })
                        }
                        gallery.getOrNull(index + 1)?.let { anchor ->
                            add(CustomAccessibilityAction(later) { move(wallpaper.id, anchor.id); true })
                        }
                        if (wallpaper.file != null) add(CustomAccessibilityAction(deleteLabel) {
                            deletion = wallpaper.id
                            true
                        })
                    }
                },
                verticalArrangement = Arrangement.spacedBy(tokens.gap / 2)
            ) {
                Box {
                    Box(
                        Modifier.fillMaxWidth().height(tokens.wallpaperThumbnail)
                            .clip(RoundedCornerShape(tokens.artworkRadius)).background(tokens.widgetSurface)
                    ) {
                        photo?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    if (deletion == wallpaper.id && (drag?.offset ?: Offset.Zero) == Offset.Zero) {
                        Box(
                            Modifier.align(Alignment.TopEnd).size(tokens.touch)
                                .appPress({ delete(wallpaper) }).semantics { contentDescription = deleteLabel },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier.size(tokens.wallpaperDeleteBubble).background(MaterialTheme.colorScheme.error, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                EnyoGlyph(Glyph.Close, color = MaterialTheme.colorScheme.onError)
                            }
                        }
                    }
                }
                Text(label, style = tokens.state, maxLines = 2, overflow = TextOverflow.Ellipsis)
                wallpaper.bundled?.photographer?.let {
                    Text(stringResource(R.string.wallpaper_photo_credit, it), style = tokens.secondary)
                }
            }
        }
        item(key = addWallpaperKey) {
            Box(
                Modifier.fillMaxWidth().height(tokens.wallpaperThumbnail).background(
                    tokens.widgetSurface, RoundedCornerShape(tokens.artworkRadius)
                ).appPress({
                    if (deletion != null) deletion = null
                    else if (!applying) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }).semantics { contentDescription = addLabel },
                contentAlignment = Alignment.Center
            ) { EnyoGlyph(Glyph.Plus, Modifier.size(tokens.appGlyphSize)) }
        }
    }
    gallery.firstOrNull { it.id == targetId }?.let { wallpaper ->
        EnyoSheet(stringResource(R.string.wallpaper_apply_android), { targetId = null }) {
            Text(stringResource(wallpaper.label), style = tokens.secondary)
            val source = wallpaper.bundled?.source
            val photographer = wallpaper.bundled?.photographer
            if (source != null && photographer != null) TextButton(
                onClick = { context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(source))) }
            ) { Text(stringResource(R.string.wallpaper_photo_credit, photographer), style = tokens.secondary) }
            TextButton(onClick = { apply(wallpaper, WallpaperManager.FLAG_SYSTEM) }) {
                Text(stringResource(R.string.wallpaper_home))
            }
            TextButton(onClick = { apply(wallpaper, WallpaperManager.FLAG_LOCK) }) {
                Text(stringResource(R.string.wallpaper_lock))
            }
            TextButton(onClick = { apply(wallpaper, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }) {
                Text(stringResource(R.string.wallpaper_both))
            }
        }
    }
}

private fun Context.importWallpaper(uri: Uri): GalleryWallpaper? {
    val directory = wallpaperDirectory()
    val id = "${UUID.randomUUID()}.jpg"
    val destination = File(directory, id)
    val temp = File(directory, "$id.part")
    return runCatching {
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            val size = info.size
            val scale = (displayBitmapEdge.toFloat() / maxOf(size.width, size.height)).coerceAtMost(1f)
            decoder.setTargetSize((size.width * scale).roundToInt().coerceAtLeast(1), (size.height * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val written = try {
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } finally {
            bitmap.recycle()
        }
        if (written && temp.renameTo(destination)) GalleryWallpaper(id, file = destination) else null
    }.getOrNull().also { temp.delete() }
}

private fun adjustCrop(image: Bitmap, context: Context): Rect {
    val window = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
    val aspect = window.width().toFloat() / window.height().coerceAtLeast(1)
    val width = minOf(image.width, (image.height * aspect).roundToInt()).coerceAtLeast(1)
    val height = minOf(image.height, (image.width / aspect).roundToInt()).coerceAtLeast(1)
    val left = (image.width - width) / 2
    val top = (image.height - height) / 2
    return Rect(left, top, left + width, top + height)
}

private const val addWallpaperKey = "add_wallpaper"
private const val displayBitmapEdge = 2560
