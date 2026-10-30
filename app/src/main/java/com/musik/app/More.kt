package com.musik.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import coil.imageLoader
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/* ======================= Queue ======================= */

@Composable
fun QueueScreen(vm: AppVm) {
    val api = vm.api ?: return
    val items = vm.queue
    val cur = vm.currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val rowH = 64.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    var saveName by remember { mutableStateOf<String?>(null) }
    val upcoming = items.size - cur - 1
    Surface(Modifier.fillMaxSize(), color = BgSolid) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar("Queue", { vm.showQueue = false }) {
                IconBtn({ vm.showQueue = false; vm.showFull = false; vm.go(Screen.History) }) { Icon(Icons.Default.DateRange, "History") }
                IconBtn({ saveName = "My queue" }) { Icon(Icons.Default.Add, "Save as playlist") }
                IconBtn({ vm.clearUpcoming() }) { Icon(Icons.Default.Delete, "Clear upcoming") }
            }
            var allBefore by remember { mutableStateOf(false) }
            val before = items.take(cur)
            val shownBefore = if (allBefore) before else before.takeLast(15)
            val beforeOffset = cur - shownBefore.size
            // opens at "Now playing" – scroll up to see what played before
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (before.isEmpty()) 0 else shownBefore.size + 1 + (if (before.size > shownBefore.size) 1 else 0))
            LazyColumn(Modifier.weight(1f), state = listState) {
                if (before.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Played before • ${before.size}", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Text(if (vm.isJamGuest) "The host can go back" else "Tap to go back", color = Dim2, fontSize = 11.sp)
                        }
                    }
                    if (before.size > shownBefore.size) item {
                        Text("Show all ${before.size}", color = Tint.light, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                            modifier = Modifier.clickable { allBefore = true }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    itemsIndexed(shownBefore, key = { i, q -> "b${beforeOffset + i}:${q.mediaId}" }) { i, q ->
                        Box(Modifier.graphicsLayer { alpha = 0.65f }) { QueueRow(vm, api, q, playing = false, onClick = { vm.jumpTo(beforeOffset + i) }, handle = null, onRemove = null) }
                    }
                }
                if (items.isNotEmpty()) {
                    item { SmallTitle("Now playing") }
                    item { QueueRow(vm, api, items[cur], playing = true, onClick = {}, handle = null, onRemove = null) }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (upcoming > 0) "Next up • $upcoming songs" else "Nothing up next", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (upcoming > 1) Text("Hold and drag ≡ to reorder", color = Dim2, fontSize = 11.sp)
                    }
                }
                itemsIndexed(items.drop(cur + 1), key = { i, q -> "${cur + 1 + i}:${q.mediaId}" }) { i, q ->
                    val idx = cur + 1 + i
                    val isDrag = dragging == idx
                    val drag = Modifier.pointerInput(idx, items.size) {
                        detectDragGestures(
                            onDragStart = { dragging = idx; dy = 0f },
                            onDrag = { ch, amount -> ch.consume(); dy += amount.y },
                            onDragEnd = { val to = (idx + (dy / rowPx).roundToInt()).coerceIn(cur + 1, items.size - 1); vm.moveQueue(idx, to); dragging = null; dy = 0f },
                            onDragCancel = { dragging = null; dy = 0f })
                    }
                    Box(Modifier.zIndex(if (isDrag) 1f else 0f).graphicsLayer { translationY = if (isDrag) dy else 0f; shadowElevation = if (isDrag) 16f else 0f }
                        .background(if (isDrag) Card2 else Color.Transparent)
                        .pointerInput(idx, items.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = idx; dy = 0f },
                                onDrag = { ch, amount -> ch.consume(); dy += amount.y },
                                onDragEnd = { val to = (idx + (dy / rowPx).roundToInt()).coerceIn(cur + 1, items.size - 1); vm.moveQueue(idx, to); dragging = null; dy = 0f },
                                onDragCancel = { dragging = null; dy = 0f })
                        }) {
                        QueueRow(vm, api, q, playing = false, onClick = { vm.jumpTo(idx) }, handle = drag, onRemove = { vm.removeQueue(idx) })
                    }
                }
                item {
                    Column(Modifier.padding(16.dp)) {
                        Button({ vm.continueSimilar() }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = Tint.accent)) { Text("Continue with similar music", fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Autoplay", fontWeight = FontWeight.SemiBold)
                                Text("Play similar music when the queue ends", color = Dim, fontSize = 12.sp)
                            }
                            Switch(vm.opt.autoplaySimilar, { vm.opt.autoplaySimilar = it }, colors = SwitchDefaults.colors(checkedTrackColor = Tint.accent))
                        }
                    }
                }
            }
        }
    }
    saveName?.let { n ->
        RenameDialog("Save queue as playlist", n, { saveName = null }) { vm.saveQueueAsPlaylist(it); saveName = null }
    }
}

@Composable
private fun QueueRow(vm: AppVm, api: Api, q: androidx.media3.common.MediaItem, playing: Boolean, onClick: () -> Unit, handle: Modifier?, onRemove: (() -> Unit)?) {
    val s = q.toSong()
    Row(Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(api.coverUrl(s.coverArt, 120), 46.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing) Tint.light else Color.White, fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal)
            Text((if (q.mediaMetadata.extras?.getBoolean("smart") == true) "✦ Smart shuffle • " else "") + s.artist, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) IconBtn(onRemove, 40.dp) { Icon(Icons.Default.Close, "Remove", tint = Dim, modifier = Modifier.size(20.dp)) }
        if (handle != null) Box(handle.size(44.dp), Alignment.Center) { Icon(Icons.Default.Menu, "Drag to reorder", tint = Dim) }
    }
}

/** "Up next" preview inside the big player. */
@Composable
fun UpNext(vm: AppVm) {
    val api = vm.api ?: return
    val nxt = vm.queue.drop(vm.currentIndex + 1).take(3)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x33000000)).padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Up next", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("Open queue", color = Tint.light, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { vm.showQueue = true }.padding(8.dp))
        }
        if (nxt.isEmpty()) Text(if (vm.opt.autoplaySimilar) "Similar music plays when this song ends" else "Nothing up next", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        nxt.forEachIndexed { i, q ->
            val s = q.toSong()
            Row(Modifier.fillMaxWidth().clickable { vm.jumpTo(vm.currentIndex + 1 + i) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(api.coverUrl(s.coverArt, 120), 40.dp)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(s.artist, color = Dim, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/* ======================= Stats ======================= */

@Composable
fun StatsCard(vm: AppVm) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(Brush.horizontalGradient(listOf(Tint.accent, Color(0xFFDB2777)))).clickable { vm.go(Screen.Stats) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Your stats", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text("Top songs, artists, genres and listening time", color = Color(0xFFF3E8FF), fontSize = 13.sp)
        }
        Text("›", fontSize = 28.sp)
    }
}

private fun JSONObject.arr(k: String): List<JSONObject> = optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

@Composable
fun StatsScreen(vm: AppVm) {
    val api = vm.api ?: return
    LaunchedEffect(Unit) { vm.loadStats() }
    val st = vm.stats
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Tint.accent.copy(alpha = 0.55f), Bg))).statusBarsPadding()) {
                TopBar("Your stats", { vm.back() })
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("week" to "Week", "month" to "Month", "year" to "This year", "all" to "All time").forEach { (k, l) -> Chip(l, vm.statsRange == k) { vm.loadStats(k) } }
                }
            }
        }
        when {
            vm.statsError != null && st == null -> item { EmptyCard("Couldn't load your stats", vm.statsError!!) { Button({ vm.loadStats() }) { Text("Try again") } } }
            st == null -> item { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = Tint.accent) } }
            st.optInt("plays") == 0 -> item { EmptyCard("Nothing here yet", "Play some music – songs count after 30 seconds. Make sure \"Share my listening\" is on in Settings.") }
            else -> {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatBox("${st.optInt("minutes")}", "minutes", Modifier.weight(1f))
                        StatBox("${st.optInt("plays")}", "plays", Modifier.weight(1f))
                        StatBox("${st.optInt("artistsCount")}", "artists", Modifier.weight(1f))
                    }
                }
                item { SmallTitle("Listening time per week") }
                item { Bars(st.arr("perWeek").map { it.optString("label") to it.optInt("minutes") }) }
                item { SmallTitle("Per month") }
                item { Bars(st.arr("perMonth").map { it.optString("label") to it.optInt("minutes") }) }
                val arts = st.arr("topArtists")
                if (arts.isNotEmpty()) {
                    item { SmallTitle("Top artists") }
                    itemsIndexed(arts) { i, a ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", color = Dim, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                            Cover(api.coverUrl(a.optString("coverArt").ifEmpty { null }, 120), 48.dp, CircleShape)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(a.optString("name"), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${a.optInt("count")} plays • ${a.optInt("minutes")} min", color = Dim, fontSize = 12.sp)
                            }
                        }
                    }
                }
                val songs = st.arr("topSongs")
                if (songs.isNotEmpty()) {
                    item { SmallTitle("Most played songs") }
                    val list = songs.mapNotNull { songFromJson(it.optJSONObject("song")) }
                    itemsIndexed(songs) { i, t ->
                        val s = songFromJson(t.optJSONObject("song")) ?: return@itemsIndexed
                        Row(Modifier.fillMaxWidth().clickable { vm.play(list, list.indexOfFirst { it.id == s.id }.coerceAtLeast(0)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", color = Dim, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                            Cover(api.coverUrl(s.coverArt, 120), 48.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.artist, color = Dim, fontSize = 12.sp, maxLines = 1)
                            }
                            Text("${t.optInt("count")}×", color = Tint.light, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                item { SmallTitle("Favourite genres") }
                val g = st.arr("genres")
                item {
                    if (g.isEmpty()) Text("Genres show up here as you listen to songs that have a genre.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(g) { _, x ->
                            Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Card).padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(x.optString("name"), fontWeight = FontWeight.Bold)
                                Text("${x.optInt("percent")}%", color = Tint.light, fontSize = 12.sp)
                            }
                        }
                    }
                }
                val d = st.arr("discoveries")
                if (d.isNotEmpty()) {
                    item { SmallTitle("New discoveries") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(d) { _, a ->
                                Column(Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Cover(api.coverUrl(a.optString("coverArt").ifEmpty { null }, 300), 100.dp, CircleShape)
                                    Text(a.optString("name"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                    Text("${a.optInt("count")} plays", color = Dim, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                item {
                    val year = st.optInt("wrappedYear")
                    Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF1DB954), Color(0xFF7C3AED), Color(0xFFDB2777)))).clickable { vm.go(Screen.WrappedS) }.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Issa Wrapped $year", fontWeight = FontWeight.Black, fontSize = 20.sp)
                            Text(if (st.optBoolean("wrappedReady")) "Your year in music is ready" else "A sneak peek – the full Wrapped arrives in December", color = Color(0xFFF5F5F5), fontSize = 13.sp)
                        }
                        Text("›", fontSize = 28.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun StatBox(big: String, small: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Card).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(big, fontWeight = FontWeight.Black, fontSize = 22.sp)
        Text(small, color = Dim, fontSize = 12.sp)
    }
}

@Composable
private fun Bars(data: List<Pair<String, Int>>) {
    val max = (data.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(130.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        data.forEachIndexed { i, (label, v) ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                if (v > 0) Text("$v", color = Dim, fontSize = 8.sp, maxLines = 1)
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.78f * v / max + 0.02f).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(if (i == data.size - 1) Tint.accent else Tint.accent.copy(alpha = 0.45f)))
                Text(label.take(3), color = Dim2, fontSize = 8.sp, maxLines = 1)
            }
        }
    }
}

/* ======================= Settings ======================= */

@Composable
fun Toggle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(icon, title, sub, { onChange(!value) }) { Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Tint.accent)) }
}

@Composable
fun PercentSlider(title: String, sub: String, value: Int, onChange: (Int) -> Unit, range: IntRange = 0..100) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text(sub, color = Dim, fontSize = 12.sp) }
            Text("$value%", color = Tint.light, fontWeight = FontWeight.Bold)
        }
        Slider(value.toFloat(), { onChange(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Tint.accent, inactiveTrackColor = Card2))
    }
}

@Composable
fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Card, title = { Text(title) }, confirmButton = {},
        text = {
            Column {
                options.forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(k); onDismiss() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == k, { onPick(k); onDismiss() }); Text(label)
                    }
                }
            }
        })
}

@Composable
fun ChangePasswordDialog(vm: AppVm, onDismiss: () -> Unit) {
    var cur by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val pw = PasswordVisualTransformation()
    AlertDialog(onDismissRequest = { if (!vm.pwBusy) onDismiss() }, containerColor = Card, title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cur, { cur = it }, label = { Text("Current password") }, singleLine = true, visualTransformation = pw, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(new1, { new1 = it }, label = { Text("New password") }, singleLine = true, visualTransformation = pw, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(new2, { new2 = it }, label = { Text("New password again") }, singleLine = true, visualTransformation = pw, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text("At least 8 characters. Your other devices will need the new password.", color = Dim, fontSize = 12.sp)
                err?.let { Text(it, color = Bad, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton({
                err = when { new1.length < 8 -> "The new password needs at least 8 characters"; new1 != new2 -> "The new passwords don't match"; else -> null }
                if (err == null) vm.changePassword(cur, new1) { e -> if (e == null) onDismiss() else err = e }
            }, enabled = !vm.pwBusy && cur.isNotEmpty()) { Text(if (vm.pwBusy) "Saving…" else "Change") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !vm.pwBusy) { Text("Cancel", color = Dim) } })
}

/* ======================= Settings (grouped, searchable) ======================= */

@Composable
fun SettingsGroup(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, keywords: String, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val q = LocalSettingsQuery.current
    val searching = q.isNotBlank()
    if (searching && !title.contains(q, true) && !keywords.contains(q, true)) return
    val expanded = open || searching
    Column(Modifier.padding(horizontal = 12.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface1)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = !searching, onClick = onToggle).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Tint.accent.copy(alpha = 0.22f)), Alignment.Center) { Icon(icon, null, tint = Tint.light, modifier = Modifier.size(20.dp)) }
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
            if (!searching) Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = Dim)
        }
        androidx.compose.animation.AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                HorizontalDivider(color = Card2, modifier = Modifier.padding(horizontal = 14.dp))
                CompositionLocalProvider(LocalSettingsQuery provides (if (title.contains(q, true)) "" else q)) { content() }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppVm) {
    val api = vm.api ?: return
    val social = vm.social
    val o = vm.opt
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val openGroups = remember { mutableStateMapOf<String, Boolean>() }
    fun g(name: String) = openGroups[name] == true
    fun t(name: String) { openGroups[name] = !(openGroups[name] ?: false) }
    var editName by remember { mutableStateOf(false) }
    var editServer by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmHistory by remember { mutableStateOf(false) }
    var showWheel by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<String?>(null) }
    var sizes by remember { mutableStateOf(Triple(vm.coverCacheBytes(), vm.downloadBytes(), vm.otherCacheBytes())) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.cropUri = it } }
    val pickPhoto = { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Tint.accent.copy(alpha = 0.5f), Bg))).statusBarsPadding()) {
                TopBar("Settings", { vm.back() })
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(CircleShape).clickable { pickPhoto() }) { Avatar(social?.avatarUrl(social.me, vm.myProfile?.avatarTs ?: 0), vm.displayName(), 64.dp) }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(vm.displayName(), fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@${api.user}", color = Dim)
                    }
                }
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search settings") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Clear") } },
                    shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        item {
            CompositionLocalProvider(LocalSettingsQuery provides query.trim()) {
                SettingsGroup(Icons.Default.Person, "Account & profile", "picture photo name password log out signed", g("acc"), { t("acc") }) {
                    SettingRow(Icons.Default.AccountCircle, "View my music profile", "Background image, colour, top artists and stats", { vm.go(Screen.Profile(api.user)) })
                    SettingRow(Icons.Default.Face, "Profile picture", "Shown to your friends", { pickPhoto() })
                    SettingRow(Icons.Default.Edit, "Display name", vm.displayName(), { editName = true })
                    SettingRow(Icons.Default.Lock, "Change password", "For the app, the web app and the friends server", { pw = true })
                    SettingRow(Icons.Default.Person, "Signed in as ${api.user}", api.base)
                    SettingRow(Icons.Default.ExitToApp, "Log out", null, { confirmLogout = true })
                }
                SettingsGroup(Icons.Default.PlayArrow, "Playback", "autoplay restore queue resume continue device shuffle smart sleep timer downloads headphones", g("play"), { t("play") }) {
                    Toggle(Icons.Default.PlayArrow, "Autoplay similar music", "Keeps playing when the queue ends", o.autoplaySimilar) { o.autoplaySimilar = it }
                    Toggle(Icons.Default.Refresh, "Smart shuffle", "Shuffle mixes in new songs that fit the list", o.smartShuffle) { vm.setSmartShuffle(it) }
                    Toggle(Icons.Default.List, "Restore queue", "Bring back your queue after the app was closed", o.restoreQueue) { o.restoreQueue = it }
                    if (o.restoreQueue) Toggle(Icons.Default.PlayArrow, "Resume playing when the app opens", null, o.resumeOnOpen) { o.resumeOnOpen = it }
                    Toggle(Icons.Default.Share, "Continue from other devices", "Offer to pick up where you left off on your PC or the web", o.offerResume) { o.offerResume = it }
                    Toggle(Icons.Default.Warning, "Pause when headphones disconnect", null, o.noisyPause) { o.noisyPause = it }
                    SettingRow(Icons.Default.Notifications, "Sleep timer", sleepLabel(vm) ?: "Off", { vm.showSleep = true })
                    SettingRow(Icons.Default.KeyboardArrowDown, "Downloads", "${vm.downloaded.size} songs • ${vm.downloadBytes() / (1024 * 1024)} MB", { vm.go(Screen.Downloads) })
                }
                SettingsGroup(Icons.Default.Notifications, "Audio & crossfade", "fade crossfade gapless volume quiet loud limit start buttons even out equalizer bass treble quality wifi mobile data saver", g("vol"), { t("vol") }) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Crossfade", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(if (vm.fadeSec == 0) "Off – songs follow each other without a gap" else "The song that ends fades out while the next one fades in", color = Dim, fontSize = 12.sp)
                            }
                            Text(if (vm.fadeSec == 0) "Off" else "${vm.fadeSec} s", color = Tint.light, fontWeight = FontWeight.Bold)
                        }
                        Slider((if (vm.fadeSec == 0) 2 else vm.fadeSec).toFloat(), { v -> val n = Math.round(v); vm.setFade(if (n < 3) 0 else n) }, valueRange = 2f..12f, steps = 9,
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Tint.accent, inactiveTrackColor = Card2))
                    }
                    PercentSlider("App volume", "Fine control on top of the phone volume – for very quiet listening", o.volApp, { o.volApp = minOf(it, o.volMax) })
                    PercentSlider("Volume limit", "The app never plays louder than this", o.volMax, { o.volMax = it; if (o.volApp > it) o.volApp = it }, 5..100)
                    Toggle(Icons.Default.KeyboardArrowDown, "Extra-quiet volume buttons", "At the phone's lowest step, the volume buttons keep going quieter (while the app is open)", o.fineVolumeKeys) { o.fineVolumeKeys = it }
                    SettingRow(Icons.Default.PlayArrow, "Volume when the app starts", if (o.volStart < 0) "Same as last time" else "${o.volStart}%", { pick = "volstart" })
                    Toggle(Icons.Default.Build, "Even out volume", "Loud and quiet songs end up closer in volume", o.leveling) { o.leveling = it }
                    SettingRow(Icons.Default.Build, "Equalizer, bass & treble", if (vm.eqOn) "On" else "Off", { vm.go(Screen.Equalizer) })
                    SettingRow(Icons.Default.Star, "Quality on Wi-Fi", QUALITIES.firstOrNull { it.first == vm.quality }?.second ?: "Original", { pick = "qwifi" })
                    SettingRow(Icons.Default.Phone, "Quality on mobile data", if (o.qualityMobile < 0) "Same as Wi-Fi" else QUALITIES.firstOrNull { it.first == o.qualityMobile }?.second ?: "", { pick = "qmobile" })
                    Toggle(Icons.Default.Warning, "Data saver", "Low quality, small covers – for slow networks", o.dataSaver) { o.dataSaver = it }
                }
                SettingsGroup(Icons.Default.Create, "Appearance & theme", "volume slider accent colour color player layout background mini animation transitions theme style minimal neon amoled black glass dynamic album cover covers resolution", g("look"), { t("look") }) {
                    SettingRow(Icons.Default.Star, "Theme style", THEME_STYLES.toMap()[o.themeStyle] ?: "Default", { pick = "theme" })
                    Toggle(Icons.Default.Face, "Colours from the album cover", "The accent colour follows the song that is playing", o.dynamicAccent) { o.dynamicAccent = it }
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text("Accent colour", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ACCENTS.forEachIndexed { i, (_, c) ->
                                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                                    .then(if (c.toInt() == vm.accentColor) Modifier.border(3.dp, Color.White, CircleShape) else Modifier).clickable { vm.chooseAccent(i) })
                            }
                            Box(Modifier.size(34.dp).clip(CircleShape)
                                .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                                .then(if (ACCENTS.none { it.second.toInt() == vm.accentColor }) Modifier.border(3.dp, Color.White, CircleShape) else Modifier).clickable { showWheel = !showWheel })
                        }
                        if (showWheel) Box(Modifier.padding(top = 16.dp)) { ColorWheel(Color(vm.accentColor)) { vm.pickAccentColor(it.toArgb()) } }
                    }
                    SettingRow(Icons.Default.AccountBox, "Player layout", mapOf("classic" to "Classic", "big" to "Big cover", "minimal" to "Minimal")[o.playerLayout] ?: "Classic", { pick = "layout" })
                    SettingRow(Icons.Default.Create, "Player background", mapOf("color" to "Cover colour", "blur" to "Blurred cover", "black" to "Plain black")[o.playerBg] ?: "", { pick = "bg" })
                    SettingRow(Icons.Default.KeyboardArrowDown, "Mini player", if (o.miniStyle == "dark") "Dark" else "Cover colour", { pick = "mini" })
                    Toggle(Icons.Default.Settings, "Volume slider in the player", "Show a volume control under the play buttons", o.playerVolume) { o.playerVolume = it }
                    Toggle(Icons.Default.Face, "High-resolution covers", "Sharper covers in the big player", o.hiresCovers) { o.hiresCovers = it }
                    Toggle(Icons.Default.Refresh, "Album animation", "The cover shrinks a little when paused", o.coverAnim) { o.coverAnim = it }
                    Toggle(Icons.Default.ArrowForward, "Animations & transitions", "Turn off for a snappier feel", o.transitions) { o.transitions = it }
                }
                SettingsGroup(Icons.Default.Search, "Search & recommendations", "search personal lyrics line recent hidden not interested recommendations", g("search"), { t("search") }) {
                    Toggle(Icons.Default.Favorite, "Personal search results", "Artists and songs you listen to and like come first when they match", o.searchPersonal) { o.searchPersonal = it }
                    Toggle(Icons.Default.Search, "Find songs by their lyrics", "Type a line from a song to find it (looked up through your Issa server)", o.searchLyrics) { o.searchLyrics = it }
                    SettingRow(Icons.Default.Close, "Hidden from recommendations", "${vm.hiddenSongs.size + vm.hiddenArtists.size} songs and artists you're not interested in", { pick = "hidden" })
                    SettingRow(Icons.Default.Delete, "Clear recent searches", if (vm.recentSearches.isEmpty()) "Nothing saved" else "${vm.recentSearches.size} saved on this phone", { vm.clearSearches() })
                }
                SettingsGroup(Icons.Default.Home, "Library & personalization", "home time of day mix right now made for you discover recently favourites friends your week stats share listening", g("home"), { t("home") }) {
                    Toggle(Icons.Default.DateRange, "Time-of-day mix", "Fairouz in the morning, Abdel Halim in the afternoon, Umm Kulthum in the evening", o.homeTime) { o.homeTime = it }
                    Toggle(Icons.Default.DateRange, "Mix for right now", "Built from what you usually play at this time and day", o.homeMoment) { o.homeMoment = it }
                    Toggle(Icons.Default.Star, "Made for you mixes", "On Home", o.homeMixes) { o.homeMixes = it }
                    Toggle(Icons.Default.Search, "Discover something new", "On Home", o.homeDiscover) { o.homeDiscover = it }
                    Toggle(Icons.Default.Refresh, "Recently played", "On Home", o.homeRecent) { o.homeRecent = it }
                    Toggle(Icons.Default.Favorite, "Your favourites", "On Home", o.homeFavs) { o.homeFavs = it }
                    Toggle(Icons.Default.DateRange, "Your week in music", "The weekly card – still in Your Library", o.homeWeekly) { o.homeWeekly = it }
                    Toggle(Icons.Default.Star, "Your stats", "The stats card – still in Your Library", o.homeStats) { o.homeStats = it }
                    Toggle(Icons.Default.Person, "Friends on Home", "Top 10s, friend activity and shared playlists", o.homeFriends) { o.homeFriends = it }
                    Toggle(Icons.Default.Share, "Share my listening", "Friends see what you play and your weekly Top 10", vm.shareActivity) { vm.setShare(it) }
                    SettingRow(Icons.Default.Refresh, "Clear listening history", "Recently played and recent searches on this phone", { confirmHistory = true })
                }
                SettingsGroup(Icons.Default.Settings, "App preferences", "notifications songs friends jam updates background car bmw idrive aaidrive bluetooth dashboard friends server storage cache troubleshooting network version", g("app"), { t("app") }) {
                    Text("Notifications", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, top = 8.dp))
                    Toggle(Icons.Default.Send, "Songs from friends", null, o.notifRecs) { o.notifRecs = it }
                    Toggle(Icons.Default.Notifications, "Jam invitations", null, o.notifJam) { o.notifJam = it }
                    Toggle(Icons.Default.Refresh, "New app versions", "The update banner and \"What's new\"", o.notifUpdates) { o.notifUpdates = it }
                    SettingRow(Icons.Default.Info, "Check in the background", mapOf("normal" to "Every 2 minutes", "saver" to "Every 10 minutes (saves battery)", "off" to "Off – only when the app is open")[o.bgRefresh] ?: "", { pick = "bg_refresh" })
                    Text("Car", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, top = 12.dp))
                    Toggle(Icons.Default.PlayArrow, "Show next song in the car", "iDrive, AAIdrive and Bluetooth show \"Next: song\" under the artist", vm.carNext) { vm.chooseCarNext(it) }
                    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) vm.toast = "Allow \"Nearby devices\" so the app notices when you get in the car" }
                    Toggle(Icons.Default.PlayArrow, "Start music when I get in the car", "Continues where you left off when the phone connects to the car's Bluetooth", vm.carAutoplay) {
                        vm.chooseCarAutoplay(it)
                        if (it && android.os.Build.VERSION.SDK_INT >= 31) btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    Toggle(Icons.Default.Edit, "Short song names in the car", "Hides (feat. …), [Remastered] and similar so names fit instead of jumping", vm.carShort) { vm.chooseCarShort(it) }
                    Toggle(Icons.Default.List, "Show the whole queue on the dashboard", "Off: the dashboard only shows ‹ Back, the song and Next ›. Reconnect the phone after changing.", vm.carQueue) { vm.chooseCarQueue(it) }
                    Text("Server & storage", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, top = 12.dp))
                    SettingRow(Icons.Default.Home, "Friends server", vm.socialBase(), { editServer = true }) {
                        Text(if (vm.socialError == null) "Connected" else "Offline", color = if (vm.socialError == null) Live else Bad, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    SettingRow(Icons.Default.Info, "Storage used", "Covers ${mb(sizes.first)} • Downloads ${mb(sizes.second)} • Other ${mb(sizes.third)}")
                    SettingRow(Icons.Default.Settings, "Cover cache size", "${o.cacheMb} MB – the oldest covers are removed automatically", { pick = "cache" })
                    SettingRow(Icons.Default.Delete, "Clear cover cache", "Covers download again when needed", {
                        ctx.imageLoader.memoryCache?.clear(); ctx.imageLoader.diskCache?.clear(); vm.toast = "Cover cache cleared"
                        sizes = Triple(vm.coverCacheBytes(), vm.downloadBytes(), vm.otherCacheBytes())
                    })
                    SettingRow(Icons.Default.Delete, "Clear temporary files", null, { vm.clearTempFiles(); sizes = Triple(vm.coverCacheBytes(), vm.downloadBytes(), vm.otherCacheBytes()) })
                    Text("Help", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, top = 12.dp))
                    SettingRow(Icons.Default.Refresh, "App updates", vm.update?.let { "Version ${it.versionName} is ready to install" } ?: "You're on ${BuildConfig.VERSION_NAME}",
                        { if (vm.update != null) vm.installUpdate(ctx) else vm.checkUpdate(force = true) }) {
                        Text(if (vm.update != null) "Install" else "Check", color = Tint.light, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    SettingRow(Icons.Default.Build, "Network status & troubleshooting", "Server connection, speed and error info", { vm.go(Screen.Diag) })
                }
            }
            Text("Issa Music ${BuildConfig.VERSION_NAME}", color = Dim2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 32.dp))
        }
    }
    if (pw) ChangePasswordDialog(vm) { pw = false }
    if (editName) {
        var name by remember { mutableStateOf(vm.displayName()) }
        AlertDialog(onDismissRequest = { editName = false }, containerColor = Card, title = { Text("Display name") },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true) },
            confirmButton = { TextButton({ if (name.isNotBlank()) vm.setDisplayName(name.trim()); editName = false }) { Text("Save") } },
            dismissButton = { TextButton({ editName = false }) { Text("Cancel", color = Dim) } })
    }
    if (editServer) {
        var url by remember { mutableStateOf(vm.socialBase()) }
        AlertDialog(onDismissRequest = { editServer = false }, containerColor = Card, title = { Text("Friends server") },
            text = { Column { Text("Usually your server address with port 5280.", color = Dim, fontSize = 13.sp); OutlinedTextField(url, { url = it }, singleLine = true) } },
            confirmButton = { TextButton({ vm.setSocialBase(url); editServer = false }) { Text("Save") } },
            dismissButton = { TextButton({ editServer = false }) { Text("Cancel", color = Dim) } })
    }
    if (confirmHistory) AlertDialog(onDismissRequest = { confirmHistory = false }, containerColor = Card, title = { Text("Clear listening history?") },
        text = { Text("Recently played and recent searches on this phone are removed. Likes and playlists are kept.") },
        confirmButton = { TextButton({ vm.clearHistory(); confirmHistory = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirmHistory = false }) { Text("Cancel", color = Dim) } })
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, containerColor = Card, title = { Text("Log out?") },
        confirmButton = { TextButton({ confirmLogout = false; vm.logout() }) { Text("Log out") } },
        dismissButton = { TextButton({ confirmLogout = false }) { Text("Cancel", color = Dim) } })
    when (pick) {
        "volstart" -> ChoiceDialog("Volume when the app starts", listOf(-1 to "Same as last time", 5 to "5%", 10 to "10%", 25 to "25%", 50 to "50%", 75 to "75%", 100 to "100%"), o.volStart, { o.volStart = it }) { pick = null }
        "qwifi" -> ChoiceDialog("Quality on Wi-Fi", QUALITIES, vm.quality, { vm.chooseQuality(it) }) { pick = null }
        "qmobile" -> ChoiceDialog("Quality on mobile data", listOf(-1 to "Same as Wi-Fi") + QUALITIES, o.qualityMobile, { o.qualityMobile = it; vm.toast = "Applies from the next song" }) { pick = null }
        "layout" -> ChoiceDialog("Player layout", listOf("classic" to "Classic", "big" to "Big cover", "minimal" to "Minimal"), o.playerLayout, { o.playerLayout = it }) { pick = null }
        "bg" -> ChoiceDialog("Player background", listOf("color" to "Cover colour", "blur" to "Blurred cover", "black" to "Plain black"), o.playerBg, { o.playerBg = it }) { pick = null }
        "hidden" -> AlertDialog(onDismissRequest = { pick = null }, containerColor = Card, title = { Text("Hidden from recommendations") },
            text = {
                val list = vm.hiddenList()
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (list.isEmpty()) Text("Nothing hidden. Tap ⋮ on a song and choose \"Not interested\".", color = Dim)
                    list.forEach { (k, label, artist) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (artist) "Artist" else "Song", color = Dim, fontSize = 12.sp) }
                            TextButton({ vm.unhide(k, artist) }) { Text("Show again") }
                        }
                    }
                }
            }, confirmButton = { TextButton({ pick = null }) { Text("Done") } })
        "theme" -> ChoiceDialog("Theme style", THEME_STYLES, o.themeStyle, { o.themeStyle = it; Th.apply(it) }) { pick = null }
        "mini" -> ChoiceDialog("Mini player", listOf("color" to "Cover colour", "dark" to "Dark"), o.miniStyle, { o.miniStyle = it }) { pick = null }
        "bg_refresh" -> ChoiceDialog("Check for news in the background", listOf("normal" to "Every 2 minutes", "saver" to "Every 10 minutes", "off" to "Off"), o.bgRefresh, { o.bgRefresh = it }) { pick = null }
        "cache" -> ChoiceDialog("Cover cache size", listOf(100 to "100 MB", 300 to "300 MB", 1000 to "1 GB"), o.cacheMb, { o.cacheMb = it; vm.toast = "Applies after the app restarts" }) { pick = null }
    }
}

/* ======================= "Continue from …", "Right now" mix, quick volume ======================= */

@Composable
fun ResumeBanner(vm: AppVm) {
    val j = vm.resumeOffer ?: return
    val st = j.optJSONObject("state") ?: return
    val q = st.optJSONArray("queue") ?: return
    val s = songFromJson(q.optJSONObject(st.optInt("index").coerceIn(0, (q.length() - 1).coerceAtLeast(0)))) ?: return
    val api = vm.api ?: return
    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Brush.horizontalGradient(listOf(Tint.accent, Color(0xFF6D28D9))))
        .clickable { vm.acceptResume() }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(api.coverUrl(s.coverArt, 120), 42.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text("Continue on this phone?", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("${s.title} • from ${j.optString("device")} • ${fmt(st.optLong("pos"))}", fontSize = 12.sp, color = Color(0xFFEDE9FE), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Play", fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { vm.acceptResume() }.padding(horizontal = 14.dp, vertical = 6.dp), color = Color.Black)
        IconBtn({ vm.dismissResume() }, 36.dp) { Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(18.dp)) }
    }
}

@Composable
fun MomentCard(vm: AppVm, m: Mix) {
    val api = vm.api ?: return
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(Brush.horizontalGradient(listOf(Color(0xFF0EA5E9), Color(0xFF1E3A8A)))).clickable { vm.play(m.songs, 0); vm.showFull = true }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Collage(m.songs.take(4).map { api.coverUrl(it.coverArt, 200) }, 72.dp, RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("RIGHT NOW", fontSize = 10.sp, letterSpacing = 1.sp, color = Color(0xFFBAE6FD), fontWeight = FontWeight.Bold)
            Text(m.title, fontWeight = FontWeight.Black, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.subtitle, color = Color(0xFFE0F2FE), fontSize = 12.sp, maxLines = 1)
        }
        PlayCircle({ vm.play(m.songs, 0) }, size = 44.dp)
    }
}

/** Quick app-volume slider in the big player (handy for loud headphones). */
@Composable
fun VolumeBar(vm: AppVm) {
    val o = vm.opt
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (o.volApp == 0) "🔇" else if (o.volApp < 40) "🔈" else "🔊", fontSize = 15.sp)
        Slider(o.volApp.toFloat(), { o.volApp = minOf(it.roundToInt(), o.volMax) }, valueRange = 0f..100f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x44FFFFFF)))
        Text("${o.volApp}%", color = Color(0xFFCCCCCC), fontSize = 12.sp, modifier = Modifier.width(38.dp))
    }
}


/* ======================= Troubleshooting ======================= */

@Composable
fun DiagScreen(vm: AppVm) {
    var rows by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(tick) { rows = null; rows = vm.diagnostics() }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Column(Modifier.statusBarsPadding()) { TopBar("Troubleshooting", { vm.back() }) { IconBtn({ tick++ }) { Icon(Icons.Default.Refresh, "Test again") } } } }
        val r = rows
        if (r == null) item { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = Tint.accent) } }
        else {
            itemsIndexed(r) { _, (k, v) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(k, color = Dim, fontSize = 12.sp)
                    Text(v, color = if (v.startsWith("Not reachable")) Bad else Color.White, fontSize = 15.sp)
                }
            }
            item {
                OutlinedButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(r.joinToString("\n") { "${it.first}: ${it.second}" })); vm.toast = "Copied" },
                    Modifier.padding(16.dp).fillMaxWidth(), shape = RoundedCornerShape(50)) { Text("Copy all") }
                Text("Nothing secret is included – no passwords.", color = Dim2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

val THEME_STYLES = listOf("default" to "Default", "minimal" to "Minimal", "neon" to "Neon", "amoled" to "AMOLED Black", "glass" to "Glass")

/* ======================= History ======================= */

/** Everything you played, grouped by day, with the time. Tap to play again. */
@Composable
fun HistoryScreen(vm: AppVm) {
    val api = vm.api ?: return
    var log by remember { mutableStateOf(vm.playLog()) }
    var confirm by remember { mutableStateOf(false) }
    val day = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.ENGLISH)
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    fun label(ts: Long): String {
        val c = java.util.Calendar.getInstance(); val today = c.get(java.util.Calendar.DAY_OF_YEAR) to c.get(java.util.Calendar.YEAR)
        c.timeInMillis = ts; val d = c.get(java.util.Calendar.DAY_OF_YEAR) to c.get(java.util.Calendar.YEAR)
        c.timeInMillis = System.currentTimeMillis() - 86_400_000; val y = c.get(java.util.Calendar.DAY_OF_YEAR) to c.get(java.util.Calendar.YEAR)
        return when (d) { today -> "Today"; y -> "Yesterday"; else -> day.format(java.util.Date(ts)) }
    }
    val groups = log.groupBy { label(it.second) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Tint.accent.copy(alpha = 0.45f), Color.Transparent))).statusBarsPadding()) {
                TopBar("History", { vm.back() }) { if (log.isNotEmpty()) IconBtn({ confirm = true }) { Icon(Icons.Default.Delete, "Clear history", tint = Dim) } }
                Text(if (log.isEmpty()) "Songs you play show up here." else "${log.size} plays on this phone", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        if (log.isEmpty()) item { EmptyCard("Nothing yet", "Play some music and it shows up here, with the time you played it.") }
        groups.forEach { (g, plays) ->
            item { SmallTitle(g) }
            itemsIndexed(plays) { i, (song, at) ->
                Row(Modifier.fillMaxWidth().clickable { vm.play(plays.map { it.first }, i) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(time.format(java.util.Date(at)), color = Dim2, fontSize = 12.sp, modifier = Modifier.width(46.dp))
                    Cover(api.coverUrl(song.coverArt, 120), 44.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (vm.current?.mediaId == song.id) Tint.light else Color.White)
                        Text(song.artist, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconBtn({ vm.menuSong = song }) { Icon(Icons.Default.MoreVert, null, tint = Dim) }
                }
            }
        }
        item { Spacer(Modifier.height(140.dp)) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = Card, title = { Text("Clear your history?") },
        text = { Text("Only the list on this phone is cleared – your stats stay.") },
        confirmButton = { TextButton({ vm.clearPlayLog(); log = emptyList(); confirm = false }) { Text("Clear", color = Bad) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel", color = Dim) } })
}
