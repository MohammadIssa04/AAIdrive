package com.musik.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.objs(k: String): List<JSONObject> = optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

val PROFILE_COLORS = listOf("" to 0L, "#8B5CF6" to 0xFF8B5CF6, "#EC4899" to 0xFFEC4899, "#EF4444" to 0xFFEF4444, "#F59E0B" to 0xFFF59E0B,
    "#10B981" to 0xFF10B981, "#06B6D4" to 0xFF06B6D4, "#3B82F6" to 0xFF3B82F6, "#D4AF37" to 0xFFD4AF37, "#E5E7EB" to 0xFFE5E7EB)

fun profileColor(theme: String?): Color? = try {
    if (theme != null && Regex("#[0-9a-fA-F]{6}").matches(theme)) Color(android.graphics.Color.parseColor(theme)) else null
} catch (_: Exception) { null }

/** A friend's (or your own) music identity: banner, favourites and what their listening says about them. */
@Composable
fun ProfileScreen(vm: AppVm, user: String) {
    val social = vm.social
    val api = vm.api ?: return
    val key = user.lowercase()
    LaunchedEffect(key) { vm.loadProfile(key) }
    val p = vm.profiles[key]
    val mine = social != null && key == social.me
    var editing by remember { mutableStateOf(false) }
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.bannerCropUri = it } }
    val accent = profileColor(p?.optString("theme")) ?: Tint.accent

    if (p == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar("Profile", { vm.back() }) { if (mine) IconBtn({ vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") } }
            if (vm.profileError != null) EmptyCard("Couldn't load the profile", vm.profileError!!) { Button({ vm.loadProfile(key) }) { Text("Try again") } }
            else Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator(color = Tint.accent) }
        }
        return
    }
    val display = p.optString("display", user)
    val bannerUrl = social?.bannerUrl(key, p.optLong("bannerTs"))

    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to accent.copy(alpha = 0.25f), 0.5f to Color.Transparent))) {
        // ---------- banner + name
        item {
            Box(Modifier.fillMaxWidth().height(250.dp)) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent, lerpC(accent, Color.Black, 0.6f)))))
                if (bannerUrl != null) AsyncImage(bannerUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x55000000), 0.45f to Color.Transparent, 1f to BgSolid)))
                Row(Modifier.statusBarsPadding().fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn({ vm.back() }) { Icon(Icons.Default.ArrowBack, null) }
                    Spacer(Modifier.weight(1f))
                    if (mine) IconBtn({ bannerPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.Face, "Change background image") }
                    if (mine) IconBtn({ vm.go(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
                }
                Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.border(3.dp, accent, CircleShape).padding(3.dp)) { Avatar(social?.avatarUrl(key, p.optLong("avatarTs")), display, 84.dp) }
                    Column(Modifier.padding(start = 14.dp, bottom = 6.dp)) {
                        Text(display, fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val since = p.optLong("since")
                        Text(if (since > 0) "Listening since ${java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date(since * 1000))}" else "@$key",
                            color = Dim, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val bio = p.optString("bio")
                if (bio.isNotBlank()) Text(bio, modifier = Modifier.padding(top = 10.dp), fontSize = 14.sp)
                if (mine) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ editing = true }, shape = RoundedCornerShape(50)) { Text("Edit profile") }
                    OutlinedButton({ vm.go(Screen.Settings) }, shape = RoundedCornerShape(50)) { Icon(Icons.Default.Settings, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Settings") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IdBox("${p.optInt("minutes")}", "minutes", accent, Modifier.weight(1f))
                    IdBox("${p.optInt("plays")}", "plays", accent, Modifier.weight(1f))
                    IdBox("${p.optInt("artistsCount")}", "artists", accent, Modifier.weight(1f))
                }
            }
        }
        // ---------- top artists: picked automatically from what they actually play
        val arts = p.objs("topArtists").take(10)
        if (arts.isNotEmpty()) {
            item { SmallTitle("Top artists") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(arts) { a ->
                        val an = a.optString("name")
                        LaunchedEffect(an) { vm.artistImage(an) }
                        Column(Modifier.width(96.dp).clickable { vm.openArtistNamed(an) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Cover(api.coverUrl(vm.artistImages[an.lowercase()] ?: a.optString("coverArt").ifEmpty { null }, 200), 96.dp, CircleShape)
                            Text(an, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            Text("${a.optInt("count")} plays", fontSize = 11.sp, color = Dim)
                        }
                    }
                }
            }
        }
        val albums = p.objs("topAlbums")
        if (albums.isNotEmpty()) {
            item { SmallTitle("Most played albums") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(albums) { a ->
                        Column(Modifier.width(110.dp).clickable(enabled = a.optString("id").isNotEmpty()) { vm.go(Screen.AlbumS(a.optString("id"))) }) {
                            Cover(api.coverUrl(a.optString("coverArt").ifEmpty { null }, 300), 110.dp, RoundedCornerShape(8.dp))
                            Text(a.optString("name"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            Text("${a.optInt("count")} plays", fontSize = 11.sp, color = Dim)
                        }
                    }
                }
            }
        }
        if (p.optInt("plays") == 0) item { EmptyCard("No music yet", if (mine) "Play some music and your profile fills up by itself." else "$display hasn't played anything yet.") }
        item { Spacer(Modifier.height(140.dp)) }
    }

    if (editing) EditProfileDialog(vm, p) { editing = false }
}

private fun lerpC(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

@Composable
private fun IdBox(value: String, label: String, accent: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Card).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Black, color = lerpC(accent, Color.White, 0.35f))
        Text(label, color = Dim, fontSize = 11.sp)
    }
}

@Composable
private fun EditProfileDialog(vm: AppVm, p: JSONObject, onDone: () -> Unit) {
    var bio by remember { mutableStateOf(p.optString("bio")) }
    var theme by remember { mutableStateOf(p.optString("theme")) }
    AlertDialog(onDismissRequest = onDone, containerColor = Card,
        title = { Text("Edit profile") },
        text = {
            Column {
                OutlinedTextField(bio, { bio = it.take(160) }, label = { Text("About you") }, placeholder = { Text("Tarab at night, rap in the gym…") }, modifier = Modifier.fillMaxWidth())
                Text("${bio.length}/160", color = Dim2, fontSize = 11.sp, modifier = Modifier.align(Alignment.End))
                Text("Profile colour", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PROFILE_COLORS.forEach { (hex, c) ->
                        Box(Modifier.size(34.dp).clip(CircleShape).background(if (c == 0L) Card2 else Color(c))
                            .then(if (theme == hex) Modifier.border(3.dp, Color.White, CircleShape) else Modifier).clickable { theme = hex }, Alignment.Center) {
                            if (c == 0L) Text("A", fontSize = 12.sp, color = Dim)
                        }
                    }
                }
                Text("\"A\" uses your app accent colour. Tap the picture icon on your profile to change the background image.", color = Dim2, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton({ vm.saveProfile(theme, bio.trim(), p.optJSONObject("favs") ?: JSONObject(), onDone) }, enabled = !vm.profileBusy) { Text("Save") } },
        dismissButton = { TextButton(onDone) { Text("Cancel") } })
}

