@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.musik.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/* ================= app update ================= */

@Composable
fun UpdateBanner(vm: AppVm) {
    if (!vm.opt.notifUpdates) return
    val u = vm.update ?: return
    val ctx = LocalContext.current
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(Tint.accent, lerpColor(Tint.accent, Color(0xFFEC4899), 0.6f)))).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Update available: ${u.versionName}", fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text(u.notes.ifBlank { "New features and fixes" } + (if (u.size > 0) " • ${u.size / (1024 * 1024)} MB" else ""), color = Color(0xEEFFFFFF), fontSize = 13.sp, maxLines = 2)
            }
            Button({ vm.installUpdate(ctx) }, enabled = vm.updateProgress == null, shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Text(if (vm.updateProgress != null) "${((vm.updateProgress ?: 0f) * 100).toInt()}%" else "Install", fontWeight = FontWeight.Bold)
            }
        }
        vm.updateProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), color = Color.White, trackColor = Color(0x44FFFFFF)) }
    }
}

/* ================= color wheel ================= */

@Composable
fun ColorWheel(initial: Color, onPick: (Color) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1].coerceAtLeast(0.25f)) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceAtLeast(0.45f)) }
    val color = Color.hsv(hue, sat, value)
    fun pick(o: Offset, w: Float) {
        val r = w / 2; val dx = o.x - r; val dy = o.y - r
        hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360) % 360).toFloat()
        sat = (hypot(dx, dy) / r).coerceIn(0f, 1f)
        onPick(Color.hsv(hue, sat, value))
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        var size by remember { mutableFloatStateOf(1f) }
        Box(Modifier.size(220.dp)) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { pick(it, this.size.width.toFloat()) } }
                    .pointerInput(Unit) { detectDragGestures { ch, _ -> pick(ch.position, this.size.width.toFloat()) } }
            ) {
                size = this.size.width
                val r = min(this.size.width, this.size.height) / 2
                drawCircle(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)), r)
                drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center = center, radius = r), r)
                drawCircle(Color.Black.copy(alpha = 1f - value), r)
                val a = Math.toRadians(hue.toDouble())
                val p = Offset(center.x + (cos(a) * sat * r).toFloat(), center.y + (sin(a) * sat * r).toFloat())
                drawCircle(Color.White, 14.dp.toPx(), p)
                drawCircle(color, 10.dp.toPx(), p)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Brightness", color = Dim, fontSize = 12.sp, modifier = Modifier.width(80.dp))
            Slider(value, { value = it.coerceIn(0.3f, 1f); onPick(Color.hsv(hue, sat, value)) }, valueRange = 0.3f..1f, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = color, inactiveTrackColor = Card2))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(color))
            Text("#%06X".format(0xFFFFFF and color.toArgb()), color = Dim, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/* ================= equalizer ================= */

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun EqualizerScreen(vm: AppVm) {
    val info = PlaybackService.eqInfo
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Tint.accent.copy(alpha = 0.5f), Bg))).statusBarsPadding()) {
                TopBar("Equalizer", { vm.back() }) {
                    Switch(vm.eqOn, { vm.setEqEnabled(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Tint.accent))
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (vm.eqOn) "Equalizer is on" else "Equalizer is off", color = Dim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        if (info == null) item {
            EmptyCard("Equalizer not available yet", "Play a song first, then open the equalizer again. Some phones don't allow apps to use the equalizer.")
        } else {
            item {
                SmallTitle("Presets")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(EQ_PRESETS) { _, (name, levels) -> Chip(name, false) { vm.applyPreset(levels, info) } }
                }
                SmallTitle("Bands")
                Row(Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    (0 until info.bands).forEach { b ->
                        val level = vm.eqLevels.getOrNull(b) ?: 0
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text("${if (level > 0) "+" else ""}${level / 100} dB", fontSize = 11.sp, color = Dim)
                            Box(Modifier.weight(1f).width(56.dp), contentAlignment = Alignment.Center) {
                                Slider(
                                    level.toFloat(), { vm.setEqBand(b, it.toInt(), info.bands) }, valueRange = info.min.toFloat()..info.max.toFloat(),
                                    modifier = Modifier.requiredWidth(200.dp).graphicsLayer { rotationZ = -90f },
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Tint.accent, inactiveTrackColor = Card2)
                                )
                            }
                            val f = info.freqs.getOrNull(b) ?: 0
                            Text(if (f >= 1000) "${f / 1000}k" else "$f", fontSize = 11.sp, color = Dim)
                        }
                    }
                }
            }
        }
        item {
            SmallTitle("Treble")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row { Text("Brightness of vocals and cymbals", modifier = Modifier.weight(1f)); Text("${if (vm.opt.treble > 0) "+" else ""}${vm.opt.treble / 100} dB", color = Tint.light, fontWeight = FontWeight.Bold) }
                Slider(vm.opt.treble.toFloat(), { vm.opt.treble = (it / 100).toInt() * 100 }, valueRange = -1000f..1000f,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Tint.accent, inactiveTrackColor = Card2))
            }
        }
        item {
            SmallTitle("Bass boost")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row { Text("Strength", modifier = Modifier.weight(1f)); Text("${vm.bass / 10}%", color = Tint.light, fontWeight = FontWeight.Bold) }
                Slider(vm.bass.toFloat(), { vm.chooseBass(it.toInt()) }, valueRange = 0f..1000f,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Tint.accent, inactiveTrackColor = Card2))
                Text("Tip: headphones and car speakers handle bass boost best.", color = Dim2, fontSize = 12.sp)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}


/* ================= downloads ================= */

@Composable
fun DownloadsScreen(vm: AppVm) {
    val songs = vm.downloaded
    var confirm by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListHeader(vm, "Downloaded", "${songs.size} songs • ${vm.downloadBytes() / (1024 * 1024)} MB on this phone • plays without internet",
                Color(0xFF1DB954).copy(alpha = 0.45f), songs, {
                    GradientTile(180.dp, listOf(Color(0xFF0D73EC), Color(0xFF22C55E)), RoundedCornerShape(8.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(96.dp))
                    }
                }) { if (songs.isNotEmpty()) TextButton({ confirm = true }) { Text("Remove all", color = Dim) } }
        }
        if (vm.downloadProgress.isNotEmpty()) item {
            Text("Downloading ${vm.downloadProgress.size} song${if (vm.downloadProgress.size > 1) "s" else ""}…", color = Tint.light,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (songs.isEmpty()) item { EmptyCard("Nothing downloaded yet", "Open an album, playlist or Liked Songs and tap the download button, or long-press a song and choose Download.") }
        itemsIndexed(songs) { i, s -> SongRow(vm, s, songs, i, onRemove = { vm.removeDownload(s.id) }) }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = Card, title = { Text("Remove all downloads?") },
        confirmButton = { TextButton({ vm.clearDownloads(); confirm = false }) { Text("Remove") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel", color = Dim) } })
}

@Composable
fun DownloadButton(vm: AppVm, songs: List<Song>) {
    val all = songs.isNotEmpty() && songs.all { vm.isDownloaded(it.id) }
    val busy = songs.any { it.id in vm.downloadProgress }
    IconBtn({ if (!all && !busy) vm.download(songs) else if (all) vm.toast = "Downloaded. Plays without internet." }) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(22.dp), color = Tint.accent, strokeWidth = 2.dp)
            all -> Box(Modifier.size(24.dp).clip(CircleShape).background(Tint.accent), Alignment.Center) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.Black, modifier = Modifier.size(20.dp)) }
            else -> Box(Modifier.size(24.dp).clip(CircleShape).border(2.dp, Dim, CircleShape), Alignment.Center) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Dim, modifier = Modifier.size(20.dp)) }
        }
    }
}

/* ================= time of day mix ================= */

@Composable
fun TimeMixCard(vm: AppVm, m: Mix) {
    val api = vm.api ?: return
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(Color(m.color), Color(m.color).copy(alpha = 0.35f)))).clickable { vm.go(Screen.TimeMix) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Collage(m.songs.take(4).map { api.coverUrl(it.coverArt, 200) }, 84.dp, RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(m.title, fontWeight = FontWeight.Black, fontSize = 19.sp, maxLines = 2)
            Text(m.subtitle, color = Color(0xFFEEEEEE), fontSize = 13.sp, maxLines = 2)
        }
        PlayCircle({ vm.play(m.songs, 0) }, size = 48.dp)
    }
}

@Composable
fun TimeMixScreen(vm: AppVm) {
    val api = vm.api ?: return
    val m = vm.home?.timeMix ?: run { Text("Not available right now.", color = Dim, modifier = Modifier.statusBarsPadding().padding(24.dp)); return }
    LazyColumn(Modifier.fillMaxSize()) {
        item { ListHeader(vm, m.title, m.subtitle, Color(m.color).copy(alpha = 0.8f), m.songs, { Collage(m.songs.take(4).map { api.coverUrl(it.coverArt, 300) }, 220.dp) }) }
        itemsIndexed(m.songs) { i, s -> SongRow(vm, s, m.songs, i) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/* ================= collaborative playlists ================= */

@Composable
fun NewCollabDialog(vm: AppVm, first: Song? = null, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDone, containerColor = Card, title = { Text("New shared playlist") },
        text = {
            Column {
                Text("Everyone you add can add and remove songs.", color = Dim, fontSize = 13.sp)
                OutlinedTextField(name, { name = it.take(60) }, singleLine = true, placeholder = { Text("e.g. Car music") }, modifier = Modifier.padding(vertical = 8.dp))
                if (vm.friends.isEmpty()) Text("No friends yet.", color = Dim2)
                vm.friends.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { if (f.name in picked) picked.remove(f.name) else picked.add(f.name) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(f.name in picked, { if (it) picked.add(f.name) else picked.remove(f.name) })
                        Text(f.display)
                    }
                }
            }
        },
        confirmButton = { TextButton({ if (name.isNotBlank()) { vm.createCollab(name.trim(), picked.toList(), first); onDone() } }) { Text("Create") } },
        dismissButton = { TextButton(onDone) { Text("Cancel", color = Dim) } }
    )
}

@Composable
fun CollabScreen(vm: AppVm, id: Long) {
    val api = vm.api ?: return
    val c = vm.collabs.firstOrNull { it.id == id } ?: run { Text("Playlist not found.", color = Dim, modifier = Modifier.statusBarsPadding().padding(24.dp)); return }
    val songs = c.songs.map { it.song }
    var confirm by remember { mutableStateOf(false) }
    val people = (listOf(c.ownerDisplay) + c.members.map { it.second }).distinct()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListHeader(vm, c.name, "Shared playlist • ${people.joinToString(" & ")} • ${songs.size} songs", Color(0xFFF97316).copy(alpha = 0.6f), songs, {
                Collage(songs.take(4).map { api.coverUrl(it.coverArt, 300) }, 220.dp)
            }) {
                DownloadButton(vm, songs)
                IconBtn({ vm.collabMenu = c }) { Icon(Icons.Default.MoreVert, null, tint = Dim) }
            }
            if (songs.isEmpty()) Text("Empty for now. Long-press any song and choose Add to playlist.", color = Dim, modifier = Modifier.padding(24.dp))
        }
        itemsIndexed(c.songs) { i, cs ->
            SongRow(vm, cs.song, songs, i, extra = "${cs.song.artist} • added by ${vm.nameOf(cs.addedBy)}", onRemove = { vm.collabRemove(c, i) })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = Card, title = { Text("Delete \"${c.name}\"?") },
        text = { Text("It's removed for everyone.") },
        confirmButton = { TextButton({ confirm = false; vm.collabDelete(c) }) { Text("Delete") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel", color = Dim) } })
}

/* ================= Jam ================= */

@Composable
fun JamSheet(vm: AppVm) {
    val social = vm.social
    val picked = remember { mutableStateListOf<String>() }
    ModalBottomSheet(onDismissRequest = { vm.showJam = false }, containerColor = Card) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Jam", fontWeight = FontWeight.Black, fontSize = 24.sp)
            Text("Listen together in sync. Everyone can add songs to the queue.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
            when {
                social == null -> Text("The friends server isn't reachable.", color = Dim)
                vm.inJam -> {
                    val j = vm.jam
                    val cur = j?.queue?.getOrNull(j.index)
                    val tt by rememberInfiniteTransition(label = "jamSheet").animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "t")
                    if (cur != null) Box(Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
                        JamBackdrop(vm.api?.coverUrl(cur.coverArt, 300), tt, Modifier.matchParentSize())
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(vm.api?.coverUrl(cur.coverArt, 200), 72.dp, RoundedCornerShape(12.dp))
                            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                Text("NOW PLAYING IN THE JAM", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = Color(0xCCFFFFFF))
                                Text(cur.title, fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(cur.artist, color = Color(0xDDFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    val people = listOfNotNull(j?.host) + (j?.members ?: emptyList())
                    people.distinct().forEach { n ->
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(social.avatarUrl(n, if (n == social.me) vm.myProfile?.avatarTs ?: 0 else vm.friends.firstOrNull { it.name == n }?.avatarTs ?: 0), vm.nameOf(n), 36.dp)
                            Text(vm.nameOf(n) + if (n == j?.host) "  (host)" else "", modifier = Modifier.padding(start = 12.dp), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if ((j?.members?.isEmpty() == true) && vm.isJamHost) Text("Waiting for friends to join…", color = Dim, modifier = Modifier.padding(vertical = 8.dp))
                    if (vm.isJamGuest) Text("The host controls play and skip. Songs you pick are added to the shared queue.", color = Dim2, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    JamSkipBar(vm)
                    JamQueue(vm)
                    Button({ vm.leaveJam(); vm.showJam = false }, shape = RoundedCornerShape(50), modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Card2)) { Text(if (vm.isJamHost) "End Jam" else "Leave Jam", color = Bad) }
                }
                else -> {
                    vm.jamInvites.forEach { inv ->
                        Button({ vm.joinJam(inv.host); vm.showJam = false }, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Text("Join ${inv.hostDisplay}'s Jam")
                        }
                    }
                    Text("Invite friends", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    if (vm.friends.isEmpty()) Text("No friends yet.", color = Dim)
                    vm.friends.forEach { f ->
                        Row(Modifier.fillMaxWidth().clickable { if (f.name in picked) picked.remove(f.name) else picked.add(f.name) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(f.name in picked, { if (it) picked.add(f.name) else picked.remove(f.name) })
                            Avatar(social.avatarUrl(f.name, f.avatarTs), f.display, 32.dp, ring = if (f.playing) Live else null)
                            Text(f.display, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                    Button({ vm.startJam(picked.toList()); vm.showJam = false }, shape = RoundedCornerShape(50), modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(48.dp)) {
                        Text("Start a Jam", fontWeight = FontWeight.Bold)
                    }
                    Text("Your current queue becomes the Jam queue.", color = Dim2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/* ================= sleep timer ================= */

@Composable
fun SleepSheet(vm: AppVm) {
    ModalBottomSheet(onDismissRequest = { vm.showSleep = false }, containerColor = Card) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text("Sleep timer", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Text("Music fades out and stops.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
            listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                Text("$m minutes", modifier = Modifier.fillMaxWidth().clickable { vm.setSleep(m); vm.showSleep = false }.padding(horizontal = 20.dp, vertical = 14.dp))
            }
            Text("End of this song", modifier = Modifier.fillMaxWidth().clickable { vm.sleepAtEndOfTrack(); vm.showSleep = false }.padding(horizontal = 20.dp, vertical = 14.dp))
            if (vm.sleepUntil > 0 || vm.sleepEndOfTrack)
                Text("Turn off timer", color = Bad, modifier = Modifier.fillMaxWidth().clickable { vm.setSleep(0); vm.showSleep = false }.padding(horizontal = 20.dp, vertical = 14.dp))
        }
    }
}

@Composable
fun sleepLabel(vm: AppVm): String? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(vm.sleepUntil) { while (vm.sleepUntil > 0) { now = System.currentTimeMillis(); delay(1000); vm.syncSleep() } }
    return when {
        vm.sleepEndOfTrack -> "Stops after this song"
        vm.sleepUntil > now -> "Stops in ${fmt(vm.sleepUntil - now)}"
        else -> null
    }
}

/* ================= lyrics ================= */

@Composable
fun LyricsCard(vm: AppVm, s: Song, pos: Long, color: Color) {
    LaunchedEffect(s.id) { vm.loadLyrics(s) }
    val l = vm.lyrics
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(lerpColor(color, Color.Black, 0.25f)).clickable(enabled = l != null) { vm.showLyrics = true }.padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (l != null) Text("Show all", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        when {
            vm.lyricsLoading -> Text("Looking for lyrics…", color = Color(0xFFDDDDDD))
            l == null -> Text("No lyrics found for this song.", color = Color(0xFFDDDDDD))
            l.synced.isNotEmpty() -> {
                val i = l.synced.indexOfLast { it.timeMs <= pos + 300 }.coerceAtLeast(0)
                l.synced.drop(i).take(4).forEachIndexed { k, line ->
                    Text(line.text.ifBlank { "♪" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (k == 0) Color.White else Color(0x99FFFFFF), modifier = Modifier.padding(vertical = 2.dp))
                }
            }
            else -> Text(l.plain!!.lines().take(5).joinToString("\n"), fontSize = 16.sp, color = Color(0xFFEEEEEE))
        }
    }
}

@Composable
fun LyricsOverlay(vm: AppVm, s: Song, pos: Long, color: Color) {
    LaunchedEffect(s.id) { vm.loadLyrics(s) }
    val l = vm.lyrics
    if (l == null) {
        Surface(Modifier.fillMaxSize(), color = lerpColor(color, Color.Black, 0.2f)) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                IconBtn({ vm.showLyrics = false }) { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(30.dp)) }
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    if (vm.lyricsLoading) CircularProgressIndicator(color = Color.White)
                    else Text("No lyrics found for this song.", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }
    val state = rememberLazyListState()
    val idx = if (l.synced.isNotEmpty()) l.synced.indexOfLast { it.timeMs <= pos + 300 }.coerceAtLeast(0) else -1
    LaunchedEffect(idx) { if (idx > 1) state.animateScrollToItem((idx - 2).coerceAtLeast(0)) }
    Surface(Modifier.fillMaxSize(), color = lerpColor(color, Color.Black, 0.2f)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn({ vm.showLyrics = false }) { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, color = Color(0xFFDDDDDD), fontSize = 13.sp, maxLines = 1)
                }
            }
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                if (l.synced.isNotEmpty()) itemsIndexed(l.synced) { i, line ->
                    Text(line.text.ifBlank { "♪" }, fontSize = 26.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp,
                        color = if (i == idx) Color.White else if (i < idx) Color(0x88FFFFFF) else Color(0x55000000),
                        modifier = Modifier.padding(vertical = 8.dp).clickable { vm.seekTo(line.timeMs) })
                } else item { Text(l.plain ?: "", fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp) }
                item { Text("Lyrics from LRCLIB", color = Color(0x88FFFFFF), fontSize = 11.sp, modifier = Modifier.padding(vertical = 32.dp)) }
            }
        }
    }
}

fun lerpColor(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f)

/* ================= reactions ================= */

val REACTIONS = listOf("🔥", "😍", "😂", "🤯", "💯")

@Composable
fun ReactionRow(vm: AppVm, f: Friend) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 10.dp)) {
        REACTIONS.forEach { e ->
            Box(Modifier.size(34.dp).clip(CircleShape).background(Card2).clickable { vm.react(f, e) }, Alignment.Center) { Text(e, fontSize = 16.sp) }
        }
    }
}

/* ================= weekly + wrapped ================= */

@Composable
fun WeeklyCard(vm: AppVm) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF0F766E), Color(0xFF1E3A8A)))).clickable { vm.go(Screen.Weekly) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Your week in music", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text("Minutes, top artists and what you and your friends both played", color = Color(0xFFD1FAE5), fontSize = 13.sp)
        }
        Text("›", fontSize = 30.sp)
    }
}

@Composable
fun WrappedCard(vm: AppVm) {
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFF43F5E), Color(0xFFF59E0B), Color(0xFF8B5CF6)))).clickable { vm.go(Screen.WrappedS) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Your $year Wrapped", fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text("Your year so far, ready to share", color = Color.White, fontSize = 13.sp)
        }
        Text("›", fontSize = 30.sp)
    }
}

@Composable
fun WeeklyScreen(vm: AppVm) {
    val api = vm.api ?: return
    val social = vm.social ?: return
    LaunchedEffect(Unit) { vm.loadWeekly() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF0F766E), Bg))).statusBarsPadding()) {
                TopBar("", { vm.back() })
                Text("Your week", fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp))
                Text("The last 7 days", color = Dim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        if (vm.weeklyLoading && vm.weekly.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = Tint.accent) } }
        val leader = vm.weekly.firstOrNull()?.takeIf { it.minutes > 0 }
        if (leader != null && vm.weekly.size > 1) item {
            EmptyCard("🏆 ${if (leader.name == social.me) "You" else leader.display} listened the most", "${leader.minutes} minutes this week")
        }
        itemsIndexed(vm.weekly) { _, w ->
            val me = w.name == social.me
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(social.avatarUrl(w.name, if (me) vm.myProfile?.avatarTs ?: 0 else vm.friends.firstOrNull { it.name == w.name }?.avatarTs ?: 0), w.display, 44.dp)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(if (me) "You" else w.display, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("${w.minutes} min • ${w.plays} plays", color = Dim, fontSize = 13.sp)
                    }
                }
                if (w.topArtists.isNotEmpty()) {
                    Text("Top artists", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    Text(w.topArtists.take(3).mapIndexed { i, (n, _) -> "${i + 1}. $n" }.joinToString("   "), fontWeight = FontWeight.SemiBold)
                }
                w.topSongs.firstOrNull()?.let { t ->
                    Text("Most played", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    Row(Modifier.clickable { vm.play(listOf(t.song), 0) }.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(api.coverUrl(t.song.coverArt, 120), 44.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(t.song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${t.song.artist} • ${t.count}×", color = Dim, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
                if (!me && w.common.isNotEmpty()) {
                    Text("You both played", color = Tint.light, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    w.common.forEach { s -> Text("• ${s.title} – ${s.artist}", fontSize = 13.sp, modifier = Modifier.clickable { vm.play(w.common, w.common.indexOf(s)) }.padding(vertical = 2.dp)) }
                }
                if (w.plays == 0) Text("Nothing played this week.", color = Dim2, modifier = Modifier.padding(top = 8.dp))
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun WrappedScreen(vm: AppVm) {
    val api = vm.api ?: return
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.loadWrapped() }
    val w = vm.wrapped
    Box(Modifier.fillMaxSize().background(BgSolid)) {
        if (w == null) { CircularProgressIndicator(Modifier.align(Alignment.Center), color = Tint.accent); return@Box }
        val pages = 5
        val pager = rememberPagerState { pages }
        val bgs = listOf(listOf(Color(0xFF8B5CF6), Color(0xFF1E1B4B)), listOf(Color(0xFFF43F5E), Color(0xFF4C0519)), listOf(Color(0xFF10B981), Color(0xFF022C22)),
            listOf(Color(0xFFF59E0B), Color(0xFF451A03)), listOf(Color(0xFF3B82F6), Color(0xFF0C1E4A)))
        HorizontalPager(pager, Modifier.fillMaxSize()) { p ->
            Column(
                Modifier.fillMaxSize().background(Brush.verticalGradient(bgs[p])).statusBarsPadding().padding(28.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start
            ) {
                when (p) {
                    0 -> {
                        Text("${w.year}", fontSize = 80.sp, fontWeight = FontWeight.Black)
                        Text("${w.display}, here's your year on Issa Music.", fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
                        Text("Swipe →", color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 24.dp))
                    }
                    1 -> {
                        Text("You listened for", fontSize = 22.sp)
                        Text("${w.minutes}", fontSize = 84.sp, fontWeight = FontWeight.Black)
                        Text("minutes", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("That's ${w.plays} plays of ${w.uniqueSongs} different songs.", color = Color(0xDDFFFFFF), modifier = Modifier.padding(top = 16.dp))
                    }
                    2 -> {
                        Text("Your top artists", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 16.dp))
                        w.topArtists.forEachIndexed { i, (n, c) ->
                            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", fontSize = 34.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(48.dp))
                                Column { Text(n, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("$c plays", color = Color(0xCCFFFFFF)) }
                            }
                        }
                        if (w.topArtists.isEmpty()) Text("Listen to more music to fill this up.", color = Color(0xCCFFFFFF))
                        Text("${w.uniqueArtists} artists in total", color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 16.dp))
                    }
                    3 -> {
                        Text("Your top songs", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 16.dp))
                        w.topSongs.forEachIndexed { i, t ->
                            Row(Modifier.padding(vertical = 6.dp).clickable { vm.play(w.topSongs.map { it.song }, i) }, verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(40.dp))
                                Cover(api.coverUrl(t.song.coverArt, 120), 52.dp)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(t.song.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${t.song.artist} • ${t.count} plays", color = Color(0xCCFFFFFF), maxLines = 1)
                                }
                            }
                        }
                        if (w.topMonth.isNotEmpty()) Text("Your biggest month: ${w.topMonth}", color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 16.dp))
                    }
                    else -> {
                        Text("Share your year", fontSize = 30.sp, fontWeight = FontWeight.Black)
                        Text("Make a story card for Instagram or Snapchat.", color = Color(0xDDFFFFFF), modifier = Modifier.padding(vertical = 12.dp))
                        Button({ vm.shareWrapped(ctx, w) }, shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            modifier = Modifier.height(52.dp)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Share", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        Row(Modifier.statusBarsPadding().padding(10.dp).align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
            IconBtn({ vm.back() }) { Icon(Icons.Default.Close, null) }
            Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(pages) { i -> Box(Modifier.width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= pager.currentPage) Color.White else Color(0x55FFFFFF))) }
            }
        }
    }
}

/* ================= playlist menus (rename / delete) ================= */

@Composable
fun RenameDialog(title: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Card, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true) },
        confirmButton = { TextButton({ if (name.isNotBlank()) onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Dim) } }
    )
}

@Composable
fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color = Color.White, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (color == Color.White) Dim else color); Text(label, color = color, modifier = Modifier.padding(start = 18.dp), fontSize = 15.sp)
    }
}

@Composable
fun PlaylistMenu(vm: AppVm, pl: Playlist) {
    val api = vm.api ?: return
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.plCoverCrop = pl.id to uri; vm.playlistMenu = null }
    val mine = pl.owner.isEmpty() || pl.owner.equals(api.user, true)
    var rename by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val close = { vm.playlistMenu = null }
    if (!rename && !confirm) ModalBottomSheet(onDismissRequest = { close() }, containerColor = Card) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(vm.plCover(pl, 120), 52.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(pl.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pl.songCount} songs" + if (!mine) " • by ${vm.nameOf(pl.owner.lowercase())}" else "", color = Dim, fontSize = 13.sp)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Card2)
            if (mine) {
                MenuItem(Icons.Default.Edit, "Rename") { rename = true }
                MenuItem(Icons.Default.Face, "Change picture") { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                if (vm.plCovers.containsKey(pl.id)) MenuItem(Icons.Default.Refresh, "Use the automatic picture") { vm.removePlCover(pl.id); close() }
                MenuItem(Icons.Default.Share, if (pl.public) "Make private" else "Share with friends") { vm.setPlaylistPublic(pl, !pl.public) { }; close() }
            }
            MenuItem(Icons.Default.Send, "Send to a friend") { vm.sendPlaylist = pl; close() }
            if (mine) MenuItem(Icons.Default.Delete, "Delete playlist", Bad) { confirm = true }
        }
    }
    if (rename) RenameDialog("Rename playlist", pl.name, { rename = false; close() }) { n -> vm.renamePlaylist(pl, n); rename = false; close() }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false; close() }, containerColor = Card, title = { Text("Delete \"${pl.name}\"?") },
        text = { Text("This can't be undone.") },
        confirmButton = { TextButton({ vm.deletePlaylist(pl); confirm = false; close() }) { Text("Delete", color = Bad) } },
        dismissButton = { TextButton({ confirm = false; close() }) { Text("Cancel", color = Dim) } })
}

@Composable
fun CollabMenu(vm: AppVm, c: Collab) {
    var rename by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val close = { vm.collabMenu = null }
    val owner = c.owner == vm.social?.me
    if (!rename && !confirm) ModalBottomSheet(onDismissRequest = { close() }, containerColor = Card) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text(c.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Text("Shared playlist • ${c.songs.size} songs", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Card2)
            MenuItem(Icons.Default.Edit, "Rename") { rename = true }
            if (owner) MenuItem(Icons.Default.Delete, "Delete for everyone", Bad) { confirm = true }
        }
    }
    if (rename) RenameDialog("Rename playlist", c.name, { rename = false; close() }) { n -> vm.collabRename(c, n); rename = false; close() }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false; close() }, containerColor = Card, title = { Text("Delete \"${c.name}\"?") },
        text = { Text("It's removed for everyone.") },
        confirmButton = { TextButton({ vm.collabDelete(c); confirm = false; close() }) { Text("Delete", color = Bad) } },
        dismissButton = { TextButton({ confirm = false; close() }) { Text("Cancel", color = Dim) } })
}

/* ================= player "More" sheet ================= */

@Composable
fun PlayerMoreSheet(vm: AppVm, s: Song) {
    val ctx = LocalContext.current
    val sleep = sleepLabel(vm)
    val close = { vm.showPlayerMore = false }
    ModalBottomSheet(onDismissRequest = { close() }, containerColor = Card) {
        Column(Modifier.padding(bottom = 28.dp)) {
            @Composable fun row(sym: String, label: String, sub: String? = null, on: Boolean = false, onClick: () -> Unit) {
                Row(Modifier.fillMaxWidth().clickable { close(); onClick() }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Card2), Alignment.Center) {
                        Text(sym, fontSize = 18.sp, color = if (on) Tint.light else Color.White)
                    }
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (sub != null) Text(sub, color = if (on) Tint.light else Dim, fontSize = 12.sp)
                    }
                }
            }
            row("◉", "Jam", if (vm.inJam) (if (vm.isJamHost) "You're hosting" else "Listening with ${vm.jam?.hostDisplay}") else "Listen together with a friend", vm.inJam) { vm.showJam = true }
            row("◎", "Start radio", vm.radioSeed?.let { "Playing $it radio" } ?: "Endless music like this song", vm.radioSeed != null) { vm.startRadio(seed = s) }
            row("✦", "Smart shuffle", if (vm.opt.smartShuffle) "On – songs that fit are mixed in" else "Shuffle with new songs that fit the list", vm.opt.smartShuffle) { vm.setSmartShuffle(!vm.opt.smartShuffle) }
            row("☾", "Sleep timer", sleep ?: "Off", sleep != null) { vm.showSleep = true }
            row("≋", "Equalizer & bass", if (vm.eqOn) "On" else "Off", vm.eqOn) { vm.go(Screen.Equalizer) }
            row("↗", "Share to Instagram / Snapchat") { vm.shareSong(ctx, s) }
            row("➤", "Send to a friend") { vm.sendSong = s }
            row("+", "Add to playlist") { vm.pickerSong = s }
            if (vm.isDownloaded(s.id)) row("✓", "Downloaded", "Plays without internet", true) { }
            else row("↓", "Download", "Save on this phone") { vm.download(listOf(s)) }
            if (s.artistId != null) row("☺", "Go to artist", s.artist) { vm.go(Screen.ArtistS(s.artistId)) }
            if (s.albumId != null) row("◫", "Go to album", s.album) { vm.go(Screen.AlbumS(s.albumId)) }
        }
    }
}

/* ================= what's new ================= */

@Composable
fun WhatsNewDialog(vm: AppVm) {
    if (!vm.opt.notifUpdates) return
    val notes = vm.whatsNew ?: return
    AlertDialog(
        onDismissRequest = { vm.dismissWhatsNew() }, containerColor = Card,
        title = { Column { Text("What's new", fontWeight = FontWeight.Black); Text("Issa Music ${BuildConfig.VERSION_NAME}", color = Tint.light, fontSize = 14.sp) } },
        text = { Text(notes, fontSize = 15.sp) },
        confirmButton = { Button({ vm.dismissWhatsNew() }, shape = RoundedCornerShape(50)) { Text("Nice") } }
    )
}

/** The Jam's shared queue: what's playing now and everything coming up. */
@Composable
fun JamQueue(vm: AppVm) {
    val api = vm.api ?: return
    val j = vm.jam ?: return
    val queue = j.queue
    val now = j.index
    if (queue.isEmpty()) return
    val cur = queue.getOrNull(now)
    val next = queue.drop(now + 1)
    val before = queue.take(now)
    var showBefore by remember { mutableStateOf(false) }
    val host = vm.isJamHost
    if (before.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().clickable { showBefore = !showBefore }.padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Played before • ${before.size}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (showBefore) "Hide" else "Show", color = Tint.light, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (showBefore) {
            if (host) Text("Tap a song to go back to it", color = Dim2, fontSize = 12.sp)
            before.takeLast(30).let { shown -> val off = before.size - shown.size
                shown.forEachIndexed { i, s -> Box(Modifier.graphicsLayer { alpha = 0.65f }.then(if (host) Modifier.clickable { vm.jumpTo(off + i) } else Modifier)) { JamRow(api, s, false) } } }
        }
    }
    if (cur != null) {
        Text("Now playing", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        JamRow(api, cur, true)
    }
    Text(if (next.isEmpty()) "Nothing up next" else "Up next • ${next.size} song${if (next.size > 1) "s" else ""}", fontWeight = FontWeight.Bold,
        color = if (next.isEmpty()) Dim else Color.White, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    next.take(100).forEachIndexed { i, s -> Box(if (host) Modifier.clickable { vm.jumpTo(now + 1 + i) } else Modifier) { JamRow(api, s, false) } }
    if (next.size > 100) Text("+ ${next.size - 100} more", color = Dim2, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun JamRow(api: Api, s: Song, playing: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(api.coverUrl(s.coverArt, 120), 42.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = if (playing) Tint.light else Color.White)
            Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Dim, fontSize = 12.sp)
        }
        if (playing) Text("◉", color = Live)
    }
}

/** "Make a playlist for me": most played, similar music, a mix of both or brand-new finds – and it can keep itself up to date. */
@Composable
fun MakePlaylistSheet(vm: AppVm, onClose: () -> Unit) {
    var kind by remember { mutableStateOf("top") }
    var range by remember { mutableStateOf("week") }
    var size by remember { mutableIntStateOf(50) }
    var auto by remember { mutableStateOf(true) }
    val kinds = listOf(
        Triple("top", "Most played", "The songs you play the most"), Triple("similar", "Similar songs", "Music like your top artists – nothing you already have on top"),
        Triple("mix", "Mix", "Half your favourites, half new music like them"), Triple("new", "New finds", "Only songs you have never played"))
    val grad = when (kind) { "similar" -> listOf(Color(0xFF0EA5E9), Color(0xFF6366F1)); "mix" -> listOf(Color(0xFF10B981), Color(0xFF0EA5E9)); "new" -> listOf(Color(0xFFF59E0B), Color(0xFFE11D48)); else -> listOf(Color(0xFF7C3AED), Color(0xFFDB2777)) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Card) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Make a playlist for me", fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text("Built from what you actually play, with its own picture made from the songs.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
            // describe it in your own words
            var prompt by remember { mutableStateOf("") }
            Text("Describe it", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(prompt, { prompt = it.take(60) }, placeholder = { Text("e.g. chill arabic evening, gym rap…") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { if (prompt.isNotBlank()) { vm.promptPlaylist(prompt); onClose() } }))
                Spacer(Modifier.width(8.dp))
                Button({ vm.promptPlaylist(prompt); onClose() }, enabled = prompt.isNotBlank() && vm.generating == null, shape = RoundedCornerShape(50)) { Text("Make") }
            }
            Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Chill evening", "Gym rap", "Arabic classics", "Late night drive", "Party").forEach { ex -> Chip(ex, false) { prompt = ex } }
            }
            Text("…or build it from what you play", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            kinds.forEach { (k, t, sub) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(if (kind == k) Brush.horizontalGradient(grad) else Brush.horizontalGradient(listOf(Card2, Card2)))
                    .clickable { kind = k }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t, fontWeight = FontWeight.Bold)
                        Text(sub, color = if (kind == k) Color(0xEEFFFFFF) else Dim, fontSize = 12.sp)
                    }
                    if (kind == k) Text("✓", fontWeight = FontWeight.Black)
                }
            }
            if (kind != "new") {
                Text("From", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("week" to "This week", "month" to "This month", "year" to "This year", "all" to "All time").forEach { (r, l) -> Chip(l, range == r) { range = r } } }
            }
            Text("Songs", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(25, 50, 100).forEach { n -> Chip("$n", size == n) { size = n } } }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep it updated", fontWeight = FontWeight.Bold)
                    Text("New songs and a new picture by itself – daily for a week list, weekly for a year list", color = Dim, fontSize = 12.sp)
                }
                Switch(auto, { auto = it }, colors = SwitchDefaults.colors(checkedTrackColor = Tint.accent))
            }
            Button({ vm.generatePlaylist(kind, if (kind == "new") "month" else range, size, auto); onClose() }, enabled = vm.generating == null, shape = RoundedCornerShape(50),
                modifier = Modifier.padding(top = 18.dp).fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = grad[0])) {
                Text(if (vm.generating != null) "Making \"${vm.generating}\"…" else "Make \"${vm.genName(kind, if (kind == "new") "month" else range)}\"", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
