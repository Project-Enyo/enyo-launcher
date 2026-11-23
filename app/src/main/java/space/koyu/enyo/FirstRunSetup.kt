package space.koyu.enyo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * The sounds of the first setup: a gull with the intro, the sea while setup runs, and a ding for
 * each step forward and a lower one for each step back. They follow the media volume, and the
 * person can turn them off.
 */
private class SetupSounds(private val context: Context) {
    // Played as media, so the media volume sets how loud the setup is, on silent too.
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build()
    private val dingLoaded = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val ding = pool.apply { setOnLoadCompleteListener { _, _, _ -> dingLoaded.complete(Unit) } }.load(context, R.raw.setup_ding, 1)
    private var gull: MediaPlayer? = null
    private var sea: MediaPlayer? = null
    var muted by mutableStateOf(false)
        private set

    /** A player made ready off the main thread, so decoding never holds up a frame. */
    private suspend fun prepared(resource: Int, looping: Boolean): MediaPlayer? = kotlinx.coroutines.suspendCancellableCoroutine { done ->
        val player = MediaPlayer()
        runCatching {
            player.setAudioAttributes(attributes)
            context.resources.openRawResourceFd(resource).use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            player.isLooping = looping
            player.setOnPreparedListener { if (done.isActive) done.resumeWith(Result.success(it)) }
            player.setOnErrorListener { _, _, _ -> if (done.isActive) done.resumeWith(Result.success(null)); true }
            player.prepareAsync()
        }.onFailure { player.release(); if (done.isActive) done.resumeWith(Result.success(null)) }
        done.invokeOnCancellation { player.release() }
    }

    /** Readies every sound, so the intro's picture and sound can start on the same frame. Gives up after a moment. */
    suspend fun prepare() {
        kotlinx.coroutines.withTimeoutOrNull(1_500) {
            kotlinx.coroutines.coroutineScope {
                launch { gull = prepared(R.raw.setup_seagull, false) }
                launch { sea = prepared(R.raw.setup_waves, true) }
                launch { dingLoaded.await() }
            }
        }
    }

    fun intro() {
        if (muted) return
        gull?.runCatching { setVolume(0.8f, 0.8f); start() }
    }

    fun startSea() {
        if (muted || sea?.isPlaying == true) return
        sea?.runCatching { setVolume(0f, 0f); start() }
    }

    /** The sea level, from 0 to 1, for fading in and out. */
    fun seaVolume(level: Float) {
        val volume = (level * 0.55f).coerceIn(0f, 1f)
        sea?.runCatching { setVolume(volume, volume) }
    }

    fun pauseSea() { sea?.runCatching { if (isPlaying) pause() } }

    /** A step forward rings at its own pitch, a step back lower. */
    fun step(forward: Boolean) {
        if (!muted) pool.play(ding, 0.6f, 0.6f, 1, 0, if (forward) 1f else 0.8f)
    }

    fun toggle() {
        muted = !muted
        if (muted) { gull?.runCatching { stop() }; pauseSea() }
    }

    fun release() {
        gull?.release(); gull = null
        sea?.release(); sea = null
        pool.release()
    }
}

/** The animated sea: three layers of waves whose surface sits at [level] of the height. */
@Composable
private fun Sea(level: () -> Float, modifier: Modifier = Modifier) {
    val tokens = LocalTokens.current
    val motion = rememberInfiniteTransition(label = "sea")
    val phase by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(7_000, easing = LinearEasing), RepeatMode.Restart), label = "seaPhase")
    val colors = listOf(tokens.accent.copy(alpha = 0.18f), tokens.accent.copy(alpha = 0.3f), tokens.accentContainer)
    Canvas(modifier.fillMaxSize()) {
        val surface = size.height * (1f - level())
        colors.forEachIndexed { layer, color ->
            val amplitude = size.height * 0.012f * (3 - layer)
            val length = size.width * (0.9f + layer * 0.35f)
            val shift = (phase * (if (layer % 2 == 0) 1 else -1) + layer * 0.27f) * 2 * PI.toFloat()
            val top = surface + layer * size.height * 0.018f
            val path = Path().apply {
                moveTo(0f, size.height)
                var x = 0f
                while (x <= size.width + 8f) {
                    lineTo(x, top + amplitude * sin(x / length * 2 * PI.toFloat() + shift))
                    x += 8f
                }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path, color)
        }
    }
}

private enum class SetupStepPage { Welcome, Permissions, Banners, Guide }

/**
 * The first setup: an intro with the sea and a gull that settles into a guided setup, one step at
 * a time, ending with the offer to learn Enyo with the guide.
 */
@Composable
internal fun FirstRunSetup(close: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalTokens.current
    val scope = rememberCoroutineScope()
    val sounds = remember(context) { SetupSounds(context) }
    val sea = remember { Animatable(0f) }
    val intro = remember { Animatable(0f) }
    var introDone by rememberSaveableFlag()
    // The intro waits for its sounds, then picture and sound start together.
    var started by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(SetupStepPage.Welcome) }
    var guide by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    val icon = remember(context) {
        runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(256, 256).asImageBitmap() }.getOrNull()
    }
    DisposableEffect(sounds) { onDispose { sounds.release() } }
    // The sea pauses while the person is away granting access, and comes back with them.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, sounds) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> sounds.pauseSea()
                Lifecycle.Event.ON_START -> if (introDone && !finishing) { sounds.startSea(); sounds.seaVolume(1f) }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun settle() {
        if (introDone) return
        introDone = true
        scope.launch { intro.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        scope.launch { sea.animateTo(0.1f, tween(1_200, easing = FastOutSlowInEasing)) }
    }
    LaunchedEffect(Unit) {
        sounds.prepare()
        if (introDone) { sea.snapTo(0.1f); intro.snapTo(1f); started = true; sounds.startSea(); sounds.seaVolume(1f); return@LaunchedEffect }
        // The first frame is drawn before anything moves, so nothing stutters in.
        androidx.compose.runtime.withFrameNanos { }
        started = true
        sounds.intro()
        sounds.startSea()
        launch { sea.animateTo(0.42f, tween(1_600, easing = FastOutSlowInEasing)) }
        // The waves crash in right with the gull, both together, then the setup rises out of the sea.
        val fade = Animatable(0f)
        launch { fade.animateTo(1f, tween(600)) { sounds.seaVolume(value) } }
        delay(3_800)
        settle()
    }
    fun go(next: SetupStepPage) {
        sounds.step(next.ordinal > step.ordinal)
        step = next
    }
    fun finish() {
        if (finishing) return
        finishing = true
        sounds.step(true)
        scope.launch {
            val fade = Animatable(1f)
            fade.animateTo(0f, tween(1_000)) { sounds.seaVolume(value) }
            sounds.pauseSea()
            close()
        }
    }
    BackHandler(enabled = introDone && step != SetupStepPage.Welcome && !guide) { go(SetupStepPage.entries[step.ordinal - 1]) }

    // Only these switches recompose; the animations themselves are read while drawing.
    val introShown by remember { derivedStateOf { intro.value < 1f } }
    val setupShown by remember { derivedStateOf { intro.value > 0f } }
    Box(Modifier.fillMaxSize().background(tokens.settingsBackground)) {
        Sea({ sea.value })
        // The intro: the icon and name over the swelling sea; a tap skips ahead.
        if (introShown && started) Column(
            Modifier.fillMaxSize().graphicsLayer { alpha = 1f - intro.value; translationY = -intro.value * size.height * 0.08f }
                .appPress({ settle() }),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            val appear = remember { Animatable(0f) }
            LaunchedEffect(Unit) { delay(1_300); appear.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
            // The icon comes to life: sky, a rising sun, the sea and a palm in the wind.
            AnimatedEnyoIcon(Modifier.size(tokens.iconSize * 2.4f), delayMillis = 200)
            Spacer(Modifier.height(tokens.padding))
            Text("Enyo", Modifier.graphicsLayer { alpha = appear.value }, style = tokens.state.copy(fontSize = tokens.state.fontSize * 1.6f))
        }
        if (setupShown) Column(
            Modifier.fillMaxSize().safeDrawingPadding().graphicsLayer { alpha = intro.value; translationY = (1f - intro.value) * size.height * 0.06f }
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = tokens.margin, vertical = tokens.gap), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.setup_step, step.ordinal + 1, SetupStepPage.entries.size), Modifier.weight(1f), style = tokens.caption)
                val soundLabel = stringResource(if (sounds.muted) R.string.setup_sound_on else R.string.setup_sound_off)
                Box(Modifier.size(tokens.touch).clip(CircleShape).appPress({
                    sounds.toggle()
                    if (!sounds.muted) { sounds.startSea(); sounds.seaVolume(1f) }
                }).semantics { contentDescription = soundLabel }, contentAlignment = Alignment.Center) {
                    EnyoGlyph(if (sounds.muted) Glyph.SoundOff else Glyph.Sound, color = tokens.quiet)
                }
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(tween(tokens.settingsTransitionMillis)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(tokens.settingsTransitionMillis)))
                        .togetherWith(slideOutHorizontally(tween(tokens.settingsTransitionMillis)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(tokens.settingsTransitionMillis / 2)))
                },
                modifier = Modifier.weight(1f),
                label = "setupStep"
            ) { page ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = tokens.margin, vertical = tokens.gap),
                    verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                    when (page) {
                        SetupStepPage.Welcome -> {
                            item {
                                Column(Modifier.fillMaxWidth().padding(vertical = tokens.padding), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
                                    AnimatedEnyoIcon(Modifier.size(tokens.iconSize * 1.6f), assembled = true)
                                    Text(stringResource(R.string.setup_welcome_title), style = tokens.state, textAlign = TextAlign.Center)
                                    Text(stringResource(R.string.setup_welcome_body), style = tokens.secondary, textAlign = TextAlign.Center)
                                }
                            }
                        }
                        SetupStepPage.Permissions -> {
                            item { SettingsHeading(stringResource(R.string.setup_permissions)) }
                            item { ListCard { ListNote(stringResource(R.string.setup_intro)) } }
                            item { SetupPermissions() }
                        }
                        SetupStepPage.Banners -> {
                            item { SettingsHeading(stringResource(R.string.setup_banners_title)) }
                            item { OverlayPreference(recommended = true) }
                            item { ListCard { ListNote(stringResource(R.string.battery_discovery)) } }
                        }
                        SetupStepPage.Guide -> {
                            item { SettingsHeading(stringResource(R.string.setup_learn_title)) }
                            item {
                                ListCard {
                                    ListNote(stringResource(R.string.setup_learn_body))
                                    SettingsAction(stringResource(R.string.setup_learn_open), Glyph.Note, summary = stringResource(R.string.guide_summary)) {
                                        sounds.step(true)
                                        guide = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = tokens.margin).padding(bottom = tokens.margin),
                horizontalArrangement = Arrangement.spacedBy(tokens.gap), verticalAlignment = Alignment.CenterVertically) {
                if (step != SetupStepPage.Welcome) PillButton(stringResource(R.string.setup_back)) { go(SetupStepPage.entries[step.ordinal - 1]) }
                Spacer(Modifier.weight(1f))
                if (step == SetupStepPage.Guide) PillButton(stringResource(R.string.setup_finish), filled = true, enabled = !finishing) { finish() }
                else PillButton(stringResource(if (step == SetupStepPage.Welcome) R.string.setup_begin else R.string.setup_next), filled = true) {
                    go(SetupStepPage.entries[step.ordinal + 1])
                }
            }
        }
    }
    if (guide) GuidePage { guide = false; sounds.step(false) }
}

/** Whether the intro already played, kept across rotation so it does not play twice. */
@Composable
private fun rememberSaveableFlag(): MutableState<Boolean> = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
