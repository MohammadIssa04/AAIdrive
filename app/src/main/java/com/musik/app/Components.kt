@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.musik.app

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult

/* ================= colors ================= */

/** The look of the whole app: Default, Minimal, Neon, AMOLED Black or Glass (switchable live). */
object Th {
    var style by mutableStateOf("default")
    var bg by mutableStateOf(Color(0xFF121212)); private set
    var surface by mutableStateOf(Color(0xFF181818)); private set
    var card by mutableStateOf(Color(0xFF242424)); private set
    var card2 by mutableStateOf(Color(0xFF2E2E2E)); private set
    fun apply(st: String) {
        style = st
        val c = when (st) {
            "amoled" -> listOf(0xFF000000, 0xFF0A0A0A, 0xFF151515, 0xFF202020)
            "minimal" -> listOf(0xFF0F0F0F, 0xFF151515, 0xFF1C1C1C, 0xFF262626)
            "neon" -> listOf(0xFF07060D, 0xFF0F0B1C, 0xFF17122B, 0xFF221B3D)
            "glass" -> listOf(0x00000000L, 0x1FFFFFFFL, 0x29FFFFFFL, 0x38FFFFFFL)
            else -> listOf(0xFF121212, 0xFF181818, 0xFF242424, 0xFF2E2E2E)
        }
        bg = Color(c[0]); surface = Color(c[1]); card = Color(c[2]); card2 = Color(c[3])
    }
}
val Bg: Color get() = Th.bg
/** Background for full-screen layers (always solid, also in Glass). */
val BgSolid: Color get() = if (Th.style == "glass") Color(0xFF0B0B10) else Th.bg
val Surface1: Color get() = Th.surface
val Card: Color get() = Th.card
val Card2: Color get() = Th.card2
val Dim = Color(0xFFB3B3B3)
val Dim2 = Color(0xFF7A7A7A)
val Bad = Color(0xFFFF5C7A)
val Live = Color(0xFF3DDC97)

object Tint {
    var accent by mutableStateOf(Color(0xFF8B5CF6))
    val light: Color get() = lerp(accent, Color.White, 0.35f)
}

@Composable
fun MusikTheme(accent: Color, content: @Composable () -> Unit) {
    Tint.accent = accent
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent, onPrimary = Color.White, background = BgSolid, onBackground = Color.White,
            surface = Surface1, onSurface = Color.White, surfaceVariant = Card, onSurfaceVariant = Dim,
            secondaryContainer = lerp(accent, Bg, 0.6f), onSecondaryContainer = Color.White, surfaceContainer = Surface1,
            surfaceContainerHigh = if (Th.style == "glass") Color(0xFF1C1B24) else Card, surfaceContainerHighest = if (Th.style == "glass") Color(0xFF26252F) else Card2
        ), content = content
    )
}

/** Extracts a dark, vivid color from cover art (used for gradients like on album pages). */
private val coverColors = android.util.LruCache<String, Int>(300) // colours already worked out, so screens don't flicker

fun coverKey(url: String) = Regex("[?&]id=([^&]+)").find(url)?.groupValues?.get(1) ?: url

private val vibrantColors = android.util.LruCache<String, Int>(300)

/** A bright colour from the cover, for "colours from the album cover". */
@Composable
fun rememberVibrant(url: String?): Color? {
    val ctx = LocalContext.current
    val known = url?.let { vibrantColors.get(coverKey(it)) }
    var c by remember(url) { mutableStateOf(known?.let { Color(it) }) }
    LaunchedEffect(url) {
        if (url == null || known != null) return@LaunchedEffect
        val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(96).allowHardware(false).build())
        val bmp = ((r as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        val col = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val p = Palette.from(bmp).maximumColorCount(16).generate()
            (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch)?.rgb?.let { rgb ->
                val hsv = FloatArray(3); android.graphics.Color.colorToHSV(rgb, hsv)
                hsv[1] = hsv[1].coerceAtLeast(0.45f); hsv[2] = hsv[2].coerceIn(0.65f, 0.95f) // readable on dark backgrounds
                Color(android.graphics.Color.HSVToColor(hsv))
            }
        } ?: return@LaunchedEffect
        vibrantColors.put(coverKey(url), col.toArgb()); c = col
    }
    return c
}

/** Neon makes colours fully saturated and bright; Minimal mutes them. */
fun styleAccent(c: Color, style: String): Color {
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    when (style) { "neon" -> { hsv[1] = 1f; hsv[2] = 1f }; "minimal" -> { hsv[1] *= 0.55f; hsv[2] = hsv[2].coerceAtMost(0.8f) } }
    return Color(android.graphics.Color.HSVToColor(hsv))
}

@Composable
fun rememberCoverColor(url: String?, fallback: Color = Color(0xFF2A2A2A)): Color {
    val ctx = LocalContext.current
    val known = url?.let { coverColors.get(coverKey(it)) }
    var c by remember(url) { mutableStateOf(known?.let { Color(it) } ?: fallback) }
    LaunchedEffect(url) {
        if (url == null || known != null) return@LaunchedEffect
        val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(96).allowHardware(false).build())
        val bmp = ((r as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        val col = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { // heavy work off the UI thread
            val p = Palette.from(bmp).maximumColorCount(12).generate()
            (p.vibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch)?.let { lerp(Color(it.rgb), Color.Black, 0.35f) }
        } ?: return@LaunchedEffect
        coverColors.put(coverKey(url), col.toArgb())
        c = col
    }
    return c
}

/* ================= icons & buttons ================= */

@Composable
fun Ico(kind: String, size: Dp = 24.dp, tint: Color = Color.White) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        fun tri(x1: Float, x2: Float) = Path().apply { moveTo(x1 * w, 0.22f * h); lineTo(x2 * w, 0.5f * h); lineTo(x1 * w, 0.78f * h); close() }
        when (kind) {
            "play" -> drawPath(tri(0.32f, 0.8f), tint)
            "pause" -> {
                drawRect(tint, Offset(0.27f * w, 0.22f * h), Size(0.15f * w, 0.56f * h))
                drawRect(tint, Offset(0.58f * w, 0.22f * h), Size(0.15f * w, 0.56f * h))
            }
            "next" -> { drawPath(tri(0.22f, 0.66f), tint); drawRect(tint, Offset(0.7f * w, 0.24f * h), Size(0.09f * w, 0.52f * h)) }
            "prev" -> {
                drawPath(Path().apply { moveTo(0.78f * w, 0.22f * h); lineTo(0.34f * w, 0.5f * h); lineTo(0.78f * w, 0.78f * h); close() }, tint)
                drawRect(tint, Offset(0.21f * w, 0.24f * h), Size(0.09f * w, 0.52f * h))
            }
            "shuffle" -> { // two crossing arrows
                val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.085f * w, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                drawPath(Path().apply { moveTo(0.14f * w, 0.30f * h); cubicTo(0.42f * w, 0.30f * h, 0.50f * w, 0.70f * h, 0.80f * w, 0.70f * h) }, tint, style = st)
                drawPath(Path().apply { moveTo(0.14f * w, 0.70f * h); cubicTo(0.42f * w, 0.70f * h, 0.50f * w, 0.30f * h, 0.80f * w, 0.30f * h) }, tint, style = st)
                drawPath(Path().apply { moveTo(0.70f * w, 0.19f * h); lineTo(0.84f * w, 0.30f * h); lineTo(0.70f * w, 0.41f * h) }, tint, style = st)
                drawPath(Path().apply { moveTo(0.70f * w, 0.59f * h); lineTo(0.84f * w, 0.70f * h); lineTo(0.70f * w, 0.81f * h) }, tint, style = st)
            }
            "repeat", "repeat1" -> { // rounded loop with an arrow (and a small 1)
                val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.085f * w, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                drawRoundRect(tint, Offset(0.16f * w, 0.30f * h), Size(0.68f * w, 0.42f * h), androidx.compose.ui.geometry.CornerRadius(0.16f * w), style = st)
                drawPath(Path().apply { moveTo(0.48f * w, 0.19f * h); lineTo(0.62f * w, 0.30f * h); lineTo(0.48f * w, 0.41f * h) }, tint, style = st)
                if (kind == "repeat1") drawCircle(tint, 0.15f * w, Offset(0.5f * w, 0.51f * h))
                if (kind == "repeat1") drawRect(Color.Black, Offset(0.475f * w, 0.42f * h), Size(0.05f * w, 0.18f * h))
            }
            "queue" -> { // three lines + a small play triangle
                val sw = 0.08f * h
                drawRect(tint, Offset(0.16f * w, 0.24f * h), Size(0.62f * w, sw)); drawRect(tint, Offset(0.16f * w, 0.46f * h), Size(0.62f * w, sw))
                drawRect(tint, Offset(0.16f * w, 0.68f * h), Size(0.36f * w, sw))
                drawPath(Path().apply { moveTo(0.62f * w, 0.62f * h); lineTo(0.86f * w, 0.72f * h); lineTo(0.62f * w, 0.82f * h); close() }, tint)
            }
            "lyrics" -> { // speech bubble with text lines
                val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.08f * w, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                drawRoundRect(tint, Offset(0.14f * w, 0.18f * h), Size(0.72f * w, 0.52f * h), androidx.compose.ui.geometry.CornerRadius(0.12f * w), style = st)
                drawPath(Path().apply { moveTo(0.30f * w, 0.70f * h); lineTo(0.26f * w, 0.86f * h); lineTo(0.46f * w, 0.70f * h) }, tint, style = st)
                drawLine(tint, Offset(0.30f * w, 0.36f * h), Offset(0.70f * w, 0.36f * h), 0.07f * w, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, Offset(0.30f * w, 0.52f * h), Offset(0.56f * w, 0.52f * h), 0.07f * w, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            "jam" -> { // two overlapping circles (listening together)
                val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.08f * w)
                drawCircle(tint, 0.22f * w, Offset(0.38f * w, 0.5f * h), style = st); drawCircle(tint, 0.22f * w, Offset(0.62f * w, 0.5f * h), style = st)
            }
            "bars" -> { // small equalizer for the playing row
                listOf(0.2f to 0.5f, 0.45f to 0.25f, 0.7f to 0.4f).forEach { (x, top) ->
                    drawRect(tint, Offset(x * w, top * h), Size(0.14f * w, (0.85f - top) * h))
                }
            }
        }
    }
}

@Composable
fun IconBtn(onClick: () -> Unit, size: Dp = 44.dp, bg: Color? = null, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).then(if (bg != null) Modifier.background(bg) else Modifier).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun PlayCircle(onClick: () -> Unit, playing: Boolean = false, size: Dp = 56.dp) {
    IconBtn(onClick, size, Tint.accent) { Ico(if (playing) "pause" else "play", size * 0.5f, Color.White) }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) Tint.accent else Card2).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
}

@Composable
fun LikeBtn(vm: AppVm, id: String, size: Dp = 44.dp) {
    val on = id in vm.starred
    IconBtn({ vm.toggleStar(id) }, size) {
        Icon(if (on) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (on) Tint.accent else Dim)
    }
}

/* ================= images ================= */

@Composable
fun Cover(url: String?, size: Dp, shape: Shape = RoundedCornerShape(4.dp)) {
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(shape).background(Card2)
    )
}

@Composable
fun Collage(urls: List<String?>, size: Dp, shape: Shape = RoundedCornerShape(6.dp)) {
    val u = urls.filterNotNull().distinct()
    if (u.size < 4) { Cover(u.firstOrNull(), size, shape); return }
    Column(Modifier.size(size).clip(shape)) {
        Row { Cover(u[0], size / 2, RoundedCornerShape(0.dp)); Cover(u[1], size / 2, RoundedCornerShape(0.dp)) }
        Row { Cover(u[2], size / 2, RoundedCornerShape(0.dp)); Cover(u[3], size / 2, RoundedCornerShape(0.dp)) }
    }
}

@Composable
fun GradientTile(size: Dp, colors: List<Color>, shape: Shape = RoundedCornerShape(4.dp), content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(size).clip(shape).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center, content = content)
}

@Composable
fun LikedTile(size: Dp) {
    GradientTile(size, listOf(Color(0xFF4B2BD6), Tint.light)) {
        Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
fun Avatar(url: String?, name: String, size: Dp, ring: Color? = null) {
    Box(Modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(Brush.linearGradient(listOf(Tint.accent, Color(0xFFEC4899)))),
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp, color = Color.White)
            if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
        }
        if (ring != null) Box(Modifier.align(Alignment.BottomEnd).size(size / 3.5f).clip(CircleShape).background(BgSolid).padding(2.dp).clip(CircleShape).background(ring))
    }
}

/* ================= text helpers ================= */

@Composable
fun SectionTitle(t: String, sub: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (sub != null) Text(sub, color = Dim, fontSize = 12.sp)
            Text(t, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (action != null && onAction != null) Text(action, color = Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onAction).padding(6.dp))
    }
}

@Composable
fun SmallTitle(t: String) {
    Text(t.uppercase(), color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp))
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconBtn(onBack) { Text("‹", fontSize = 32.sp, modifier = Modifier.padding(bottom = 4.dp)) } else Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

fun ago(ts: Long): String {
    val d = System.currentTimeMillis() / 1000 - ts
    return when { d < 60 -> "just now"; d < 3600 -> "${d / 60} min ago"; d < 86400 -> "${d / 3600} h ago"; else -> "${d / 86400} d ago" }
}

fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

/* ================= data loading ================= */

sealed class Res<out T> {
    object Loading : Res<Nothing>()
    data class Err(val m: String) : Res<Nothing>()
    data class Ok<T>(val v: T) : Res<T>()
}

@Composable
fun <T> Load(key: Any, load: suspend () -> T, content: @Composable (T, () -> Unit) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var st by remember(key, tick) { mutableStateOf<Res<T>>(Res.Loading) }
    LaunchedEffect(key, tick) { st = try { Res.Ok(load()) } catch (e: Exception) { Res.Err(e.message ?: "Something went wrong") } }
    when (val s = st) {
        is Res.Loading -> Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator(color = Tint.accent) }
        is Res.Err -> Column(Modifier.fillMaxWidth().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text(s.m, color = Bad, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp)); Button(onClick = { tick++ }) { Text("Try again") }
        }
        is Res.Ok -> content(s.v) { tick++ }
    }
}

/* ================= rows & cards ================= */

@Composable
fun SongRow(vm: AppVm, song: Song, list: List<Song>, index: Int, number: Int? = null, extra: String? = null, onRemove: (() -> Unit)? = null, why: String? = null, onPlay: (() -> Unit)? = null) {
    val api = vm.api ?: return
    val playing = vm.current?.mediaId == song.id
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = { onPlay?.invoke() ?: vm.play(list, index) }, onLongClick = { vm.menuSong = song })
            .padding(start = 16.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (number != null) Text("$number", color = Dim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(28.dp))
        Cover(api.coverUrl(song.coverArt, 120), 50.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing) { Ico("bars", 14.dp, Tint.accent); Spacer(Modifier.width(6.dp)) }
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
                    color = if (playing) Tint.light else Color.White)
            }
            Text(extra ?: song.artist, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (why != null) Text(why, color = Tint.light, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) IconBtn(onRemove, 40.dp) { Text("✕", color = Dim) }
        IconBtn({ vm.menuSong = song }, 40.dp) { Icon(Icons.Default.MoreVert, null, tint = Dim) }
    }
}

@Composable
fun MediaCard(
    title: String, subtitle: String, onClick: () -> Unit, width: Dp = 150.dp, round: Boolean = false,
    onLong: (() -> Unit)? = null, art: @Composable () -> Unit
) {
    Column(
        Modifier.width(width).clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(6.dp),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start
    ) {
        art()
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp), textAlign = if (round) TextAlign.Center else TextAlign.Start)
        if (subtitle.isNotEmpty()) Text(subtitle, color = Dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp,
            textAlign = if (round) TextAlign.Center else TextAlign.Start)
    }
}

@Composable
fun SongCard(vm: AppVm, song: Song, list: List<Song>, index: Int, subtitle: String = song.artist) {
    val api = vm.api ?: return
    MediaCard(song.title, subtitle, { vm.play(list, index) }, onLong = { vm.menuSong = song }) {
        Cover(api.coverUrl(song.coverArt, 300), 138.dp)
    }
}

@Composable
fun MixCard(vm: AppVm, mix: Mix, index: Int) {
    val api = vm.api ?: return
    MediaCard(mix.subtitle, "", { vm.go(Screen.MixS(index)) }) {
        Box(Modifier.size(138.dp).clip(RoundedCornerShape(6.dp))) {
            Collage(mix.songs.take(8).map { api.coverUrl(it.coverArt, 200) }, 138.dp, RoundedCornerShape(0.dp))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
            Box(Modifier.align(Alignment.TopStart).padding(8.dp).size(10.dp).clip(CircleShape).background(Tint.accent))
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(26.dp).background(Color(mix.color)))
                Text(mix.title, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color.White,
                    modifier = Modifier.background(Color(mix.color).copy(alpha = 0.85f)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
fun QuickTile(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, art: @Composable () -> Unit) {
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(6.dp)).background(Card2).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        art()
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 10.dp))
    }
}

@Composable
fun AlbumRow(vm: AppVm, a: Album) {
    val api = vm.api ?: return
    Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.AlbumS(a.id)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(api.coverUrl(a.coverArt, 160), 56.dp)
        Column(Modifier.padding(start = 12.dp)) {
            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull("Album", a.artist.ifEmpty { null }, a.year?.toString()).joinToString(" • "), color = Dim, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
fun ArtistRow(vm: AppVm, a: Artist) {
    val api = vm.api ?: return
    Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.ArtistS(a.id)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(api.coverUrl(a.coverArt, 120), 56.dp, CircleShape)
        Column(Modifier.padding(start = 12.dp)) {
            Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Artist", color = Dim, fontSize = 13.sp)
        }
    }
}

@Composable
fun PlaylistRow(vm: AppVm, p: Playlist) {
    val api = vm.api ?: return
    val mine = p.owner.isEmpty() || p.owner.equals(api.user, true)
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { vm.go(Screen.PlaylistS(p.id)) }, onLongClick = { vm.playlistMenu = p })
        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(vm.plCover(p, 160), 56.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull("Playlist", if (mine) null else "by ${vm.nameOf(p.owner.lowercase())}", "${p.songCount} songs").joinToString(" • "),
                color = Dim, fontSize = 13.sp, maxLines = 1)
        }
        if (mine && p.public) Text("Shared", color = Tint.light, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Card2).padding(horizontal = 8.dp, vertical = 3.dp))
        IconBtn({ vm.playlistMenu = p }, 40.dp) { Icon(Icons.Default.MoreVert, null, tint = Dim) }
    }
}
