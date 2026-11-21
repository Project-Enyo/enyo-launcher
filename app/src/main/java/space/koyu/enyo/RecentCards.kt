package space.koyu.enyo

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Image
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
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
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class RecentEntry(
    val component: ComponentName,
    val aspect: Float? = null,
    val version: Long = 0,
    val denied: Boolean = false,
    val stack: String? = null
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
                            val updated = (existing.find { it.component == request.component } ?: RecentEntry(request.component))
                                .copy(aspect = aspect, version = SystemClock.elapsedRealtime())
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

    fun group(dragged: ComponentName, destination: ComponentName) {
        if (dragged == destination) return
        scope.launch {
            synchronized(lock) {
                val current = entries.value
                val from = current.find { it.component == dragged } ?: return@synchronized
                val into = current.find { it.component == destination } ?: return@synchronized
                if (from.stack != null && from.stack == into.stack) return@synchronized
                val stack = into.stack ?: UUID.randomUUID().toString()
                persist(current.map {
                    if (it.component == from.component || it.component == into.component ||
                        (from.stack != null && from.stack == it.stack)
                    ) it.copy(stack = stack) else it
                }.withoutSingleStacks())
            }
        }
    }

    fun detach(component: ComponentName) {
        scope.launch {
            synchronized(lock) {
                persist(entries.value.map { if (it.component == component) it.copy(stack = null) else it }
                    .withoutSingleStacks())
            }
        }
    }

    fun dismiss(component: ComponentName) {
        scope.launch {
            synchronized(lock) {
                if (capture?.component == component) capture = null
                file(component).delete()
                persist(entries.value.filterNot { it.component == component }.withoutSingleStacks())
            }
        }
    }

    private fun List<RecentEntry>.withoutSingleStacks(): List<RecentEntry> {
        val grouped = groupBy { it.stack }
        return map { if (it.stack != null && grouped[it.stack]?.size == 1) it.copy(stack = null) else it }
    }

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
                    row.optLong("version"), row.optBoolean("denied"), row.optString("stack").ifBlank { null }))
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
                put("stack", item.stack)
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

private data class BoardCard(val entry: RecentEntry, val app: LauncherActivityInfo)

@Composable
internal fun CardsPage(apps: List<LauncherActivityInfo>, open: (LauncherActivityInfo) -> Unit, first: FocusRequester) {
    val context = LocalContext.current
    val store = remember(context) { RecentStore.get(context) }
    val entries by store.entries.collectAsState()
    val groups = entries.mapNotNull { entry ->
        apps.find { app -> app.componentName == entry.component }?.let { BoardCard(entry, it) }
    }.groupBy { it.entry.stack ?: it.entry.component.flattenToString() }.values.toList()
    val tokens = LocalTokens.current
    val foreground = foregroundCycle()
    val enabled = remember(context, foreground) {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == EnyoCaptureService::class.java.name
        }
    }
    var start by remember { mutableIntStateOf(0) }
    var openedStack by remember { mutableStateOf<String?>(null) }
    val firstIndex = start.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
    val visible = groups.drop(firstIndex).take(tokens.recentVisible)
    val bounds = remember { mutableMapOf<ComponentName, Rect>() }
    Column(Modifier.fillMaxSize().padding(tokens.margin)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val cardWidth = minOf(maxWidth * tokens.recentWidthFraction, maxHeight * tokens.recentHeightFraction)
            SideEffect { store.setTargetWidth(with(density) { cardWidth.roundToPx() }) }
            if (visible.isEmpty()) Text(stringResource(R.string.cards_empty), style = tokens.secondary)
            else {
                val frontAspect = visible.first().first().entry.aspect ?: tokens.recentDefaultAspect
                val wide = ((frontAspect - tokens.recentTallAspect) /
                    (tokens.recentSquareAspect - tokens.recentTallAspect)).coerceIn(0f, 1f)
                visible.asReversed().forEachIndexed { index, cluster ->
                    val card = cluster.first()
                    val entry = card.entry
                    val distance = visible.lastIndex - index
                    val direction = if (distance % 2 == 0) -1f else 1f
                    val rotation = (tokens.recentRotation + wide * tokens.recentRotation) * distance * direction
                    val fan = cardWidth * (tokens.recentOffset - wide * tokens.recentOffset / 2)
                    val ratio = entry.aspect ?: tokens.recentDefaultAspect
                    val width = minOf(cardWidth, maxHeight * ratio * tokens.recentHeightUse)
                    key(entry.component) {
                        val activate = {
                            if (cluster.size > 1) openedStack = entry.stack else open(card.app)
                        }
                        val targets = visible.map { it.first() }.filter { it.entry.component != entry.component }
                        val groupLabel = stringResource(R.string.cards_stack_with)
                        val dismissLabel = stringResource(R.string.cards_dismiss)
                        val actions = targets.map { target ->
                            CustomAccessibilityAction(
                                context.getString(R.string.cards_stack_action, groupLabel, target.app.label)
                            ) {
                                store.group(entry.component, target.entry.component)
                                true
                            }
                        } + CustomAccessibilityAction(dismissLabel) { store.dismiss(entry.component); true }
                        CardTile(
                            card,
                            store,
                            cluster.size,
                            modifier = Modifier
                                .offset(x = fan * distance * direction, y = tokens.gap * distance)
                                .width(width).aspectRatio(ratio)
                                .then(if (distance == 0) Modifier.focusRequester(first) else Modifier),
                            rotation = rotation,
                            activate = activate,
                            actions = actions,
                            bounds = { bounds[entry.component] = it },
                            drop = { point ->
                                targets.firstOrNull { bounds[it.entry.component]?.contains(point) == true }?.let {
                                    store.group(entry.component, it.entry.component)
                                }
                            },
                            dismiss = { store.dismiss(entry.component) }
                        )
                    }
                }
            }
        }
        if (groups.size > tokens.recentVisible) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = firstIndex > 0, onClick = { start = firstIndex - 1 }) {
                    Text(stringResource(R.string.older_cards_back), color = tokens.ink)
                }
                TextButton(enabled = firstIndex + tokens.recentVisible < groups.size, onClick = { start = firstIndex + 1 }) {
                    Text(stringResource(R.string.older_cards), color = tokens.ink)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 34 && !enabled) {
            TextButton(onClick = { context.launchSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text(stringResource(R.string.cards_capture_permission), color = tokens.ink)
            }
        }
    }
    val expanded = groups.firstOrNull { openedStack != null && it.first().entry.stack == openedStack }
    if (expanded != null) AlertDialog(
        onDismissRequest = { openedStack = null },
        title = { Text(stringResource(R.string.cards_stack)) },
        text = {
            LazyColumn {
                items(expanded, key = { it.entry.component.flattenToString() }) { row ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { openedStack = null; open(row.app) }, modifier = Modifier.weight(1f)) {
                            Text(row.app.label.toString())
                        }
                        TextButton(onClick = { store.detach(row.entry.component) }) {
                            Text(stringResource(R.string.cards_unstack))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { openedStack = null }) { Text(stringResource(R.string.back)) }
        }
    )
}

@Composable
private fun CardTile(
    card: BoardCard,
    store: RecentStore,
    count: Int,
    modifier: Modifier,
    rotation: Float,
    activate: () -> Unit,
    actions: List<CustomAccessibilityAction>,
    bounds: (Rect) -> Unit,
    drop: (Offset) -> Unit,
    dismiss: () -> Unit
) {
    val tokens = LocalTokens.current
    val entry = card.entry
    val illustration by produceState<Bitmap?>(null, entry) {
        value = withContext(Dispatchers.IO) { store.bitmap(entry) }
    }
    var displacement by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(Rect.Zero) }
    var settling by remember { mutableStateOf(false) }
    var exitProgress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    fun settle(remove: Boolean, height: Float) {
        settling = true
        scope.launch {
            val initial = displacement
            val end = if (remove) -(position.bottom + height * tokens.recentDismissFraction) else 0f
            val animation: androidx.compose.animation.core.AnimationSpec<Float> = if (remove) tween(tokens.recentExitMillis, easing = FastOutSlowInEasing)
            else spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)
            animate(initialValue = 0f, targetValue = 1f, animationSpec = animation) { value, _ ->
                val fraction = value.coerceIn(0f, 1f)
                displacement = Offset(initial.x * (1f - fraction), initial.y + (end - initial.y) * fraction)
                if (remove) exitProgress = fraction
            }
            if (remove) dismiss()
            else {
                dragging = false
                settling = false
                displacement = Offset.Zero
            }
        }
    }
    val dropCard by rememberUpdatedState(drop)
    val settleCard by rememberUpdatedState<(Boolean, Float) -> Unit>(::settle)
    Surface(
        onClick = activate,
        modifier = modifier
            .zIndex(if (dragging) 1f else 0f)
            .onGloballyPositioned {
                if (!dragging) {
                    position = it.boundsInRoot()
                    bounds(position)
                }
            }
            .pointerInput(entry.component) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (settling) return@awaitEachGesture
                    val origin = position.topLeft + down.position
                    var latest = down.position
                    val movedVertically = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                            if (change.isConsumed || !change.pressed) return@withTimeoutOrNull false
                            latest = change.position
                            val delta = latest - down.position
                            if (delta.getDistance() > viewConfiguration.touchSlop) {
                                if (abs(delta.x) > abs(delta.y)) return@withTimeoutOrNull false
                                change.consume()
                                return@withTimeoutOrNull true
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        false
                    }
                    if (movedVertically == false) return@awaitEachGesture
                    val stackDrag = movedVertically == null
                    dragging = true
                    val initial = latest - down.position
                    displacement = if (stackDrag) initial else Offset(0f, initial.y)
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed) {
                            settleCard(false, size.height.toFloat())
                            break
                        }
                        val delta = change.position - down.position
                        displacement = if (stackDrag) delta else Offset(0f, delta.y)
                        change.consume()
                        if (!change.pressed) {
                            if (stackDrag) {
                                if (delta.getDistance() > viewConfiguration.touchSlop) dropCard(origin + delta)
                                settleCard(false, size.height.toFloat())
                            } else {
                                val remove = delta.y < -size.height * tokens.recentDismissFraction
                                settleCard(remove, size.height.toFloat())
                            }
                            break
                        }
                    }
                }
            }
            .graphicsLayer {
                rotationZ = rotation
                translationX = displacement.x
                translationY = displacement.y
                alpha = 1f - exitProgress
            }
            .cardInput(activate = activate)
            .semantics { customActions = actions },
        shape = RoundedCornerShape(tokens.radius),
        color = tokens.surface,
        shadowElevation = tokens.elevation[2]
    ) {
        Box(contentAlignment = Alignment.Center) {
            val image = illustration
            if (image != null) {
                Image(image.asImageBitmap(), card.app.label.toString(), Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            } else Column(
                modifier = Modifier.padding(tokens.padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(tokens.gap)
            ) {
                AppIcon(card.app)
                Text(card.app.label.toString(), style = tokens.state, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (count > 1) Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(tokens.gap),
                shape = RoundedCornerShape(tokens.radius),
                color = tokens.surface
            ) {
                val countLabel = androidx.compose.ui.res.pluralStringResource(R.plurals.cards_stack_count, count, count)
                Text(countLabel, Modifier.padding(tokens.padding), style = tokens.secondary)
            }
        }
    }
}
