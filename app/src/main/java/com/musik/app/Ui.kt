@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, coil.annotation.ExperimentalCoilApi::class)

package com.musik.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/* ================= root ================= */

@Composable
fun AppRoot(vm: AppVm) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.toast) { vm.toast?.let { snack.showSnackbar(it); vm.toast = null } }
    if (vm.api == null) {
        Box(Modifier.fillMaxSize().background(BgSolid)) { LoginScreen(vm); SnackbarHost(snack, Modifier.align(Alignment.BottomCenter)) }
        return
    }
    LaunchedEffect(vm.social) {
        var last = 0L
        while (true) { // every 30 s while the app is open; in the background as chosen in Settings
            val every = when { vm.appVisible -> 30_000L; vm.opt.bgRefresh == "off" -> Long.MAX_VALUE; vm.opt.bgRefresh == "saver" -> 600_000L; else -> 120_000L }
            if (System.currentTimeMillis() - last >= every) { vm.refreshSocial(); last = System.currentTimeMillis() }
            delay(10_000)
        }
    }
    val activity = LocalContext.current as? android.app.Activity
    // Back closes what's open, then goes to Home – and from Home the app just goes to the background (music keeps playing)
    BackHandler(enabled = true) {
        when {
            vm.bannerCropUri != null -> vm.bannerCropUri = null
            vm.plCoverCrop != null -> vm.plCoverCrop = null
            vm.cropUri != null -> vm.cropUri = null
            vm.showPlayerMore -> vm.showPlayerMore = false
            vm.showLyrics -> vm.showLyrics = false
            vm.showQueue -> vm.showQueue = false
            vm.showFull -> vm.showFull = false
            vm.stack.size > 1 -> vm.back()
            vm.stack.lastOrNull() != Screen.Home -> vm.tab(Screen.Home)
            else -> activity?.moveTaskToBack(true)
        }
    }
    Box(Modifier.fillMaxSize().background(BgSolid)) {
        if (Th.style == "glass") GlassBackdrop(vm)
        Scaffold(
            containerColor = Bg,
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                Column(Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000), Color.Black)))) {
                    ResumeBanner(vm)
                    MiniPlayer(vm)
                    val root = vm.stack.first()
                    NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                        val colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White, selectedTextColor = Color.White, indicatorColor = Color.Transparent,
                            unselectedIconColor = Dim2, unselectedTextColor = Dim2
                        )
                        NavigationBarItem(root is Screen.Home, { vm.tab(Screen.Home) }, { Icon(Icons.Default.Home, null) }, label = { Text("Home", fontSize = 11.sp) }, colors = colors)
                        NavigationBarItem(root is Screen.Search, { vm.tab(Screen.Search) }, { Icon(Icons.Default.Search, null) }, label = { Text("Search", fontSize = 11.sp) }, colors = colors)
                        NavigationBarItem(root is Screen.Library, { vm.tab(Screen.Library) }, {
                            BadgedBox(badge = { if (vm.unseen > 0) Badge(containerColor = Tint.accent) { Text("${vm.unseen}") } }) { Icon(Icons.Default.List, null) }
                        }, label = { Text("Your Library", fontSize = 11.sp) }, colors = colors)
                    }
                }
            }
        ) { pad ->
            val saver = rememberSaveableStateHolder() // keeps scroll positions when you go back or switch tabs
            AnimatedContent(
                targetState = vm.stack.size to vm.stack.last(),
                transitionSpec = {
                    if (!vm.opt.transitions) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
                    val deeper = targetState.first > initialState.first
                    val sameDepth = targetState.first == initialState.first
                    if (sameDepth) fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                    else (fadeIn(tween(220)) + slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { w -> if (deeper) w / 6 else -w / 6 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { w -> if (deeper) -w / 10 else w / 10 })
                },
                label = "screens",
                modifier = Modifier.padding(bottom = pad.calculateBottomPadding()).fillMaxSize()
            ) { (_, scr) ->
              saver.SaveableStateProvider(scr.toString()) {
              Box(Modifier.fillMaxSize().background(Bg)) {
                when (val s = scr) {
                    is Screen.Home -> HomeScreen(vm)
                    is Screen.Search -> SearchScreen(vm)
                    is Screen.Library -> LibraryScreen(vm)
                    is Screen.Liked -> LikedScreen(vm)
                    is Screen.Settings -> SettingsScreen(vm)
                    is Screen.Inbox -> InboxScreen(vm)
                    is Screen.Upload -> UploadScreen(vm)
                    is Screen.MixS -> MixScreen(vm, s.index)
                    is Screen.TopS -> TopScreen(vm, s.user)
                    is Screen.ArtistS -> ArtistScreen(vm, s.id)
                    is Screen.AlbumS -> AlbumScreen(vm, s.id)
                    is Screen.PlaylistS -> PlaylistScreen(vm, s.id)
                    is Screen.Downloads -> DownloadsScreen(vm)
                    is Screen.Weekly -> WeeklyScreen(vm)
                    is Screen.WrappedS -> WrappedScreen(vm)
                    is Screen.Equalizer -> EqualizerScreen(vm)
                    is Screen.TimeMix -> TimeMixScreen(vm)
                    is Screen.CollabS -> CollabScreen(vm, s.id)
                    is Screen.Stats -> StatsScreen(vm)
                    is Screen.Profile -> ProfileScreen(vm, s.user)
                    is Screen.History -> HistoryScreen(vm)
                    is Screen.Diag -> DiagScreen(vm)
                }
              }
              }
            }
        }
        AnimatedVisibility(vm.showFull,
            enter = if (vm.opt.transitions) slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)) else EnterTransition.None,
            exit = if (vm.opt.transitions) slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200)) else ExitTransition.None) { FullPlayer(vm) }
        if (vm.showQueue) QueueScreen(vm)
        if (vm.showJam) JamSheet(vm)
        vm.playlistMenu?.let { PlaylistMenu(vm, it) }
        WhatsNewDialog(vm)
        vm.collabMenu?.let { CollabMenu(vm, it) }
        vm.cropUri?.let { u -> AvatarCropper(u, { vm.cropUri = null }) { vm.uploadAvatar(it) } }
        vm.bannerCropUri?.let { u -> ImageCropper(u, 2.4f, false, 1200, 500, { vm.bannerCropUri = null }) { vm.setBanner(it) } }
        vm.plCoverCrop?.let { (id, u) -> ImageCropper(u, 1f, false, 600, 600, { vm.plCoverCrop = null }) { vm.setPlCover(id, it) } }
        if (vm.showSleep) SleepSheet(vm)
        vm.menuSong?.let { SongMenu(vm, it) }
        vm.pickerSong?.let { PlaylistPicker(vm, it) }
        if (vm.sendSong != null || vm.sendPlaylist != null) SendSheet(vm)
    }
}

/* ================= login ================= */

@Composable
fun LoginScreen(vm: AppVm) {
    var base by remember { mutableStateOf(vm.savedBase()) }
    var user by remember { mutableStateOf(vm.savedUser()) }
    var pass by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Tint.accent.copy(alpha = 0.45f), Bg, Bg))).systemBarsPadding().imePadding().padding(28.dp),
        Arrangement.Center
    ) {
        GradientTile(72.dp, listOf(Tint.accent, Color(0xFFEC4899)), RoundedCornerShape(20.dp)) { Ico("play", 36.dp) }
        Spacer(Modifier.height(18.dp))
        Text("Issa Music", fontSize = 34.sp, fontWeight = FontWeight.Black)
        Text("Millions of songs. Your server.", color = Dim, modifier = Modifier.padding(bottom = 28.dp))
        OutlinedTextField(base, { base = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.login(base, user, pass) }))
        vm.loginError?.let { Text(it, color = Bad, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(22.dp))
        Button({ vm.login(base, user, pass) }, enabled = !vm.loggingIn && user.isNotBlank() && pass.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(50)) {
            Text(if (vm.loggingIn) "Logging in…" else "Log in", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

/* ================= home ================= */

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening"
}

@Composable
fun HomeScreen(vm: AppVm) {
    LaunchedEffect(Unit) { vm.loadHome(); vm.loadMoment() }
    val api = vm.api ?: return
    val social = vm.social
    var filter by remember { mutableStateOf("All") }
    val headerColor = Tint.accent.copy(alpha = 0.35f)
    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to headerColor, 0.25f to Bg))) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AccountButton(vm)
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Music", "Friends").forEach { Chip(it, filter == it) { filter = it } }
                }
                IconBtn({ vm.showJam = true }) { Ico("jam", 24.dp, if (vm.inJam) Live else Color.White) }
                IconBtn({ vm.go(Screen.Inbox) }) {
                    BadgedBox(badge = { if (vm.unseen > 0) Badge(containerColor = Tint.accent) { Text("${vm.unseen}") } }) {
                        Icon(Icons.Default.Notifications, null)
                    }
                }
            }
            // Greeting on two lines so long names fit
            Text(greeting() + ",", fontSize = 16.sp, color = Color(0xFFDDDDDD), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
            Text(vm.displayName(), fontSize = 28.sp, fontWeight = FontWeight.Black, lineHeight = 32.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        item { UpdateBanner(vm) }
        item { JamBanner(vm) }
        val h = vm.home
        if (filter != "Friends") item { QuickGrid(vm) }
        if (filter != "Friends" && vm.opt.homeMoment) vm.moment?.let { m -> item { MomentCard(vm, m) } }
        if (filter != "Friends" && vm.opt.homeTime) h?.timeMix?.let { tm -> item { TimeMixCard(vm, tm) } }
        if (false) vm.reactions.filter { !it.seen }.take(3).forEach { r -> // reactions are turned off
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card)
                    .clickable { vm.markReactionsSeen() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.emoji, fontSize = 26.sp)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("${r.fromDisplay} reacted to ${r.song?.title ?: "your music"}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ago(r.ts), color = Dim, fontSize = 12.sp)
                    }
                    Text("✕", color = Dim)
                }
            }
        }

        // ---------- friends
        if (filter != "Music" && (vm.opt.homeFriends || filter == "Friends")) {
            val obsessions = vm.friends.mapNotNull { f -> vm.tops[f.name]?.firstOrNull()?.takeIf { it.count >= 3 }?.let { f to it } }
            obsessions.forEach { (f, t) -> item { ObsessionCard(vm, f, t) } }
            val topUsers = (listOfNotNull(social?.me) + vm.friends.map { it.name }).filter { (vm.tops[it]?.size ?: 0) > 0 }
            if (topUsers.isNotEmpty()) {
                item { SectionTitle("Top 10 this week", sub = "What everyone has on repeat") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                        itemsIndexed(topUsers) { _, u ->
                            val items = vm.tops[u].orEmpty()
                            val mine = u == social?.me
                            MediaCard(if (mine) "Your Top 10" else "${vm.nameOf(u)}'s Top 10", "This week • ${items.size} songs", { vm.go(Screen.TopS(u)) }) {
                                Box {
                                    Collage(items.map { api.coverUrl(it.song.coverArt, 200) }, 138.dp)
                                    Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                                        Avatar(social?.avatarUrl(u, if (mine) vm.myProfile?.avatarTs ?: 0 else vm.friends.firstOrNull { it.name == u }?.avatarTs ?: 0), vm.nameOf(u), 32.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (vm.social != null && vm.socialError == null) {
                if (vm.opt.homeWeekly) item { WeeklyCard(vm) }
                if (vm.opt.homeStats) item { StatsCard(vm) }
            }
            if (vm.collabs.isNotEmpty()) {
                item { SectionTitle("Playlists together") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                        itemsIndexed(vm.collabs) { _, c ->
                            MediaCard(c.name, "With ${(listOf(c.ownerDisplay) + c.members.map { it.second }).distinct().joinToString(" & ")}", { vm.go(Screen.CollabS(c.id)) }) {
                                Collage(c.songs.take(4).map { api.coverUrl(it.song.coverArt, 200) }, 138.dp)
                            }
                        }
                    }
                }
            }
            if (vm.friends.isNotEmpty()) {
                item { SectionTitle("Friend activity") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(vm.friends) { _, f -> FriendCard(vm, f) }
                    }
                }
            }
            val shared = vm.playlists.filter { it.owner.isNotEmpty() && !it.owner.equals(api.user, true) }
            if (shared.isNotEmpty()) {
                item { SectionTitle("Shared by friends") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                        itemsIndexed(shared) { _, p ->
                            MediaCard(p.name, "By ${vm.nameOf(p.owner.lowercase())} • ${p.songCount} songs", { vm.go(Screen.PlaylistS(p.id)) }) {
                                Cover(vm.plCover(p, 300), 138.dp)
                            }
                        }
                    }
                }
            }
            if (filter == "Friends" && vm.friends.isEmpty()) item {
                EmptyCard("No friends yet", if (vm.socialError != null) "The friends server isn't reachable: ${vm.socialError}" else
                    "Friends show up here once they've signed in to Issa Music with their own account.")
            }
        }

        // ---------- music
        if (filter != "Friends") {
            when {
                vm.homeLoading && h == null -> item { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = Tint.accent) } }
                vm.homeError != null && h == null -> item { EmptyCard("Couldn't load your home", vm.homeError!!) { Button({ vm.loadHome(true) }) { Text("Try again") } } }
                h != null -> {
                    if (h.mixes.isNotEmpty() && vm.opt.homeMixes) {
                        item { SectionTitle(vm.displayName(), sub = "Made for", action = "Refresh") { vm.loadHome(true) } }
                        item { LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) { itemsIndexed(h.mixes) { i, m -> MixCard(vm, m, i) } } }
                    }
                    if (h.noSeeds) item {
                        EmptyCard("Let's get to know your taste", "Search for music and tap ♥ on songs you love. Your mixes and recommendations are built from what you like and play.") {
                            Button({ vm.tab(Screen.Search) }, shape = RoundedCornerShape(50)) { Text("Find music") }
                        }
                    }
                    if (vm.recents.isNotEmpty() && vm.opt.homeRecent) {
                        item { SectionTitle("Recently played") }
                        item { LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) { itemsIndexed(vm.recents.take(15)) { i, s -> SongCard(vm, s, vm.recents, i) } } }
                    }
                    if (h.discover.isNotEmpty() && vm.opt.homeDiscover) {
                        item { SectionTitle("Discover something new", sub = "Based on artists you like") }
                        item { LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) { itemsIndexed(h.discover) { i, s -> SongCard(vm, s, h.discover, i) } } }
                    }
                    if (vm.likedSongs.isNotEmpty() && vm.opt.homeFavs) {
                        item { SectionTitle("Your favourites", action = "Show all") { vm.go(Screen.Liked) } }
                        item { LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) { itemsIndexed(vm.likedSongs.take(15)) { i, s -> SongCard(vm, s, vm.likedSongs, i) } } }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
fun EmptyCard(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(18.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(text, color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        if (action != null) { Spacer(Modifier.height(12.dp)); action() }
    }
}

@Composable
fun QuickGrid(vm: AppVm) {
    val api = vm.api ?: return
    val tiles = mutableListOf<Triple<String, () -> Unit, @Composable () -> Unit>>()
    tiles += Triple("Liked Songs", { vm.go(Screen.Liked) }, { LikedTile(56.dp) })
    (if (vm.opt.homeMixes) vm.home?.mixes?.take(2) else null)?.forEachIndexed { i, m ->
        tiles += Triple(m.title, { vm.go(Screen.MixS(i)) }, { Cover(api.coverUrl(m.songs.firstOrNull()?.coverArt, 120), 56.dp, RoundedCornerShape(0.dp)) })
    }
    vm.playlists.take(3).forEach { p ->
        tiles += Triple(p.name, { vm.go(Screen.PlaylistS(p.id)) }, { Cover(vm.plCover(p, 120), 56.dp, RoundedCornerShape(0.dp)) })
    }
    vm.recents.distinctBy { it.albumId ?: it.id }.take(8).forEach { s ->
        if (tiles.size < 8) tiles += Triple(s.album.ifEmpty { s.title }, { if (s.albumId != null) vm.go(Screen.AlbumS(s.albumId)) else vm.play(listOf(s), 0) },
            { Cover(api.coverUrl(s.coverArt, 120), 56.dp, RoundedCornerShape(0.dp)) })
    }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.take(8).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (t, click, art) -> QuickTile(t, click, Modifier.weight(1f)) { art() } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ObsessionCard(vm: AppVm, f: Friend, t: TopItem) {
    val api = vm.api ?: return
    val social = vm.social ?: return
    val color = rememberCoverColor(api.coverUrl(t.song.coverArt, 120))
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(color, Card))).clickable { vm.play(listOf(t.song), 0) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(api.coverUrl(t.song.coverArt, 200), 72.dp, RoundedCornerShape(6.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(social.avatarUrl(f.name, f.avatarTs), f.display, 20.dp)
                Text("${f.display} can't stop listening to", fontSize = 12.sp, color = Color(0xFFE8E8E8), modifier = Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(t.song.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text("${t.song.artist} • ${t.count} plays this week", color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlayCircle({ vm.play(listOf(t.song), 0) }, size = 44.dp)
    }
}

@Composable
fun FriendCard(vm: AppVm, f: Friend) {
    val social = vm.social ?: return
    val api = vm.api ?: return
    Column(
        Modifier.width(230.dp).clip(RoundedCornerShape(12.dp)).background(Card)
            .clickable(enabled = f.song != null) { f.song?.let { vm.play(listOf(it), 0) } }.padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CircleShape).clickable { vm.go(Screen.Profile(f.name)) }) { Avatar(social.avatarUrl(f.name, f.avatarTs), f.display, 40.dp, ring = if (f.playing) Live else null) }
            Column(Modifier.padding(start = 10.dp).clickable { vm.go(Screen.Profile(f.name)) }) {
                Text(f.display, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when { f.playing -> "Listening now"; f.song != null -> ago(f.npTs); else -> "Nothing played yet" },
                    color = if (f.playing) Live else Dim, fontSize = 12.sp)
            }
        }
        f.song?.let { s ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(api.coverUrl(s.coverArt, 120), 40.dp)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(s.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, fontSize = 12.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Ico("play", 20.dp, Tint.light)
            }
        }
    }
}

/* ================= list pages (mix, top, liked) ================= */

@Composable
fun ListHeader(vm: AppVm, title: String, subtitle: String, color: Color, songs: List<Song>, art: @Composable () -> Unit, extraActions: @Composable RowScope.() -> Unit = {}) {
    val playingThis = vm.isPlaying && songs.any { it.id == vm.current?.mediaId }
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(color, Bg))).statusBarsPadding()) {
        TopBar("", { vm.back() })
        Box(Modifier.fillMaxWidth(), Alignment.Center) { art() }
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp))
        Text(subtitle, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            extraActions()
            Spacer(Modifier.weight(1f))
            IconBtn({ if (songs.isNotEmpty()) vm.play(songs, 0, shuffled = true) }) { Ico("shuffle", 26.dp, if (vm.shuffle) Tint.light else Dim) }
            Spacer(Modifier.width(8.dp))
            PlayCircle({ if (playingThis) vm.toggle() else if (songs.isNotEmpty()) vm.play(songs, 0) }, playingThis)
        }
    }
}

@Composable
fun MixScreen(vm: AppVm, index: Int) {
    val api = vm.api ?: return
    val mix = vm.home?.mixes?.getOrNull(index) ?: run { Text("This mix isn't available any more.", color = Dim, modifier = Modifier.statusBarsPadding().padding(24.dp)); return }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListHeader(vm, mix.title, "${mix.subtitle}. Made for ${vm.displayName()}.", Color(mix.color).copy(alpha = 0.7f), mix.songs, {
                Collage(mix.songs.take(8).map { api.coverUrl(it.coverArt, 300) }, 220.dp)
            })
        }
        itemsIndexed(mix.songs) { i, s -> SongRow(vm, s, mix.songs, i) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun TopScreen(vm: AppVm, user: String) {
    val api = vm.api ?: return
    val items = vm.tops[user].orEmpty()
    val songs = items.map { it.song }
    val mine = user == vm.social?.me
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListHeader(vm, if (mine) "Your Top 10" else "${vm.nameOf(user)}'s Top 10", "Most played songs the last 7 days", Tint.accent.copy(alpha = 0.6f), songs, {
                Collage(songs.map { api.coverUrl(it.coverArt, 300) }, 220.dp)
            })
        }
        if (items.isEmpty()) item { Text("Nothing played this week yet.", color = Dim, modifier = Modifier.padding(24.dp)) }
        itemsIndexed(items) { i, t -> SongRow(vm, t.song, songs, i, number = i + 1, extra = "${t.song.artist} • ${t.count} plays") }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun LikedScreen(vm: AppVm) {
    LaunchedEffect(Unit) { vm.refreshStarred() }
    val songs = vm.likedSongs
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListHeader(vm, "Liked Songs", "${songs.size} songs", Color(0xFF4B2BD6).copy(alpha = 0.8f), songs, { LikedTile(200.dp) }) { DownloadButton(vm, songs) }
        }
        if (songs.isEmpty()) item { Text("Songs you like show up here. Tap ♥ on any song.", color = Dim, modifier = Modifier.padding(24.dp)) }
        itemsIndexed(songs) { i, s -> SongRow(vm, s, songs, i) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/* ================= search ================= */

private val GENRES = listOf(
    "Hip-Hop" to 0xFFBC5900, "Rap" to 0xFF8D67AB, "R&B" to 0xFFDC148C, "Pop" to 0xFF148A08, "Arabic" to 0xFFE8115B,
    "Afrobeats" to 0xFF1E3264, "Trap" to 0xFF503750, "Drill" to 0xFF27856A, "Chill" to 0xFF477D95, "Workout" to 0xFFE91429,
    "Latin" to 0xFFE1118C, "Electronic" to 0xFF0D73EC, "Rock" to 0xFFBA5D07, "Love songs" to 0xFFB02897, "Throwback" to 0xFF7358FF, "Swedish" to 0xFF006450
)

@Composable
fun SearchScreen(vm: AppVm) {
    val focus = LocalFocusManager.current
    LaunchedEffect(vm.query) { delay(350); vm.search() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Search", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp))
        TextField(
            vm.query, { vm.query = it }, singleLine = true,
            placeholder = { Text("What do you want to listen to?", color = Color(0xFF555555)) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Black) },
            trailingIcon = { if (vm.query.isNotEmpty()) IconBtn({ vm.query = "" }) { Icon(Icons.Default.Close, null, tint = Color.Black) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedTextColor = Color.Black, unfocusedTextColor = Color.Black,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Color.Black
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.rememberSearch() })
        )
        val r = vm.result
        when {
            vm.query.trim().length < 2 -> BrowseAll(vm)
            vm.searching && r == null -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = Tint.accent) }
            vm.searchError != null && r == null -> Text(vm.searchError!!, color = Bad, modifier = Modifier.padding(24.dp))
            r == null -> Spacer(Modifier.height(1.dp))
            r.songs.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && vm.lyricHits.isEmpty() ->
                Text(if (vm.searching) "Searching other spellings and lyrics…" else "No results for \"${vm.query}\" – try a line from the song or another spelling", color = Dim, modifier = Modifier.padding(24.dp))
            else -> SearchResults(vm, r)
        }
    }
}

@Composable
fun BrowseAll(vm: AppVm) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (vm.recentSearches.isNotEmpty()) {
            item { SmallTitle("Recent searches") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(vm.recentSearches) { _, q -> Chip(q, false) { vm.query = q } }
                }
            }
        }
        item { SectionTitle("Browse all") }
        GENRES.chunked(2).forEach { row ->
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (g, c) ->
                        Box(Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(8.dp)).background(Color(c)).clickable { vm.query = g }.padding(12.dp)) {
                            Text(g, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun SearchResults(vm: AppVm, r: SearchResult) {
    val api = vm.api ?: return
    LazyColumn(Modifier.fillMaxSize()) {
        if (vm.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp), color = Tint.accent) }
        when (val top = vm.searchTop) {
            is TopHit.ArtistTop -> {
                item { SectionTitle("Top result") }
                item {
                    val a = top.a
                    LaunchedEffect(a.name) { vm.artistImage(a.name) }
                    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Card).clickable { vm.rememberSearch(); vm.go(Screen.ArtistS(a.id)) }.padding(16.dp)) {
                        Cover(api.coverUrl(vm.artistImages[a.name.lowercase()] ?: a.coverArt, 200), 92.dp, CircleShape)
                        Text(a.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                        Text(if (a.id in vm.searchMine) "Artist • you listen to them" else "Artist", color = Dim)
                    }
                }
            }
            is TopHit.SongTop -> {
                item { SectionTitle("Top result") }
                item {
                    val s = top.s
                    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Card)
                        .clickable { vm.rememberSearch(); vm.playFromSearch(s) }.padding(16.dp)) {
                        Cover(api.coverUrl(s.coverArt, 200), 92.dp, RoundedCornerShape(6.dp))
                        Text(s.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                        Text("Song • ${s.artist}", color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        vm.searchWhy[s.id]?.let { Text(it, color = Tint.light, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
            }
            is TopHit.AlbumTop -> {
                item { SectionTitle("Top result") }
                item {
                    val a = top.a
                    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Card).clickable { vm.rememberSearch(); vm.go(Screen.AlbumS(a.id)) }.padding(16.dp)) {
                        Cover(api.coverUrl(a.coverArt, 200), 92.dp, RoundedCornerShape(6.dp))
                        Text(a.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                        Text("Album • ${a.artist}", color = Dim)
                    }
                }
            }
            null -> {}
        }
        val lyricsFirst = vm.searchKind == "lyrics"
        val lyricBlock: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            if (vm.lyricHits.isNotEmpty()) {
                item { SectionTitle("From the lyrics") }
                val hs = vm.lyricHits.map { it.song }
                itemsIndexed(vm.lyricHits) { i, h ->
                    Column(Modifier.clickable { vm.rememberSearch() }) {
                        SongRow(vm, h.song, hs, i, why = h.why, onPlay = { vm.rememberSearch(); vm.playFromSearch(h.song) })
                        Text("“${h.line}”", color = Dim, fontSize = 12.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 76.dp, end = 16.dp, bottom = 6.dp))
                    }
                }
            }
        }
        if (lyricsFirst) lyricBlock()
        if (r.songs.isNotEmpty()) {
            item { SectionTitle("Songs") }
            itemsIndexed(r.songs.take(8)) { i, s -> Box(Modifier.clickable { vm.rememberSearch() }) { SongRow(vm, s, r.songs, i, why = vm.searchWhy[s.id], onPlay = { vm.rememberSearch(); vm.playFromSearch(s) }) } }
        }
        if (!lyricsFirst) lyricBlock()
        if (r.artists.isNotEmpty()) {
            item { SectionTitle("Artists") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                    itemsIndexed(r.artists) { _, a ->
                        LaunchedEffect(a.name) { vm.artistImage(a.name) }
                        MediaCard(a.name, if (a.id in vm.searchMine) "You listen to them" else "Artist", { vm.rememberSearch(); vm.go(Screen.ArtistS(a.id)) }, width = 130.dp, round = true) {
                            Cover(api.coverUrl(vm.artistImages[a.name.lowercase()] ?: a.coverArt, 300), 118.dp, CircleShape)
                        }
                    }
                }
            }
        }
        if (r.albums.isNotEmpty()) {
            item { SectionTitle("Albums") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                    itemsIndexed(r.albums) { _, a ->
                        MediaCard(a.name, listOfNotNull(a.year?.toString(), a.artist).joinToString(" • "), { vm.rememberSearch(); vm.go(Screen.AlbumS(a.id)) }) {
                            Cover(api.coverUrl(a.coverArt, 300), 138.dp)
                        }
                    }
                }
            }
        }
        if (r.songs.size > 8) {
            item { SectionTitle("More songs") }
            itemsIndexed(r.songs.drop(8)) { i, s -> SongRow(vm, s, r.songs, i + 8, why = vm.searchWhy[s.id], onPlay = { vm.rememberSearch(); vm.playFromSearch(s) }) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/* ================= library ================= */

@Composable
fun LibraryScreen(vm: AppVm) {
    LaunchedEffect(Unit) { vm.refreshPlaylists(); vm.refreshStarred() }
    val api = vm.api ?: return
    val social = vm.social
    var filter by remember { mutableStateOf("All") }
    var sortAz by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showCollab by remember { mutableStateOf(false) }
    var showMake by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    val isMine = { p: Playlist -> p.owner.isEmpty() || p.owner.equals(api.user, true) }
    val made = vm.playlists.filter { it.id in vm.genIds }
    val mine = vm.playlists.filter { isMine(it) && it.id !in vm.genIds }
    val shared = vm.playlists.filter { !isMine(it) }
    val sorted = { l: List<Playlist> -> if (sortAz) l.sortedBy { it.name.lowercase() } else l }
    if (showCollab) NewCollabDialog(vm) { showCollab = false }
    if (showMake) MakePlaylistSheet(vm) { showMake = false }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AccountButton(vm)
                Text("Your Library", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp).weight(1f))
                IconBtn({ showAdd = true }) { Icon(Icons.Default.Add, "New", modifier = Modifier.size(28.dp)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Your playlists", "Made for you", "From friends").forEach { Chip(it, filter == it) { filter = it } }
            }
        }
        if (filter == "All") {
            // quick shortcuts – two small tiles per row, like Spotify
            item {
                val tiles: List<Triple<String, String?, Pair<() -> Unit, @Composable () -> Unit>>> = listOf(
                    Triple("Liked Songs", "${vm.likedSongs.size}", ({ vm.go(Screen.Liked) } to @Composable { LikedTile(48.dp) })),
                    Triple("Downloaded", "${vm.downloaded.size}", ({ vm.go(Screen.Downloads) } to @Composable { GradientTile(48.dp, listOf(Color(0xFF065F46), Color(0xFF22C55E))) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(28.dp)) } })),
                    Triple("Sent to you", if (vm.unseen > 0) "${vm.unseen} new" else null, ({ vm.go(Screen.Inbox) } to @Composable { GradientTile(48.dp, listOf(Color(0xFFEC4899), Color(0xFFF97316))) { Icon(Icons.Default.Send, null, tint = Color.White, modifier = Modifier.size(22.dp)) } })),
                    Triple("Your stats", null, ({ vm.go(Screen.Stats) } to @Composable { GradientTile(48.dp, listOf(Tint.accent, Color(0xFFDB2777))) { Icon(Icons.Default.Star, null, tint = Color.White, modifier = Modifier.size(24.dp)) } })),
                    Triple("Your week", null, ({ vm.go(Screen.Weekly) } to @Composable { GradientTile(48.dp, listOf(Color(0xFF0F766E), Color(0xFF1E3A8A))) { Icon(Icons.Default.DateRange, null, tint = Color.White, modifier = Modifier.size(24.dp)) } })),
                    Triple("History", null, ({ vm.go(Screen.History) } to @Composable { GradientTile(48.dp, listOf(Color(0xFF475569), Color(0xFF0EA5E9))) { Icon(Icons.Default.Refresh, null, tint = Color.White, modifier = Modifier.size(24.dp)) } })),
                    Triple("Upload music", null, ({ vm.go(Screen.Upload) } to @Composable { GradientTile(48.dp, listOf(Color(0xFF0D73EC), Color(0xFF22C55E))) { Icon(Icons.Default.AddCircle, null, tint = Color.White, modifier = Modifier.size(24.dp)) } })))
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (t, badge, act) ->
                                Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(8.dp)).background(Card).clickable(onClick = act.first), verticalAlignment = Alignment.CenterVertically) {
                                    act.second()
                                    Text(t, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f))
                                    if (badge != null) Text(badge, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (filter == "All" || filter == "Made for you") item {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF7C3AED), Color(0xFFDB2777)))).clickable(enabled = vm.generating == null) { showMake = true }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(vm.generating?.let { "Making \"$it\"…" } ?: "Make a playlist for me", fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("From what you play – keeps itself up to date", color = Color(0xEEFFFFFF), fontSize = 12.sp)
                }
                if (vm.generating != null) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp) else Text("›", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        // the lists
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(when (filter) { "Made for you" -> "Made for you"; "From friends" -> "From friends"; "Your playlists" -> "Your playlists"; else -> "Playlists" }, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text(if (sortAz) "A–Z" else "Recent", color = Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { sortAz = !sortAz }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        if (filter == "All" || filter == "Made for you") itemsIndexed(sorted(made)) { _, p -> PlaylistRow(vm, p) }
        if (filter == "All" || filter == "Your playlists") itemsIndexed(sorted(mine)) { _, p -> PlaylistRow(vm, p) }
        if (filter == "All" || filter == "From friends") {
            itemsIndexed(vm.collabs) { _, c ->
                LibRow(c.name, "Together with ${(listOf(c.ownerDisplay) + c.members.map { it.second }).distinct().filter { it != vm.displayName() }.joinToString(" & ")} • ${c.songs.size} songs",
                    { vm.go(Screen.CollabS(c.id)) }) { Collage(c.songs.take(4).map { api.coverUrl(it.song.coverArt, 120) }, 56.dp, RoundedCornerShape(4.dp)) }
            }
            itemsIndexed(sorted(shared)) { _, p -> PlaylistRow(vm, p) }
        }
        val empty = when (filter) { "Made for you" -> made.isEmpty(); "Your playlists" -> mine.isEmpty(); "From friends" -> shared.isEmpty() && vm.collabs.isEmpty(); else -> vm.playlists.isEmpty() }
        if (empty) item {
            Text(when (filter) {
                "Made for you" -> "Nothing yet – tap \"Make a playlist for me\" above."
                "From friends" -> "When a friend shares a playlist, or you make one together, it shows up here."
                else -> "Tap + up top to make a playlist, or long-press a song and choose Add to playlist."
            }, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (showAdd) ModalBottomSheet(onDismissRequest = { showAdd = false }, containerColor = Card) {
        Column(Modifier.padding(bottom = 28.dp)) {
            MenuItem(Icons.Default.Add, "New playlist") { showAdd = false; showNew = true }
            MenuItem(Icons.Default.Share, "Playlist together with a friend") { showAdd = false; showCollab = true }
            MenuItem(Icons.Default.Star, "Make a playlist for me") { showAdd = false; showMake = true }
            MenuItem(Icons.Default.AddCircle, "Upload music") { showAdd = false; vm.go(Screen.Upload) }
        }
    }
    if (showNew) NewPlaylistDialog({ showNew = false }) { n -> showNew = false; vm.newPlaylist(n, null) }
}

@Composable
fun LibRow(title: String, sub: String, onClick: () -> Unit, art: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        art()
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Card, title = { Text("Give your playlist a name") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("My playlist") }) },
        confirmButton = { TextButton({ if (name.isNotBlank()) onCreate(name.trim()) }) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Dim) } }
    )
}

/* ================= upload ================= */

@Composable
fun UploadScreen(vm: AppVm) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.uploadFiles(uris) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF0D73EC).copy(alpha = 0.6f), Bg))).statusBarsPadding()) {
                TopBar("", { vm.back() })
                Text("Upload music", fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp))
                Text("Pick songs from your phone. They're added to your server's library in the folder Uploads/${vm.displayName()} so you and your friends can play them everywhere.",
                    color = Dim, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                Button({ picker.launch(arrayOf("audio/*")) }, enabled = !vm.uploading, shape = RoundedCornerShape(50),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).height(50.dp)) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp))
                    Text(if (vm.uploading) "Uploading…" else "Choose audio files", fontWeight = FontWeight.Bold)
                }
                Text("MP3, FLAC, M4A, OGG, OPUS and WAV. Max 400 MB per file. Tags (title, artist, album, cover) are read from the files.",
                    color = Dim2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        if (vm.uploads.isNotEmpty()) item { SmallTitle("This session") }
        itemsIndexed(vm.uploads.toList().reversed()) { _, u ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (u.done) "Done" else if (u.failed) "Failed" else "${(u.progress * 100).toInt()}%",
                        color = if (u.failed) Bad else if (u.done) Live else Dim, fontSize = 13.sp)
                }
                if (!u.done && !u.failed) LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Tint.accent, trackColor = Card2)
                if (u.failed) Text(u.status, color = Bad, fontSize = 12.sp)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/* ================= inbox / send ================= */

@Composable
fun InboxScreen(vm: AppVm) {
    val social = vm.social ?: return
    val api = vm.api ?: return
    LaunchedEffect(Unit) { delay(1500); vm.markInboxSeen() }
    val songs = vm.inbox.filter { !it.isPlaylist }.map { it.song }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListHeader(vm, "From friends", "Songs and playlists your friends sent you", Color(0xFFEC4899).copy(alpha = 0.55f), songs, {
                GradientTile(180.dp, listOf(Color(0xFFEC4899), Color(0xFFF97316)), RoundedCornerShape(8.dp)) { Icon(Icons.Default.Send, null, tint = Color.White, modifier = Modifier.size(72.dp)) }
            })
        }
        if (vm.inbox.isEmpty()) item { Text("Nothing yet. When a friend sends you a song it shows up here.", color = Dim, modifier = Modifier.padding(24.dp)) }
        itemsIndexed(vm.inbox) { _, r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(social.avatarUrl(r.from, r.fromAvatarTs), r.fromDisplay, 22.dp)
                    Text("${r.fromDisplay} sent you ${if (r.isPlaylist) "a playlist" else "a song"} • ${ago(r.ts)}", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                    if (!r.seen) Box(Modifier.padding(start = 8.dp).size(8.dp).clip(CircleShape).background(Tint.accent))
                }
                if (r.message.isNotBlank()) Text("“${r.message}”", modifier = Modifier.padding(start = 46.dp, end = 16.dp, top = 4.dp), fontSize = 14.sp)
                if (r.isPlaylist) {
                    Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.PlaylistS(r.song.id)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(api.coverUrl(r.song.coverArt, 160), 50.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(r.song.title, fontWeight = FontWeight.SemiBold)
                            Text("Playlist • by ${r.fromDisplay}", color = Dim, fontSize = 13.sp)
                        }
                    }
                } else {
                    val i = songs.indexOfFirst { it.id == r.song.id }.coerceAtLeast(0)
                    SongRow(vm, r.song, songs, i)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun SendSheet(vm: AppVm) {
    val social = vm.social
    val song = vm.sendSong
    val pl = vm.sendPlaylist
    var target by remember { mutableStateOf<Friend?>(null) }
    var msg by remember { mutableStateOf("") }
    val close = { vm.sendSong = null; vm.sendPlaylist = null }
    ModalBottomSheet(onDismissRequest = { close() }, containerColor = Card) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text(if (pl != null) "Share \"${pl.name}\"" else "Send \"${song?.title}\"", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            when {
                social == null || (vm.socialError != null && vm.friends.isEmpty()) ->
                    Text("The friends server isn't reachable. Check it in Settings.", color = Dim, modifier = Modifier.padding(20.dp))
                vm.friends.isEmpty() -> Text("No friends yet. Your friend needs to sign in to Issa Music once with their own account.", color = Dim, modifier = Modifier.padding(20.dp))
                target == null -> vm.friends.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { target = f }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(social.avatarUrl(f.name, f.avatarTs), f.display, 44.dp, ring = if (f.playing) Live else null)
                        Text(f.display, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp))
                    }
                }
                else -> Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("To ${target!!.display}", color = Dim)
                    if (pl != null && !pl.public) Text("The playlist will be shared with your friends so they can open it.", color = Dim2, fontSize = 12.sp)
                    OutlinedTextField(msg, { msg = it.take(300) }, placeholder = { Text("Say something (optional)") }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton({ target = null }, shape = RoundedCornerShape(50)) { Text("Back") }
                        Button({
                            if (pl != null) vm.recommendPlaylist(target!!, pl, msg.trim()) else song?.let { vm.recommend(target!!, it, msg.trim()) }
                            close()
                        }, shape = RoundedCornerShape(50)) { Text("Send") }
                    }
                }
            }
        }
    }
}

/* ================= settings ================= */

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 26.dp, bottom = 8.dp))
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface1), content = content)
}

val LocalSettingsQuery = compositionLocalOf { "" }

@Composable
fun SettingRow(icon: ImageVector, title: String, sub: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val q = LocalSettingsQuery.current
    if (q.isNotBlank() && !title.contains(q, true) && sub?.contains(q, true) != true) return
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Card2), Alignment.Center) { Icon(icon, null, tint = Tint.light, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (sub != null) Text(sub, color = Dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/* ================= artist / album / playlist ================= */

@Composable
fun ArtistScreen(vm: AppVm, id: String) {
    val api = vm.api ?: return
    Load(id, { val (a, albums) = api.artist(id); Triple(a, albums, try { api.search(a.name).songs.filter { it.artist.contains(a.name, true) }.take(10) } catch (_: Exception) { emptyList() }) }) { (artist, albums, popular), _ ->
        val color = rememberCoverColor(api.coverUrl(artist.coverArt, 200))
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                ListHeader(vm, artist.name, "Artist • ${albums.size} albums", color, popular, { Cover(api.coverUrl(artist.coverArt, 500), 200.dp, CircleShape) }) {
                    OutlinedButton({ vm.toggleStar(artist.id) }, shape = RoundedCornerShape(50), modifier = Modifier.padding(start = 8.dp)) {
                        Text(if (artist.id in vm.starred) "Following" else "Follow", color = Color.White)
                    }
                    TextButton({ vm.startRadio(artist = artist.name) }) { Text("◎ Radio", color = Color.White) }
                }
            }
            if (popular.isNotEmpty()) {
                item { SectionTitle("Popular") }
                itemsIndexed(popular) { i, s -> SongRow(vm, s, popular, i, number = i + 1) }
            }
            if (albums.isNotEmpty()) {
                item { SectionTitle("Discography") }
                itemsIndexed(albums) { _, a -> AlbumRow(vm, a) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun AlbumScreen(vm: AppVm, id: String) {
    val api = vm.api ?: return
    Load(id, { api.album(id) }) { (album, songs), _ ->
        val color = rememberCoverColor(api.coverUrl(album.coverArt, 200))
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                ListHeader(vm, album.name, listOfNotNull(album.artist.ifEmpty { null }, "Album", album.year?.toString(), "${songs.size} songs").joinToString(" • "), color, songs,
                    { Cover(api.coverUrl(album.coverArt, 600), 230.dp, RoundedCornerShape(4.dp)) }) {
                    LikeBtn(vm, album.id)
                    DownloadButton(vm, songs)
                    if (album.artistId != null) TextButton({ vm.go(Screen.ArtistS(album.artistId)) }) { Text(album.artist, color = Color.White, maxLines = 1) }
                }
            }
            itemsIndexed(songs) { i, s -> SongRow(vm, s, songs, i) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun PlaylistScreen(vm: AppVm, id: String) {
    val api = vm.api ?: return
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Load("$id/${vm.playlistTick}", { vm.playlistSongs(id) }) { (pl, songs), reload ->
        val mine = pl.owner.isEmpty() || pl.owner.equals(api.user, true)
        val own = vm.plCovers.containsKey(pl.id)
        val color = rememberCoverColor(vm.plCover(pl, 200))
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                ListHeader(vm, pl.name, listOf(if (mine) "Your playlist" else "By ${vm.nameOf(pl.owner.lowercase())}", "${songs.size} songs", if (pl.public) "Shared with friends" else "Private").joinToString(" • "),
                    color, songs, { if (own) Cover(vm.plCover(pl, 600), 220.dp, RoundedCornerShape(6.dp)) else Collage(songs.take(4).map { api.coverUrl(it.coverArt, 300) }, 220.dp) }) {
                    IconBtn({ vm.sendPlaylist = pl }) { Icon(Icons.Default.Send, null, tint = Dim) }
                    DownloadButton(vm, songs)
                    IconBtn({ vm.playlistMenu = pl }) { Icon(Icons.Default.MoreVert, null, tint = Dim) }
                }
                if (mine) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface1).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Share, null, tint = Tint.light)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("Share with friends", fontWeight = FontWeight.SemiBold)
                        Text("Friends can see and play this playlist", color = Dim, fontSize = 12.sp)
                    }
                    Switch(pl.public, { on -> vm.setPlaylistPublic(pl, on) { reload() } }, colors = SwitchDefaults.colors(checkedTrackColor = Tint.accent))
                }
                if (songs.isEmpty()) Text("This playlist is empty. Long-press any song and choose Add to playlist.", color = Dim, modifier = Modifier.padding(24.dp))
            }
            itemsIndexed(songs) { i, s ->
                SongRow(vm, s, songs, i, onRemove = if (mine) ({ scope.launch { try { if (vm.isServerList(id)) vm.social?.removeFromList(id, i) else api.removeFromPlaylist(id, i); reload(); vm.refreshPlaylists() } catch (e: Exception) { vm.toast = e.message } } }) else null)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false }, containerColor = Card, title = { Text("Delete \"${pl.name}\"?") },
            confirmButton = { TextButton({ confirmDelete = false; scope.launch { try { api.deletePlaylist(id); vm.refreshPlaylists(); vm.back() } catch (e: Exception) { vm.toast = e.message } } }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel", color = Dim) } }
        )
    }
}

/* ================= menus ================= */

@Composable
fun SongMenu(vm: AppVm, s: Song) {
    val api = vm.api ?: return
    val ctx = LocalContext.current
    val close = { vm.menuSong = null }
    ModalBottomSheet(onDismissRequest = { close() }, containerColor = Card) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(api.coverUrl(s.coverArt, 120), 52.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(s.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, color = Dim, fontSize = 13.sp, maxLines = 1)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Card2)
            @Composable fun item(icon: ImageVector, label: String, onClick: () -> Unit) {
                Row(Modifier.fillMaxWidth().clickable { close(); onClick() }.padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = Dim); Text(label, modifier = Modifier.padding(start = 18.dp), fontSize = 15.sp)
                }
            }
            item(if (s.id in vm.starred) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (s.id in vm.starred) "Remove from Liked Songs" else "Add to Liked Songs") { vm.toggleStar(s.id) }
            if (vm.inJam) item(Icons.Default.Add, "Add to the Jam queue") { vm.addToJam(s) }
            item(Icons.Default.Send, "Send to a friend") { vm.sendSong = s }
            item(Icons.Default.Add, "Add to playlist") { vm.pickerSong = s }
            item(Icons.Default.Refresh, "Start radio") { vm.startRadio(seed = s) }
            item(Icons.Default.Close, "Not interested in this song") { vm.hideSong(s) }
            if (vm.mainArtist(s.artist).isNotBlank()) item(Icons.Default.Close, "Don't recommend ${vm.mainArtist(s.artist)}") { vm.hideArtist(s.artist) }
            if (vm.isDownloaded(s.id)) item(Icons.Default.Delete, "Remove download") { vm.removeDownload(s.id) }
            else item(Icons.Default.KeyboardArrowDown, "Download") { vm.download(listOf(s)) }
            item(Icons.Default.Share, "Share to Instagram / Snapchat") { vm.shareSong(ctx, s) }
            item(Icons.Default.PlayArrow, "Play next") { vm.playNext(s) }
            item(Icons.Default.List, "Add to queue") { vm.enqueue(s) }
            if (s.artistId != null) item(Icons.Default.Person, "Go to artist") { vm.go(Screen.ArtistS(s.artistId)) }
            if (s.albumId != null) item(Icons.Default.Star, "Go to album") { vm.go(Screen.AlbumS(s.albumId)) }
        }
    }
}

@Composable
fun PlaylistPicker(vm: AppVm, s: Song) {
    val api = vm.api ?: return
    var showNew by remember { mutableStateOf(false) }
    var showCollab by remember { mutableStateOf(false) }
    val close = { vm.pickerSong = null }
    val mine = vm.playlists.filter { it.owner.isEmpty() || it.owner.equals(api.user, true) }
    LaunchedEffect(Unit) { vm.refreshPlaylists() }
    ModalBottomSheet(onDismissRequest = { close() }, containerColor = Card) {
        Column(Modifier.padding(bottom = 28.dp).heightIn(min = 160.dp)) {
            Text("Add to playlist", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ showNew = true }, shape = RoundedCornerShape(50)) { Text("New playlist") }
                OutlinedButton({ showCollab = true }, shape = RoundedCornerShape(50)) { Text("New with a friend") }
            }
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                itemsIndexed(vm.collabs) { _, c ->
                    Row(Modifier.fillMaxWidth().clickable { close(); vm.collabAdd(c, s) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Collage(c.songs.take(4).map { api.coverUrl(it.song.coverArt, 120) }, 48.dp, RoundedCornerShape(4.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold)
                            Text("Together • ${c.songs.size} songs", color = Tint.light, fontSize = 12.sp)
                        }
                    }
                }
                itemsIndexed(mine) { _, p ->
                    Row(Modifier.fillMaxWidth().clickable { close(); vm.addToPlaylist(p, s) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(vm.plCover(p, 120), 48.dp)
                        Column(Modifier.padding(start = 12.dp)) { Text(p.name, fontWeight = FontWeight.SemiBold); Text("${p.songCount} songs", color = Dim, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
    if (showNew) NewPlaylistDialog({ showNew = false }) { n -> showNew = false; close(); vm.newPlaylist(n, s) }
    if (showCollab) NewCollabDialog(vm, s) { showCollab = false; close() }
}

/* ================= player ================= */

@Composable
fun rememberPosition(vm: AppVm): Pair<Long, Long> {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    val clock = remember(vm) { PlayClock(vm) }
    LaunchedEffect(vm.shown, vm.isPlaying) {
        while (true) {
            val (p, d) = clock.now() // what you hear – during a crossfade the song fading in
            pos = p; dur = d
            delay(if (vm.isPlaying) 250 else 600)
        }
    }
    return pos to dur
}

@Composable
fun MiniPlayer(vm: AppVm) {
    val item = vm.shown ?: return
    val api = vm.api ?: return
    val s = item.toSong()
    val coverColor = rememberCoverColor(api.coverUrl(s.coverArt, 120), Card2)
    val color by animateColorAsState(if (vm.opt.miniStyle == "dark") Card2 else coverColor, tween(if (vm.opt.transitions) 600 else 0), label = "miniColor")
    Column(Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(color).clickable { vm.showFull = true }) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(api.coverUrl(s.coverArt, 120), 42.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(if (vm.buffering) "Loading…" else s.artist, color = Color(0xFFDDDDDD), fontSize = 12.sp, maxLines = 1)
            }
            LikeBtn(vm, s.id, 40.dp)
            IconBtn({ vm.toggle() }, 40.dp) { Ico(if (vm.isPlaying) "pause" else "play", 26.dp) }
        }
        MiniProgress(vm, Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
fun FullPlayer(vm: AppVm) {
    val ctx = LocalContext.current
    val item = vm.shown ?: run { vm.showFull = false; return }
    val api = vm.api ?: return
    val s = item.toSong()
    val color by animateColorAsState(rememberCoverColor(api.coverUrl(s.coverArt, 200), Color(0xFF3A3A3A)), tween(if (vm.opt.transitions) 700 else 0), label = "fullColor")
    val o = vm.opt
    val coverScale by animateFloatAsState(if (!o.coverAnim || vm.isPlaying) 1f else 0.86f, tween(if (o.transitions) 450 else 0), label = "cover")
    Surface(Modifier.fillMaxSize(), color = BgSolid) {
        if (o.playerBg == "blur" && android.os.Build.VERSION.SDK_INT >= 31)
            AsyncImage(api.coverUrl(s.coverArt, 300), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(70.dp).graphicsLayer { alpha = 0.6f })
        val bgBrush = when (o.playerBg) {
            "black" -> Brush.verticalGradient(listOf(Bg, Bg))
            "blur" -> if (android.os.Build.VERSION.SDK_INT >= 31) Brush.verticalGradient(listOf(Color(0x66000000), Color(0xCC000000), Bg)) else Brush.verticalGradient(0f to color, 0.75f to Bg)
            else -> Brush.verticalGradient(0f to color, 0.75f to Bg)
        }
        Box(Modifier.fillMaxSize().background(bgBrush)) {
            LazyColumn(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBtn({ vm.showFull = false }) { Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(30.dp)) }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("PLAYING FROM", color = Color(0xFFDDDDDD), fontSize = 10.sp, letterSpacing = 1.sp)
                            Text(s.album.ifEmpty { "Your queue" }, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconBtn({ vm.menuSong = s }) { Icon(Icons.Default.MoreVert, null) }
                    }
                    Spacer(Modifier.height(if (o.playerLayout == "minimal") 40.dp else 24.dp))
                    Box(Modifier.scale(coverScale)) {
                        when (o.playerLayout) {
                            "big" -> BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { Cover(api.coverUrl(s.coverArt, 800), maxWidth, RoundedCornerShape(12.dp)) }
                            "minimal" -> Cover(api.coverUrl(s.coverArt, 800), 230.dp, RoundedCornerShape(16.dp))
                            else -> Cover(api.coverUrl(s.coverArt, 800), 320.dp, RoundedCornerShape(8.dp))
                        }
                    }
                    Spacer(Modifier.height(if (o.playerLayout == "minimal") 36.dp else 28.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist, color = Color(0xFFDDDDDD), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(enabled = s.artistId != null) { vm.go(Screen.ArtistS(s.artistId!!)) })
                        }
                        LikeBtn(vm, s.id)
                    }
                    SeekBar(vm, Color.White, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconBtn({ vm.toggleShuffle() }, 48.dp) {
                            Box(contentAlignment = Alignment.BottomCenter) {
                                Ico("shuffle", 28.dp, if (vm.shuffle) Tint.light else Color.White)
                                if (vm.shuffle) Box(Modifier.offset(y = 8.dp).size(4.dp).clip(CircleShape).background(Tint.light))
                            }
                        }
                        IconBtn({ vm.prev() }, 56.dp) { Ico("prev", 36.dp) }
                        IconBtn({ vm.toggle() }, 72.dp, Color.White) { Ico(if (vm.isPlaying) "pause" else "play", 36.dp, Color.Black) }
                        IconBtn({ vm.next() }, 56.dp) { Ico("next", 36.dp) }
                        IconBtn({ vm.cycleRepeat() }, 48.dp) {
                            Box(contentAlignment = Alignment.BottomCenter) {
                                Ico(if (vm.repeat == 2) "repeat1" else "repeat", 28.dp, if (vm.repeat > 0) Tint.light else Color.White)
                                if (vm.repeat > 0) Box(Modifier.offset(y = 8.dp).size(4.dp).clip(CircleShape).background(Tint.light))
                            }
                        }
                    }
                    if (o.playerVolume) VolumeBar(vm)
                    val sleep = sleepLabel(vm)
                    // Only two buttons under the controls; everything else is behind "More".
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).clickable { vm.loadLyrics(s); vm.showLyrics = true }
                                .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically
                        ) { Ico("lyrics", 18.dp); Spacer(Modifier.width(8.dp)); Text("Lyrics", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).clickable { vm.showQueue = true }
                                .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically
                        ) { Ico("queue", 18.dp); Spacer(Modifier.width(8.dp)); Text("Queue", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).clickable { vm.showPlayerMore = true }
                                .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically
                        ) { Text("•••", fontSize = 13.sp, fontWeight = FontWeight.Black); Spacer(Modifier.width(8.dp)); Text("More", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    }
                    val notes = listOfNotNull(
                        if (vm.isJamGuest) "In ${vm.jam?.hostDisplay ?: "a"}'s Jam – the host controls playback" else if (vm.isJamHost) "Hosting a Jam" else null,
                        vm.radioSeed?.let { "$it radio" }, sleep, if (vm.fadeSec > 0) "Fade ${vm.fadeSec}s" else null,
                        if (vm.isDownloaded(s.id)) "Downloaded" else null
                    )
                    if (notes.isNotEmpty()) Text(notes.joinToString(" • ") + if (vm.inJam) "  ›" else "", color = if (vm.inJam) Live else Color(0xFFCCCCCC), fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = vm.inJam) { vm.showJam = true }.padding(horizontal = 24.dp, vertical = 4.dp))
                    JamSkipBar(vm)
                    if (o.playerLayout != "minimal") UpNext(vm)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        if (vm.showLyrics) { val (lp, _) = rememberPosition(vm); LyricsOverlay(vm, s, lp, color) }
        if (vm.showPlayerMore) PlayerMoreSheet(vm, s)
    }
}

/** Glass style: the current cover, blurred and darkened, behind the whole app. */
@Composable
fun GlassBackdrop(vm: AppVm) {
    val api = vm.api
    val art = vm.shown?.toSong()?.coverArt
    val tint by animateColorAsState(rememberCoverColor(art?.let { api?.coverUrl(it, 120) }, Color(0xFF1A1830)), tween(900), label = "glassTint")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerp(tint, Color.Black, 0.25f), lerp(tint, Color.Black, 0.75f), Color(0xFF07070B)))))
    if (art != null && api != null && android.os.Build.VERSION.SDK_INT >= 31)
        AsyncImage(api.coverUrl(art, 300), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(90.dp).graphicsLayer { alpha = 0.35f })
    Box(Modifier.fillMaxSize().background(Color(0x66000000)))
}


/** Your picture in the top bar: opens a small menu – your profile, Settings (⚙) and history. */
@Composable
fun AccountButton(vm: AppVm) {
    val social = vm.social
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.clip(CircleShape).clickable { open = true }.semantics { contentDescription = "Account menu: profile and settings" }) {
            Avatar(social?.avatarUrl(social.me, vm.myProfile?.avatarTs ?: 0), vm.displayName(), 34.dp)
        }
        DropdownMenu(open, { open = false }, containerColor = Card) {
            if (social != null) DropdownMenuItem({ Text("Your profile") }, { open = false; vm.go(Screen.Profile(social.me)) }, leadingIcon = { Icon(Icons.Default.AccountCircle, null) })
            DropdownMenuItem({ Text("Settings", fontWeight = FontWeight.SemiBold) }, { open = false; vm.go(Screen.Settings) }, leadingIcon = { Icon(Icons.Default.Settings, null) })
            DropdownMenuItem({ Text("Listening history") }, { open = false; vm.go(Screen.History) }, leadingIcon = { Icon(Icons.Default.Refresh, null) })
        }
    }
}
