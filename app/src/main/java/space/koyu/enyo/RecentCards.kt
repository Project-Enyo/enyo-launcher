package space.koyu.enyo

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

internal data class RecentEntry(
    val component: ComponentName,
    val aspect: Float? = null,
    val version: Long = 0,
    val denied: Boolean = false
)

internal data class CaptureRequest(
    val component: ComponentName,
    val width: Int,
    val createdAt: Long
)

internal class RecentStore private constructor(context: Context) {
    private val directory = File(context.noBackupFilesDir, "cards")
    private val entriesFile = File(directory, "index.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    val entries = MutableStateFlow<List<RecentEntry>>(emptyList())
    @Volatile var capture: CaptureRequest? = null
    @Volatile private var targetWidth = 0

    init {
        scope.launch {
            synchronized(lock) {
                if (entries.value.isNotEmpty()) return@synchronized
                entries.value = readEntries()
            }
        }
    }

    fun setTargetWidth(pixels: Int) {
        if (pixels > 0) targetWidth = ((pixels + thumbnailBucket / 2) / thumbnailBucket).coerceAtLeast(1) * thumbnailBucket
    }

    fun prepare(component: ComponentName) {
        val current = entries.value.find { it.component == component }
        capture = if (targetWidth > 0 && current?.denied != true) {
            CaptureRequest(component, targetWidth, SystemClock.elapsedRealtime())
        } else null
    }

    fun launched(component: ComponentName) {
        scope.launch {
            synchronized(lock) {
                val existing = entries.value.ifEmpty(::readEntries)
                val item = existing.firstOrNull { it.component == component } ?: RecentEntry(component)
                val next = listOf(item) + existing.filterNot { it.component == component }
                next.drop(maxCards).forEach { file(it.component).delete() }
                persist(next.take(maxCards))
            }
        }
    }

    fun denied(request: CaptureRequest) {
        scope.launch {
            synchronized(lock) {
                val existing = entries.value.ifEmpty(::readEntries)
                val previous = existing.find { it.component == request.component } ?: RecentEntry(request.component)
                val secure = previous.copy(aspect = null, version = 0, denied = true)
                file(request.component).delete()
                persist(existing.withCapture(secure))
            }
        }
    }

    fun save(request: CaptureRequest, software: Bitmap) {
        scope.launch {
            try {
                val aspect = software.width.toFloat() / software.height
                val width = request.width.coerceAtMost(software.width)
                val height = (width / aspect).roundToInt().coerceAtLeast(1)
                val thumbnail = Bitmap.createScaledBitmap(software, width, height, true)
                try {
                    synchronized(lock) {
                        directory.mkdirs()
                        val target = file(request.component)
                        val partial = target.resolveSibling(target.name + ".part")
                        val written = runCatching { partial.outputStream().use { thumbnail.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                            .getOrDefault(false)
                        if (written && partial.renameTo(target)) {
                            val existing = entries.value.ifEmpty(::readEntries)
                            val updated = RecentEntry(request.component, aspect, SystemClock.elapsedRealtime())
                            persist(existing.withCapture(updated))
                        } else partial.delete()
                    }
                } finally {
                    if (thumbnail !== software) thumbnail.recycle()
                }
            } finally {
                software.recycle()
            }
        }
    }

    private fun List<RecentEntry>.withCapture(updated: RecentEntry): List<RecentEntry> {
        if (none { it.component == updated.component }) return (listOf(updated) + this).take(maxCards)
        return map { if (it.component == updated.component) updated else it }
    }

    fun bitmap(entry: RecentEntry): Bitmap? =
        if (entry.aspect == null) null else runCatching { BitmapFactory.decodeFile(file(entry.component).path) }.getOrNull()

    private fun file(component: ComponentName): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(component.flattenToString().toByteArray())
        return File(directory, hash.joinToString("") { "%02x".format(it) } + ".png")
    }

    private fun readEntries(): List<RecentEntry> = runCatching {
        val saved = JSONArray(entriesFile.readText())
        buildList {
            for (index in 0 until saved.length()) {
                val row = saved.getJSONObject(index)
                val component = ComponentName.unflattenFromString(row.getString("component")) ?: continue
                add(RecentEntry(component, row.optDouble("aspect").takeIf { it.isFinite() && it > 0 }?.toFloat(),
                    row.optLong("version"), row.optBoolean("denied")))
            }
        }
    }.getOrDefault(emptyList())

    private fun persist(items: List<RecentEntry>) {
        directory.mkdirs()
        val result = JSONArray()
        items.forEach { item ->
            result.put(JSONObject().apply {
                put("component", item.component.flattenToString())
                put("aspect", item.aspect)
                put("version", item.version)
                put("denied", item.denied)
            })
        }
        runCatching {
            val partial = File(directory, "index.part")
            partial.writeText(result.toString())
            partial.renameTo(entriesFile)
        }
        entries.value = items
    }

    companion object {
        private const val thumbnailBucket = 64
        private const val maxCards = 32
        private var instance: RecentStore? = null

        @Synchronized fun get(context: Context): RecentStore =
            instance ?: RecentStore(context.applicationContext).also { instance = it }
    }
}

@Composable
internal fun CardsPage(apps: List<LauncherActivityInfo>, open: (LauncherActivityInfo) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { RecentStore.get(context) }
    val entries by store.entries.collectAsState()
    val recent = entries.mapNotNull { entry ->
        apps.find { app -> app.componentName == entry.component }?.let { entry to it }
    }
    val tokens = LocalTokens.current
    val foreground = foregroundCycle()
    val enabled = remember(context, foreground) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == EnyoCaptureService::class.java.name
        }
    }
    Column(Modifier.fillMaxSize().padding(tokens.margin)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val cardWidth = minOf(maxWidth * tokens.recentWidthFraction, maxHeight * tokens.recentHeightFraction)
            val measured = with(density) { cardWidth.roundToPx() }
            SideEffect { store.setTargetWidth(measured) }
            if (recent.isEmpty()) Text(stringResource(R.string.cards_empty), style = tokens.secondary)
            else {
                val visible = recent.take(tokens.recentVisible)
                val frontAspect = visible.first().first.aspect ?: tokens.recentDefaultAspect
                val wide = ((frontAspect - tokens.recentTallAspect) /
                    (tokens.recentSquareAspect - tokens.recentTallAspect)).coerceIn(0f, 1f)
                visible.asReversed().forEachIndexed { index, (entry, app) ->
                    val distance = visible.lastIndex - index
                    val direction = if (distance % 2 == 0) -1f else 1f
                    val rotation = (tokens.recentRotation + wide * tokens.recentRotation) * distance * direction
                    val offset = cardWidth * (tokens.recentOffset - wide * tokens.recentOffset / 2)
                    val ratio = entry.aspect ?: tokens.recentDefaultAspect
                    val width = minOf(cardWidth, maxHeight * ratio * tokens.recentHeightUse)
                    val artwork by produceState<Bitmap?>(null, entry) {
                        value = withContext(Dispatchers.IO) { store.bitmap(entry) }
                    }
                    Surface(
                        onClick = { open(app) },
                        modifier = Modifier.offset(x = offset * distance * direction, y = tokens.gap * distance)
                            .width(width).aspectRatio(ratio)
                            .graphicsLayer { rotationZ = rotation },
                        shape = RoundedCornerShape(tokens.radius),
                        color = tokens.surface,
                        shadowElevation = tokens.elevation[2]
                    ) {
                        val image = artwork
                        if (image != null) {
                            Image(image.asImageBitmap(), app.label.toString(), Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        } else Box(Modifier.padding(tokens.padding), contentAlignment = Alignment.Center) {
                            val iconSize = with(density) { tokens.touch.roundToPx() }
                            val icon by produceState<Bitmap?>(null, app, iconSize) {
                                value = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val drawable = app.getIcon(density.density.times(160).roundToInt())
                                        Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888).also {
                                            drawable.setBounds(0, 0, iconSize, iconSize)
                                            drawable.draw(Canvas(it))
                                        }
                                    }.getOrNull()
                                }
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(tokens.gap)
                            ) {
                                icon?.let { Image(it.asImageBitmap(), null, Modifier.size(tokens.touch)) }
                                Text(app.label.toString(), style = tokens.state, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 34 && !enabled) {
            TextButton(onClick = { context.launchSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text(stringResource(R.string.cards_capture_permission), color = tokens.ink)
            }
        }
    }
}
