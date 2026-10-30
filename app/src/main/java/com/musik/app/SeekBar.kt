package com.musik.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Where the song you HEAR is right now. Read straight from the player in this app (no delay through the media
 * session), during a crossfade from the song that's fading in – so the timeline never shows the old song.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayClock(private val vm: AppVm) {
    private var seekTarget = -1L   // a seek we just asked for: shown until the player gets there
    private var seekAt = 0L
    private var seekId: String? = null

    private fun metaDuration(): Long = (vm.shown?.toSong()?.duration ?: 0) * 1000L

    /** (position ms, duration ms) */
    fun now(): Pair<Long, Long> {
        val shownId = vm.shown?.mediaId
        val a = PlaybackService.audiblePosition()
        var pos: Long; var dur: Long
        if (a != null && (shownId == null || a.third == shownId)) { pos = a.first; dur = if (a.second > 0) a.second else metaDuration() }
        else {
            val c = vm.player
            // a new song is starting: start from 0, never from where the last one was
            pos = if (c != null && c.currentMediaItem?.mediaId == shownId) c.currentPosition else 0L
            dur = if (c != null && c.currentMediaItem?.mediaId == shownId && c.duration > 0) c.duration else metaDuration()
        }
        if (seekTarget >= 0) {
            val waiting = seekId == shownId && System.currentTimeMillis() - seekAt < 1500 && kotlin.math.abs(pos - seekTarget) > 1500
            if (waiting) pos = seekTarget else seekTarget = -1
        }
        return pos.coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE) to dur
    }

    fun seek(ms: Long) {
        seekTarget = ms; seekAt = System.currentTimeMillis(); seekId = vm.shown?.mediaId
        vm.seekTo(ms)
    }
}

/** Ticks with the screen while music plays (redraws only the bar – nothing else recomposes), slowly when paused. */
@Composable
fun rememberPlayTick(playing: Boolean, everyMs: Long = 30): State<Long> {
    val t = remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing, everyMs) {
        while (true) {
            if (playing) { androidx.compose.runtime.withFrameMillis { t.longValue = it }; delay(everyMs) } // ~30 fps for the big bar
            else { t.longValue = System.currentTimeMillis(); delay(250) }
        }
    }
    return t
}

/** Slim, modern song timeline: tap or drag to seek, arrow keys ±5 s, accessible as a slider. */
@Composable
fun SeekBar(vm: AppVm, color: Color = Color.White, modifier: Modifier = Modifier) {
    val clock = remember(vm) { PlayClock(vm) }
    val tick = rememberPlayTick(vm.isPlaying)
    var drag by remember { mutableStateOf<Float?>(null) }   // 0..1 while scrubbing
    var pressed by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val active = drag != null || pressed
    val thick by animateDpAsState(if (active) 6.dp else 4.dp, tween(120), label = "seekThick")
    val thumb by animateDpAsState(if (active) 16.dp else 11.dp, tween(120), label = "seekThumb")
    val durNow = { clock.now().second }
    fun seekFraction(f: Float) { val d = durNow(); if (d > 0) clock.seek((f.coerceIn(0f, 1f) * d).toLong()) }
    // the semantic value only needs to change once per second
    val secPos by remember { derivedStateOf { tick.value; clock.now().first / 1000 } }
    val secDur by remember { derivedStateOf { tick.value; clock.now().second / 1000 } }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                        onTap = { o -> val pad = 8.dp.toPx(); seekFraction((o.x - pad) / (size.width - 2 * pad)) })
                }
                .pointerInput(Unit) {
                    val pad = 8.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = ((o.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { seekFraction(it) }; drag = null },
                        onDragCancel = { drag = null }
                    ) { change, _ -> change.consume(); drag = ((change.position.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) }
                }
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val (p, d) = clock.now()
                    when (e.key) {
                        Key.DirectionRight -> { if (d > 0) clock.seek(minOf(d - 1000, p + 5000)); true }
                        Key.DirectionLeft -> { clock.seek(maxOf(0, p - 5000)); true }
                        else -> false
                    }
                }
                .focusable()
                .semantics {
                    contentDescription = "Song position"
                    stateDescription = "${fmt(secPos * 1000)} of ${fmt(secDur * 1000)}"
                    progressBarRangeInfo = ProgressBarRangeInfo(if (secDur > 0) (secPos.toFloat() / secDur).coerceIn(0f, 1f) else 0f, 0f..1f)
                    setProgress { f -> seekFraction(f); true }
                }
                .drawBehind {
                    tick.value // redraw on every tick – only this bar
                    val (pos, dur) = clock.now()
                    val f = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    val pad = 8.dp.toPx(); val y = size.height / 2
                    val x0 = pad; val x1 = size.width - pad; val x = x0 + (x1 - x0) * f
                    val h = thick.toPx()
                    drawLine(color.copy(alpha = 0.22f), Offset(x0, y), Offset(x1, y), h, StrokeCap.Round)
                    if (x > x0) drawLine(color.copy(alpha = if (active) 1f else 0.92f), Offset(x0, y), Offset(x, y), h, StrokeCap.Round)
                    if (focused || active) drawCircle(color.copy(alpha = 0.18f), thumb.toPx(), Offset(x, y))
                    drawCircle(color, thumb.toPx() / 2, Offset(x, y))
                }
        )
        TimeRow(clock, tick, drag, vm.buffering)
    }
}

@Composable
private fun TimeRow(clock: PlayClock, tick: State<Long>, drag: Float?, buffering: Boolean) {
    // recomposes once per second, not every frame
    val shownSec by remember(drag) { derivedStateOf { tick.value; val (p, d) = clock.now(); (if (drag != null) (drag * d).toLong() else p) / 1000 } }
    val durSec by remember { derivedStateOf { tick.value; clock.now().second / 1000 } }
    val style = TextStyle(fontSize = 11.5.sp, color = Color(0xFFBDBDBD), fontFeatureSettings = "tnum")
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text(fmt(shownSec * 1000), style = style)
        Spacer(Modifier.weight(1f))
        Text(if (buffering && durSec == 0L) "–:––" else fmt(durSec * 1000), style = style)
    }
}

/** The thin line under the mini player. */
@Composable
fun MiniProgress(vm: AppVm, modifier: Modifier = Modifier) {
    val clock = remember(vm) { PlayClock(vm) }
    val tick = rememberPlayTick(vm.isPlaying, 400) // a 2 dp line doesn't need more – keeps every other screen light
    Box(modifier.fillMaxWidth().height(2.dp).drawBehind {
        tick.value
        val (p, d) = clock.now(); val f = if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f
        drawLine(Color(0x44FFFFFF), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
        if (f > 0) drawLine(Color.White, Offset(0f, size.height / 2), Offset(size.width * f, size.height / 2), size.height, StrokeCap.Round)
    })
}
