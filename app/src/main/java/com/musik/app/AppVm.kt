package com.musik.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import coil.imageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream

sealed class Screen {
    object Home : Screen()
    object Search : Screen()
    object Library : Screen()
    object Liked : Screen()
    object Settings : Screen()
    object Inbox : Screen()
    object Upload : Screen()
    object Downloads : Screen()
    object Weekly : Screen()
    object WrappedS : Screen()
    object Equalizer : Screen()
    object TimeMix : Screen()
    object Stats : Screen()
    object Diag : Screen()
    data class Profile(val user: String) : Screen()
    object History : Screen()
    data class CollabS(val id: Long) : Screen()
    data class MixS(val index: Int) : Screen()
    data class TopS(val user: String) : Screen()
    data class ArtistS(val id: String) : Screen()
    data class AlbumS(val id: String) : Screen()
    data class PlaylistS(val id: String) : Screen()
}

data class Mix(val title: String, val subtitle: String, val songs: List<Song>, val color: Long, val seed: String)
data class HomeData(val mixes: List<Mix>, val discover: List<Song>, val noSeeds: Boolean, val timeMix: Mix? = null)

/** Classic Arabic picks by time of day: (title, subtitle, search terms, artist match fragments, color). */
data class TimeSlot(val title: String, val subtitle: String, val queries: List<String>, val match: List<String>, val color: Long)

fun timeSlot(hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): TimeSlot = when (hour) {
    in 5..11 -> TimeSlot("Morning with Fairouz", "Start the day like Beirut mornings", listOf("Fairuz", "Fairouz", "فيروز"), listOf("fair", "فيروز"), 0xFFE8A33D)
    in 12..17 -> TimeSlot("Afternoon with Abdel Halim", "Abdel Halim Hafez for the afternoon", listOf("Abdel Halim Hafez", "Abdelhalim Hafez", "عبد الحليم حافظ"), listOf("halim", "حليم"), 0xFFD4553B)
    else -> TimeSlot("Evening with Umm Kulthum", "Oum Kalthoum for the night", listOf("Oum Kalthoum", "Umm Kulthum", "Om Kalthoum", "أم كلثوم"), listOf("kalth", "kulth", "koulth", "كلثوم"), 0xFF5B3FA8)
}

val EQ_PRESETS = listOf(
    "Flat" to listOf(0, 0, 0, 0, 0), "Bass boost" to listOf(600, 400, 0, 0, 0), "Vocal" to listOf(-200, 0, 400, 300, 0),
    "Tarab (warm)" to listOf(300, 200, 100, -100, -200), "Treble" to listOf(0, 0, 0, 400, 600), "Loud" to listOf(500, 200, -100, 200, 500),
    "Hip-hop" to listOf(500, 300, -100, 100, 200), "Rock" to listOf(400, 100, -200, 200, 400), "Classical" to listOf(300, 100, 0, 200, 300),
    "Late night (quiet)" to listOf(500, 200, 0, 200, 400), "Podcast" to listOf(-300, 0, 400, 300, -100)
)
data class UploadItem(val name: String, val progress: Float, val status: String, val done: Boolean = false, val failed: Boolean = false)

val ACCENTS = listOf(
    "Purple" to 0xFF8B5CF6, "Green" to 0xFF22C55E, "Blue" to 0xFF3B82F6, "Pink" to 0xFFEC4899, "Orange" to 0xFFF97316
)
val QUALITIES = listOf(0 to "Original", 320 to "High (320 kbps)", 160 to "Normal (160 kbps)", 96 to "Data saver (96 kbps)")
private val MIX_COLORS = listOf(0xFF1E88E5, 0xFFE53935, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFF00ACC1)

fun MediaItem.toSong(): Song {
    val e = mediaMetadata.extras
    return Song(
        id = mediaId, title = mediaMetadata.title?.toString() ?: "", artist = mediaMetadata.artist?.toString() ?: "",
        album = mediaMetadata.albumTitle?.toString() ?: "", albumId = e?.getString("albumId"),
        artistId = e?.getString("artistId"), coverArt = e?.getString("coverArt"), duration = e?.getInt("duration") ?: 0,
        genre = e?.getString("genre")
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AppVm(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("musik", Context.MODE_PRIVATE)

    var api by mutableStateOf<Api?>(null); private set
    var social by mutableStateOf<Social?>(null); private set
    var loginError by mutableStateOf<String?>(null)
    var loggingIn by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)

    // ---------- navigation
    val stack = mutableStateListOf<Screen>(Screen.Home)
    fun go(s: Screen) { showFull = false; stack.add(s) }
    fun back(): Boolean { if (stack.size > 1) { if (stack.removeAt(stack.size - 1) is Screen.Search) clearSearch(); return true }; return false }
    /** The bottom bar: switching tabs starts Search fresh (an artist opened from search keeps the results when you go back). */
    fun tab(s: Screen) { showFull = false; stack.clear(); stack.add(s); clearSearch() }
    fun clearSearch() {
        searchJob?.cancel(); searchSeq++
        query = ""; result = null; lyricHits = emptyList(); searchWhy = emptyMap(); searchTop = null; searchPartial = false; searching = false; searchError = null
    }

    // ---------- settings
    var fadeSec by mutableIntStateOf(prefs.getInt("fade", 0)); private set
    fun setFade(sec: Int) { fadeSec = sec; prefs.edit().putInt("fade", sec).apply() }
    var accent by mutableIntStateOf(prefs.getInt("accent", 0)); private set
    var accentColor by mutableIntStateOf(prefs.getInt("accent_color", ACCENTS[prefs.getInt("accent", 0).coerceIn(0, ACCENTS.size - 1)].second.toInt())); private set
    fun chooseAccent(i: Int) { accent = i; prefs.edit().putInt("accent", i).apply(); pickAccentColor(ACCENTS[i].second.toInt()) }
    fun pickAccentColor(argb: Int) { accentColor = argb; prefs.edit().putInt("accent_color", argb).apply() }
    val opt = Opts(prefs).also { o -> if (o.volStart >= 0) o.volApp = o.volStart } // start volume, if chosen
    var quality by mutableIntStateOf(prefs.getInt("quality", 0)); private set
    fun chooseQuality(kbps: Int) { quality = kbps; api?.maxBitRate = kbps; prefs.edit().putInt("quality", kbps).apply(); toast = "Applies from the next song" }
    var shareActivity by mutableStateOf(prefs.getBoolean("share", true)); private set
    var carNext by mutableStateOf(prefs.getBoolean("car_next", true)); private set
    fun chooseCarNext(on: Boolean) { carNext = on; prefs.edit().putBoolean("car_next", on).apply() }
    var carShort by mutableStateOf(prefs.getBoolean("car_short", true)); private set
    fun chooseCarShort(on: Boolean) { carShort = on; prefs.edit().putBoolean("car_short", on).apply() }
    var carAutoplay by mutableStateOf(prefs.getBoolean("car_autoplay", true)); private set
    fun chooseCarAutoplay(on: Boolean) { carAutoplay = on; prefs.edit().putBoolean("car_autoplay", on).apply() }
    var carQueue by mutableStateOf(prefs.getBoolean("car_queue", false)); private set
    fun chooseCarQueue(on: Boolean) { carQueue = on; prefs.edit().putBoolean("car_queue", on).apply() }
    fun setShare(on: Boolean) {
        shareActivity = on; prefs.edit().putBoolean("share", on).apply()
        val s = social ?: return
        if (!on) viewModelScope.launch { try { s.nowPlaying(null, false) } catch (_: Exception) {} }
    }
    fun socialBase(): String = prefs.getString("social", null) ?: defaultSocial(prefs.getString("base", null) ?: "http://192.168.0.14:5274")
    private fun defaultSocial(base: String): String {
        val u = Uri.parse(base.trim())
        return "${u.scheme ?: "http"}://${u.host ?: "192.168.0.14"}:5280"
    }
    fun setSocialBase(url: String) {
        prefs.edit().putString("social", url.trim()).apply()
        api?.let { social = Social(url, it.user, it.pass); refreshSocial(); loadTops() }
    }
    private fun forgetSearchesOnServer() { social?.let { s -> viewModelScope.launch { try { s.clearSearchHistory() } catch (_: Exception) {} } } }
    fun clearSearches() { prefs.edit().remove("searches").apply(); recentSearches = emptyList(); forgetSearchesOnServer(); toast = "Recent searches cleared" }
    fun clearHistory() { prefs.edit().remove("history").remove("searches").apply(); recentSearches = emptyList(); forgetSearchesOnServer(); toast = "History cleared"; loadHome(true) }
    /** The recent searches from the server (all devices), merged with this phone's. */
    fun syncSearches() {
        val s = social ?: return
        viewModelScope.launch {
            try {
                val l = s.searchHistory(); if (l.isEmpty()) return@launch
                val mine = recentSearches.filter { x -> l.none { it.equals(x, true) } }
                recentSearches = (l + mine).take(30)
                prefs.edit().putString("searches", recentSearches.joinToString("\n")).apply()
            } catch (_: Exception) {}
        }
    }

    // ---------- likes
    var starred by mutableStateOf<Set<String>>(emptySet())
    /** What the screen shows as playing: during a crossfade already the song that's fading in. */
    val shown: MediaItem? get() = PlaybackService.xfItem ?: current
    var likedSongs by mutableStateOf<List<Song>>(emptyList())
    private var likedArtistNames: List<String> = emptyList()
    fun refreshStarred() {
        val a = api ?: return
        viewModelScope.launch {
            try { val s = a.starred(); likedSongs = s.songs; likedArtistNames = s.artists.map { it.name }; taste = null
                starred = (s.songs.map { it.id } + s.albums.map { it.id } + s.artists.map { it.id }).toSet() } catch (_: Exception) {}
        }
    }
    fun toggleStar(id: String) {
        val a = api ?: return
        val on = id !in starred
        viewModelScope.launch {
            try { a.star(id, on); starred = if (on) starred + id else starred - id; toast = if (on) "Added to Liked Songs" else "Removed from Liked Songs"; refreshStarred() }
            catch (e: Exception) { toast = "Couldn't update like: ${e.message}" }
        }
    }

    // ---------- search (live)
    var query by mutableStateOf("")
    var result by mutableStateOf<SearchResult?>(null)
    var searching by mutableStateOf(false)
    var searchError by mutableStateOf<String?>(null)
    /** Songs found through a line of their lyrics. */
    var lyricHits by mutableStateOf<List<LyricHit>>(emptyList())
    /** Why a search result is there ("Because you listen to …"), the best match and what the search was. */
    var searchWhy by mutableStateOf<Map<String, String>>(emptyMap())
    var searchTop by mutableStateOf<TopHit?>(null)
    var searchKind by mutableStateOf("mixed")
    var searchMine by mutableStateOf<Set<String>>(emptySet())
    var searchPartial by mutableStateOf(false)

    // ---------- your listening profile (for personal search) – server (all devices) + this phone
    private var taste: Taste? = null
    private var tasteAt = 0L
    private suspend fun loadTaste(): Taste? {
        taste?.let { if (System.currentTimeMillis() - tasteAt < 600_000) return it }
        val srv = try { social?.taste()?.also { prefs.edit().putString("taste_cache", it.toString()).apply() } } catch (_: Exception) { null }
            ?: try { JSONObject(prefs.getString("taste_cache", "{}") ?: "{}") } catch (_: Exception) { null }
        val liked = likedSongs; val likedA = likedArtistNames
        val t = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { // (reading the history is off the main thread)
            val recent = playLog().filter { System.currentTimeMillis() - it.second < 14 * 86_400_000L }.map { it.first }
            SmartSearch.taste(srv, liked, likedA, recent, skipMap("skips_songs"), skipMap("skips_artists"))
        }
        taste = t; tasteAt = System.currentTimeMillis(); return t
    }
    private fun skipMap(k: String): Map<String, Int> = try { val o = JSONObject(prefs.getString(k, "{}") ?: "{}"); o.keys().asSequence().associateWith { o.optInt(it) } } catch (_: Exception) { emptyMap() }
    /** You skipped a song early: it (and a little its artist) ranks lower in your search results. */
    private fun noteSkip() {
        val c = controller ?: return
        val s = shown?.toSong() ?: return
        val pos = PlaybackService.audiblePosition()?.first ?: c.currentPosition
        if (pos > 30_000 || (s.duration > 0 && pos > s.duration * 500L)) return
        val k = SmartSearch.key(s); val ak = SmartSearch.plain(SmartSearch.mainArtistOf(s.artist))
        val ss = JSONObject(prefs.getString("skips_songs", "{}") ?: "{}"); ss.put(k, minOf(9, ss.optInt(k) + 1))
        if (ss.length() > 600) ss.keys().asSequence().toList().take(ss.length() - 600).forEach { ss.remove(it) }
        val sa = JSONObject(prefs.getString("skips_artists", "{}") ?: "{}"); if (ak.isNotEmpty()) sa.put(ak, minOf(20, sa.optInt(ak) + 1))
        prefs.edit().putString("skips_songs", ss.toString()).putString("skips_artists", sa.toString()).apply()
        taste = null
    }
    var recentSearches by mutableStateOf(prefs.getString("searches", "")!!.split("\n").filter { it.isNotBlank() }); private set
    private var searchSeq = 0
    private var searchJob: kotlinx.coroutines.Job? = null
    fun search() {
        searchJob?.cancel() // typing on: drop the old search and its extra lookups
        val a = api ?: return
        val q = query.trim()
        if (q.length < 2) { result = null; searching = false; return }
        val seq = ++searchSeq
        searching = true; searchError = null
        searchJob = viewModelScope.launch {
            try {
                // shows the normal results right away, then adds other spellings, typo matches and lyric matches
                val show = { r: RankedSearch -> if (seq == searchSeq) { result = r.result; lyricHits = r.lyrics; searchWhy = r.why; searchTop = r.top; searchKind = r.kind; searchMine = r.mineArtists; searchPartial = r.partial } }
                val lyr: (suspend (String) -> List<Triple<String, String, String>>)? = if (opt.searchLyrics) { x -> social?.lyricSearch(x) ?: emptyList() } else null
                val r = SmartSearch.run(a, q, { loadTaste() }, lyr,
                    opt.searchPersonal, hiddenSongs, hiddenArtists,
                    { s -> LyricsClient.fetch(s)?.let { l -> l.plain ?: l.synced.joinToString("\n") { it.text } } }) { first -> show(first) }
                show(r)
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (seq == searchSeq) searchError = e.message ?: "Search failed" }
            if (seq == searchSeq) searching = false
        }
    }
    fun rememberSearch() {
        val q = query.trim(); if (q.length < 2) return
        recentSearches = (listOf(q) + recentSearches.filter { !it.equals(q, true) }).take(30)
        prefs.edit().putString("searches", recentSearches.joinToString("\n")).apply()
        social?.let { s -> viewModelScope.launch { try { s.rememberSearch(q) } catch (_: Exception) {} } }
    }

    // ---------- history (on the phone)
    fun history(): List<Song> = try {
        val a = JSONArray(prefs.getString("history", "[]"))
        (0 until a.length()).mapNotNull { songFromJson(a.optJSONObject(it)) }
    } catch (_: Exception) { emptyList() }
    var recents by mutableStateOf(history()); private set

    private fun addHistory(s: Song) {
        val list = (listOf(s) + recents.filter { it.id != s.id }).take(50)
        recents = list
        // saving is done off the main thread, so a song change never makes anything stutter
        viewModelScope.launch(Dispatchers.Default) {
            val a = JSONArray(); list.forEach { a.put(it.toJson()) }
            // full listening history with times (every play, newest first)
            val log = try { JSONArray(prefs.getString("play_log", "[]")) } catch (_: Exception) { JSONArray() }
            val last = log.optJSONObject(0)
            val out = JSONArray()
            if (last?.optString("id") != s.id || System.currentTimeMillis() - last.optLong("at") > 60_000) out.put(s.toJson().put("at", System.currentTimeMillis()))
            for (i in 0 until minOf(log.length(), 399)) out.put(log.get(i))
            prefs.edit().putString("history", a.toString()).putString("play_log", out.toString()).apply()
        }
    }
    /** Everything you played on this phone, newest first, with the time you played it. */
    fun playLog(): List<Pair<Song, Long>> = try {
        val a = JSONArray(prefs.getString("play_log", "[]"))
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> songFromJson(o)?.let { it to o.optLong("at") } } }
    } catch (_: Exception) { emptyList() }
    fun clearPlayLog() { prefs.edit().remove("play_log").apply(); toast = "History cleared" }

    // ---------- home
    var home by mutableStateOf<HomeData?>(null)
    var homeLoading by mutableStateOf(false)
    var homeError by mutableStateOf<String?>(null)
    var playlists by mutableStateOf<List<Playlist>>(emptyList())

    fun mainArtist(a: String) = a.split(",", "&", " feat.", " feat ", " ft.", " x ", " X ").first().trim()

    fun refreshPlaylists() {
        val a = api ?: return
        // only your own playlists and ones friends chose to share (the music server shows an admin everyone's private ones too)
        viewModelScope.launch {
            val nav = try { a.playlists().filter { it.owner.isEmpty() || it.owner.equals(a.user, true) || it.public } } catch (_: Exception) { null }
            val own = try { social?.lists() } catch (_: Exception) { null }
            if (nav != null || own != null) playlists = (own ?: playlists.filter { isServerList(it.id) }) + (nav ?: playlists.filter { !isServerList(it.id) })
        }
        viewModelScope.launch { try { social?.plCovers()?.let { m -> plCovers.clear(); plCovers.putAll(m) } } catch (_: Exception) {} }
        viewModelScope.launch { try { social?.genpl()?.let { g -> genIds.clear(); genIds.addAll(g.map { it.id }) } } catch (_: Exception) {} }
    }

    /** Ids of the "made for you" playlists. */
    val genIds = androidx.compose.runtime.mutableStateListOf<String>()

    // ---------- playlist pictures
    /** Playlists with their own picture (id → version). */
    val plCovers = androidx.compose.runtime.mutableStateMapOf<String, Long>()
    /** The picture for a playlist: your own if you set one, otherwise the automatic one. */
    fun plCover(p: Playlist, size: Int = 300): String? = social?.let { s -> plCovers[p.id]?.let { s.plCoverUrl(p.id, it) } } ?: api?.coverUrl(p.coverArt, size)
    var plCoverCrop by mutableStateOf<Pair<String, Uri>?>(null)
    fun setPlCover(id: String, bmp: Bitmap) {
        val s = social ?: run { toast = "Friends server not set up"; return }
        plCoverCrop = null
        viewModelScope.launch {
            try {
                val jpeg = withContext(Dispatchers.Default) { ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray() }
                s.uploadPlCover(id, jpeg); plCovers[id] = System.currentTimeMillis(); playlistTick++; toast = "Playlist picture updated"
                refreshPlaylists()
            } catch (e: Exception) { toast = "Couldn't update the picture: ${e.message}" }
        }
    }
    fun removePlCover(id: String) {
        val s = social ?: return
        viewModelScope.launch { try { s.removePlCover(id); plCovers.remove(id); playlistTick++; toast = "Back to the automatic picture" } catch (e: Exception) { toast = e.message } }
    }

    // ---------- playlists made for you (from what you play most) – they can keep themselves up to date
    var generating by mutableStateOf<String?>(null); private set
    private fun rangeLabel(r: String) = mapOf("week" to "this week", "month" to "this month", "year" to "this year")[r] ?: "of all time"
    fun genName(kind: String, range: String) = when (kind) {
        "top" -> "Your top songs – ${rangeLabel(range)}"
        "similar" -> "More like your ${rangeLabel(range).removePrefix("of ")}"
        "mix" -> "Your ${rangeLabel(range).removePrefix("this ").removePrefix("of ")} mix"
        else -> "New finds for you"
    }

    /** Songs by these artists and artists like them – searches wider until there are [want] songs. */
    private suspend fun similarPool(a: Api, seeds: List<String>, exclude: Set<String>, want: Int): List<Song> = coroutineScope {
        val tried = mutableSetOf<String>()
        val lists = mutableListOf<List<Song>>()
        var next = seeds.map(::mainArtist).filter { it.isNotBlank() }.distinct().take(8)
        var round = 0
        while (next.isNotEmpty() && round < 3) {
            tried += next.map { it.lowercase() }
            val found = next.map { art -> async { art to (withTimeoutOrNull(12_000) { try { a.searchSongs(art, 60) } catch (_: Exception) { null } } ?: emptyList()) } }.awaitAll()
            val related = mutableListOf<String>()
            found.forEach { (art, songs) ->
                lists += songs.filter { it.artist.contains(art, true) }.shuffled()
                lists += songs.filter { !it.artist.contains(art, true) }.shuffled()
                related += songs.map { mainArtist(it.artist) }.filter { it.isNotBlank() && it.lowercase() !in tried }
            }
            val have = lists.flatten().filter { it.id !in exclude }.distinctBy { it.id }.size
            if (have >= want) break
            next = related.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.distinct().take(8)
            round++
        }
        // take turns between the lists so one artist doesn't fill everything
        (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { n -> lists.mapNotNull { it.getOrNull(n) } }
            .filter { it.id !in exclude && keep(it) }.distinctBy { it.id }.distinctBy { it.title.lowercase() + "|" + mainArtist(it.artist).lowercase() }.take(want)
    }

    private suspend fun buildSongs(a: Api, s: Social, kind: String, range: String, size: Int): List<Song> {
        val st = s.myStats(range, 100)
        val top = st.optJSONArray("topSongs")?.let { arr -> (0 until arr.length()).mapNotNull { songFromJson(arr.optJSONObject(it)?.optJSONObject("song")) } } ?: emptyList()
        val artists = (st.optJSONArray("topArtists")?.let { arr -> (0 until arr.length()).map { arr.optJSONObject(it)?.optString("name").orEmpty() } } ?: emptyList())
            .filter { it.isNotBlank() }.ifEmpty { (top + likedSongs).map { mainArtist(it.artist) }.distinct() }
        val known = if (kind == "new") (history().map { it.id } + likedSongs.map { it.id } + top.map { it.id }).toSet() else top.map { it.id }.toSet()
        return when (kind) {
            // your most played first; if you haven't played that many different songs yet, it's filled up with music like them
            "top" -> top.take(size) + (if (top.size < size) similarPool(a, artists, top.map { it.id }.toSet(), size - top.size) else emptyList())
            "mix" -> {
                val t = top.shuffled().take(size / 2)
                val n = similarPool(a, artists, known, size - t.size)
                t.zip(n) { x, y -> listOf(x, y) }.flatten() + t.drop(n.size) + n.drop(t.size)
            }
            else -> similarPool(a, artists, known, size)
        }
    }

    // ---------- "Not interested": songs and artists that are never recommended again
    var hiddenSongs by mutableStateOf(prefs.getStringSet("hidden_songs", emptySet())!!.toSet()); private set
    var hiddenArtists by mutableStateOf(prefs.getStringSet("hidden_artists", emptySet())!!.toSet()); private set
    /** False for songs you said you're not interested in (used by mixes, radio, autoplay and playlists made for you). */
    fun keep(s: Song) = s.id !in hiddenSongs && mainArtist(s.artist).lowercase() !in hiddenArtists && !SmartSearch.isVariant(s.title) // no remixes / sped up / karaoke in mixes
    fun hideSong(s: Song) {
        hiddenSongs = hiddenSongs + s.id; prefs.edit().putStringSet("hidden_songs", hiddenSongs).apply()
        prefs.edit().putString("hidden_names", (JSONObject(prefs.getString("hidden_names", "{}") ?: "{}")).put(s.id, "${s.title} – ${s.artist}").toString()).apply()
        toast = "Got it – you won't get \"${s.title}\" in recommendations"
        if (current?.mediaId == s.id && !isJamGuest && radioSeed != null) controller?.seekToNextMediaItem()
    }
    fun hideArtist(name: String) {
        val a = mainArtist(name).lowercase(); if (a.isBlank()) return
        hiddenArtists = hiddenArtists + a; prefs.edit().putStringSet("hidden_artists", hiddenArtists).apply()
        toast = "Got it – no more ${mainArtist(name)} in recommendations"
    }
    fun hiddenList(): List<Triple<String, String, Boolean>> { // (key, label, isArtist)
        val names = try { JSONObject(prefs.getString("hidden_names", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        return hiddenArtists.map { Triple(it, it.replaceFirstChar { c -> c.uppercase() }, true) } + hiddenSongs.map { Triple(it, names.optString(it, "A song"), false) }
    }
    fun unhide(key: String, artist: Boolean) {
        if (artist) { hiddenArtists = hiddenArtists - key; prefs.edit().putStringSet("hidden_artists", hiddenArtists).apply() }
        else { hiddenSongs = hiddenSongs - key; prefs.edit().putStringSet("hidden_songs", hiddenSongs).apply() }
    }

    // ---------- "Describe your playlist": a playlist from your own words
    private val MOODS = mapOf(
        "chill" to listOf("chill", "lofi", "acoustic"), "relax" to listOf("chill", "acoustic"), "calm" to listOf("chill", "acoustic"),
        "gym" to listOf("workout", "rap", "trap", "drill"), "workout" to listOf("workout", "rap", "trap"), "run" to listOf("workout", "dance"), "training" to listOf("workout", "rap"),
        "party" to listOf("party", "dance", "pop", "afrobeats"), "dance" to listOf("dance", "pop"), "happy" to listOf("pop", "happy", "dance"),
        "sad" to listOf("sad", "love songs", "acoustic"), "love" to listOf("love songs", "r&b"), "romantic" to listOf("love songs", "r&b"),
        "arabic" to listOf("arabic", "fairuz", "umm kulthum", "abdel halim"), "tarab" to listOf("umm kulthum", "abdel halim", "fairuz"),
        "morning" to listOf("fairuz", "acoustic", "chill"), "night" to listOf("r&b", "chill", "lofi"), "late" to listOf("r&b", "lofi"),
        "car" to listOf("hip-hop", "rap"), "drive" to listOf("hip-hop", "rap", "pop"), "focus" to listOf("lofi", "instrumental"), "study" to listOf("lofi", "instrumental"),
        "swedish" to listOf("swedish", "svensk"), "throwback" to listOf("throwback", "2000s", "90s"), "old" to listOf("throwback", "classic"))
    private val STOP = setOf("a", "an", "the", "and", "or", "for", "with", "to", "of", "in", "on", "my", "me", "i", "some", "music", "songs", "song", "playlist", "like", "vibes", "vibe", "mix", "lite", "och", "med", "för", "musik", "låtar")
    fun promptPlaylist(prompt: String, size: Int = 40) {
        val a = api ?: return
        val s = social
        val text = prompt.trim().take(60); if (text.isBlank() || generating != null) return
        generating = text
        viewModelScope.launch {
            try {
                val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}&'-]+")).filter { it.isNotBlank() }
                val terms = (words.flatMap { MOODS[it] ?: emptyList() } + words.filter { it !in STOP && it !in MOODS && it.length > 1 } + listOf(text)).distinct().take(10)
                val lists = coroutineScope { terms.map { t -> async { withTimeoutOrNull(12_000) { try { a.searchSongs(t, 50) } catch (_: Exception) { null } } ?: emptyList() } }.awaitAll() }
                // songs from artists you already like come first, the rest are taken turn by turn from each search
                val liked = (likedSongs + history()).map { mainArtist(it.artist).lowercase() }.toSet()
                val mixed = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { n -> lists.mapNotNull { it.getOrNull(n) } }.filter(::keep)
                    .distinctBy { it.id }.distinctBy { it.title.lowercase() + "|" + mainArtist(it.artist).lowercase() }
                val songs = (mixed.filter { mainArtist(it.artist).lowercase() in liked }.take(size / 3) + mixed).distinctBy { it.id }.take(size)
                if (songs.isEmpty()) { toast = "Couldn't find music for \"$text\" – try other words"; generating = null; return@launch }
                val name = text.replaceFirstChar { it.uppercase() }
                // kept on the friends server: ready in a second, Deezer songs included (they download when played)
                val (id, added) = if (s != null) s.createList(name, songs).id to songs.size else a.createPlaylistSafe(name, songs.map { it.id }) { d, t -> generating = "$name – adding $d/$t" }
                if (id != null && s != null) try { s.uploadPlCover(id, generatedCover(name, "prompt", "", songs)); plCovers[id] = System.currentTimeMillis() } catch (_: Exception) {}
                refreshPlaylists(); toast = "Made \"$name\" ($added songs)"
                if (id != null) go(Screen.PlaylistS(id))
            } catch (e: Exception) { toast = "Couldn't make the playlist: ${e.message}" }
            generating = null
        }
    }

    /** Makes a new playlist, or rebuilds [existingId] with fresh songs and a fresh picture. */
    fun generatePlaylist(kind: String, range: String, size: Int = 50, auto: Boolean = true, existingId: String? = null, quiet: Boolean = false) {
        val a = api ?: return
        val s = social ?: run { toast = "Friends server not connected"; return }
        if (generating != null) return
        val name = genName(kind, range)
        generating = name
        viewModelScope.launch {
            try {
                val songs = buildSongs(a, s, kind, range, size)
                if (songs.isEmpty()) { if (!quiet) toast = "Play some music first – there's nothing to build from yet"; generating = null; return@launch }
                var added = songs.size
                val id = when {
                    existingId != null && isServerList(existingId) -> { s.updateList(existingId, songs = songs); existingId }
                    existingId != null -> { added = a.replacePlaylistSongs(existingId, songs.map { it.id }) { d, t -> generating = "$name – adding $d/$t" }; existingId }
                    else -> s.createList(name, songs).id // kept on the friends server: ready in a second, Deezer songs included
                }
                if (id != null) {
                    try { val jpeg = generatedCover(name, kind, range, songs); s.uploadPlCover(id, jpeg); plCovers[id] = System.currentTimeMillis() } catch (_: Exception) {}
                    try { s.saveGenpl(id, kind, range, size, auto) } catch (_: Exception) {}
                }
                refreshPlaylists(); playlistTick++
                if (!quiet) { toast = "Made \"$name\" ($added songs)"; if (id != null && existingId == null) go(Screen.PlaylistS(id)) }
            } catch (e: Exception) { if (!quiet) toast = "Couldn't make the playlist: ${e.message}" }
            generating = null
        }
    }

    /** Rebuilds "made for you" playlists that are due (a week list daily, a month list every 3 days, a year list weekly). */
    private var genChecked = 0L
    fun refreshGenerated() {
        val s = social ?: return
        if (System.currentTimeMillis() - genChecked < 30 * 60_000) return
        genChecked = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val due = s.genpl().filter { g -> g.auto && now - g.ts > when (g.range) { "week" -> 20 * 3600_000L; "month" -> 3 * 86400_000L; else -> 7 * 86400_000L } }
                for (g in due) {
                    while (generating != null) delay(500)
                    if (playlists.isNotEmpty() && playlists.none { it.id == g.id }) { try { s.removeGenpl(g.id) } catch (_: Exception) {}; continue } // you deleted it
                    generatePlaylist(g.kind, g.range, g.size, true, g.id, quiet = true)
                    delay(800)
                }
            } catch (_: Exception) {}
        }
    }

    /** The picture for a "made for you" playlist: the covers of its songs with the title on top – it changes when the songs do. */
    private suspend fun generatedCover(title: String, kind: String, range: String, songs: List<Song>): ByteArray {
        val a = api
        val ctx = app
        val arts = songs.mapNotNull { it.coverArt }.distinct().take(4)
        val bmps = if (a == null) emptyList() else arts.mapNotNull { id ->
            try { ((ctx.imageLoader.execute(coil.request.ImageRequest.Builder(ctx).data(a.coverUrl(id, 300)).size(300).allowHardware(false).build()) as? coil.request.SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap } catch (_: Exception) { null }
        }
        return withContext(Dispatchers.Default) {
            val size = 600
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val cols = when (kind) { "similar" -> intArrayOf(0xFF0EA5E9.toInt(), 0xFF6366F1.toInt()); "mix" -> intArrayOf(0xFF10B981.toInt(), 0xFF0EA5E9.toInt()); "new" -> intArrayOf(0xFFF59E0B.toInt(), 0xFFE11D48.toInt()); else -> intArrayOf(0xFF7C3AED.toInt(), 0xFFDB2777.toInt()) }
            c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), cols[0], cols[1], android.graphics.Shader.TileMode.CLAMP) })
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            if (bmps.size >= 4) bmps.forEachIndexed { i, b -> c.drawBitmap(b, null, android.graphics.Rect((i % 2) * 300, (i / 2) * 300, (i % 2) * 300 + 300, (i / 2) * 300 + 300), paint) }
            else bmps.firstOrNull()?.let { c.drawBitmap(it, null, android.graphics.Rect(0, 0, size, size), paint) }
            // colour wash + dark fade at the bottom so the title is readable
            c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), (cols[0] and 0x00FFFFFF) or 0x8C000000.toInt(), (cols[1] and 0x00FFFFFF) or 0x8C000000.toInt(), android.graphics.Shader.TileMode.CLAMP) })
            c.drawRect(0f, size * 0.45f, size.toFloat(), size.toFloat(), android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, size * 0.45f, 0f, size.toFloat(), 0x00000000, 0xCC000000.toInt(), android.graphics.Shader.TileMode.CLAMP) })
            val big = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 78f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setShadowLayer(12f, 0f, 4f, 0x88000000.toInt()) }
            val small = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFFFFF.toInt(); textSize = 34f; typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.12f }
            val words = title.split(" ").filter { it.isNotBlank() }
            val (l1, l2) = when (kind) { "top" -> "Your top" to "songs"; "similar" -> "More like" to "your taste"; "mix" -> "Your" to "mix"; "prompt" -> words.take((words.size + 1) / 2).joinToString(" ").take(14) to words.drop((words.size + 1) / 2).joinToString(" ").take(14); else -> "New finds" to "for you" }
            c.drawText(l1, 40f, size - 170f, big); c.drawText(l2, 40f, size - 88f, big)
            c.drawText(when (kind) { "new" -> "UPDATED FOR YOU"; "prompt" -> "MADE FROM YOUR WORDS"; else -> rangeLabel(range).uppercase() }, 44f, size - 40f, small)
            c.drawText("ISSA MUSIC", 44f, 70f, small)
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }
    }

    // Home is cached on the phone so the app opens instantly; it's rebuilt in the background
    // when it's older than a few hours (or when you press Refresh).
    private fun mixJson(m: Mix) = JSONObject().put("title", m.title).put("subtitle", m.subtitle).put("color", m.color).put("seed", m.seed)
        .put("songs", JSONArray().also { a -> m.songs.forEach { a.put(it.toJson()) } })
    private fun mixFrom(o: JSONObject?): Mix? = o?.let {
        val a = it.optJSONArray("songs") ?: JSONArray()
        Mix(it.optString("title"), it.optString("subtitle"), (0 until a.length()).mapNotNull { i -> songFromJson(a.optJSONObject(i)) }, it.optLong("color"), it.optString("seed"))
    }
    private fun saveHome(h: HomeData) = try {
        val o = JSONObject().put("ts", System.currentTimeMillis()).put("user", api?.user ?: "").put("noSeeds", h.noSeeds)
            .put("mixes", JSONArray().also { a -> h.mixes.forEach { a.put(mixJson(it)) } })
            .put("discover", JSONArray().also { a -> h.discover.forEach { a.put(it.toJson()) } })
        h.timeMix?.let { o.put("timeMix", mixJson(it)) }
        prefs.edit().putString("home_cache", o.toString()).apply()
    } catch (_: Exception) {}
    private fun cachedHome(): Pair<HomeData, Long>? {
        val raw = prefs.getString("home_cache", null) ?: return null
        return try { parseHome(raw) } catch (_: Exception) { null }
    }
    private fun parseHome(raw: String): Pair<HomeData, Long>? {
        val o = JSONObject(raw)
        if (o.optString("user") != (api?.user ?: "")) return null
        val mx = o.optJSONArray("mixes") ?: JSONArray(); val d = o.optJSONArray("discover") ?: JSONArray()
        val tm = mixFrom(o.optJSONObject("timeMix"))?.takeIf { it.title == timeSlot().title && it.songs.isNotEmpty() }
        return HomeData((0 until mx.length()).mapNotNull { mixFrom(mx.optJSONObject(it)) }, (0 until d.length()).mapNotNull { songFromJson(d.optJSONObject(it)) },
            o.optBoolean("noSeeds"), tm) to o.optLong("ts")
    }

    fun loadHome(force: Boolean = false) {
        val a = api ?: return
        if (homeLoading || (home != null && !force)) return
        refreshPlaylists(); loadTops()
        if (!force) {
            val c = cachedHome()
            if (c != null) {
                home = c.first
                val fresh = System.currentTimeMillis() - c.second < 4 * 3600_000L
                if (c.first.timeMix == null) viewModelScope.launch { // the time of day changed – only fetch the new time mix
                    val tm = loadTimeMix(a); home?.let { h -> home = h.copy(timeMix = tm); saveHome(home!!) }
                }
                if (fresh) return
            }
        }
        homeLoading = true; homeError = null
        viewModelScope.launch {
            try {
                val timeJob = async { loadTimeMix(a) }
                val st = a.starred()
                likedSongs = st.songs; likedArtistNames = st.artists.map { it.name }
                starred = (st.songs.map { it.id } + st.albums.map { it.id } + st.artists.map { it.id }).toSet()
                val hist = history()
                val counts = (st.songs.map { it.artist } + st.songs.map { it.artist } + st.artists.flatMap { listOf(it.name, it.name, it.name) } + hist.map { it.artist })
                    .map(::mainArtist).filter { it.isNotBlank() }.groupingBy { it }.eachCount()
                val seeds = counts.entries.sortedByDescending { it.value }.take(6).map { it.key }
                val results = seeds.map { seed -> async { seed to withTimeoutOrNull(9_000) { try { a.searchSongs(seed) } catch (_: Exception) { null } } } }.awaitAll()
                val discover = mutableListOf<Song>()
                val mixes = results.mapIndexedNotNull { i, (seed, songsFound) ->
                    if (songsFound == null) return@mapIndexedNotNull null
                    @Suppress("NAME_SHADOWING") val songsFound = songsFound.filter(::keep)
                    val own = songsFound.filter { it.artist.contains(seed, true) }.distinctBy { it.title.lowercase() }.shuffled().take(12)
                    val others = songsFound.filter { !it.artist.contains(seed, true) }.distinctBy { it.title.lowercase() }
                    discover += others.filter { it.id !in starred }
                    val songs = (own + others.take(8) + st.songs.filter { it.artist.contains(seed, true) }.take(5)).distinctBy { it.id }.shuffled()
                    if (songs.isEmpty()) return@mapIndexedNotNull null
                    val names = (listOf(seed) + songs.map { mainArtist(it.artist) }).distinct().filter { it.isNotBlank() }.take(3)
                    Mix("Mix ${i + 1}", names.joinToString(", ") + " and more", songs, MIX_COLORS[i % MIX_COLORS.size], seed)
                }.mapIndexed { i, m -> m.copy(title = "Mix ${i + 1}") }
                val old = home
                val h = HomeData(mixes, discover.distinctBy { it.id }.shuffled().take(20), seeds.isEmpty(), timeJob.await() ?: old?.timeMix)
                if (h.mixes.isNotEmpty() || old == null || force) { home = h; saveHome(h) }
            } catch (e: Exception) { if (home == null) homeError = e.message ?: "Couldn't load home" }
            homeLoading = false
        }
    }

    /** Downloads the covers Home shows first, so they're already on the phone when Home appears. */
    suspend fun prefetchCovers(ctx: Context) {
        val a = api ?: return
        val h = home ?: return
        val loader = coil.Coil.imageLoader(ctx)
        val songs = h.mixes.flatMap { it.songs.take(4) } + listOfNotNull(h.timeMix).flatMap { it.songs.take(4) } + recents.take(8) + h.discover.take(8)
        val urls = (songs.map { a.coverUrl(it.coverArt, 300) } + playlists.take(4).map { a.coverUrl(it.coverArt, 300) }).filterNotNull().distinct().take(16)
        coroutineScope {
            urls.map { u -> async { try { loader.execute(coil.request.ImageRequest.Builder(ctx).data(u).build()) } catch (_: Exception) {} } }.awaitAll()
        }
    }

    private suspend fun loadTimeMix(a: Api): Mix? {
        val slot = timeSlot()
        val found = slot.queries.map { q -> viewModelScope.async { withTimeoutOrNull(9_000) { try { a.searchSongs(q) } catch (_: Exception) { null } } ?: emptyList() } }
            .awaitAll().flatten().filter { s -> slot.match.any { s.artist.contains(it, true) } }
        val songs = found.distinctBy { it.title.lowercase() }.shuffled().take(25)
        return if (songs.isEmpty()) null else Mix(slot.title, slot.subtitle, songs, slot.color, slot.queries.first())
    }

    // ---------- radio (endless queue based on a song or artist)
    var radioSeed by mutableStateOf<String?>(null); private set
    private var radioBusy = false

    private suspend fun radioBatch(a: Api, artist: String, exclude: Set<String>, title: String? = null): List<Song> {
        val seed = mainArtist(artist)
        // same vibe: the artist's Deezer radio + related artists (via the Issa server) – real similar music, not just name matches
        val vibe = try { social?.vibe(seed, title, 30, exclude) ?: emptyList() } catch (_: Exception) { emptyList() }
        val good = vibe.filter { it.id !in exclude && keep(it) }
        if (good.size >= 8) return good
        val rs = try { a.searchSongs(seed) } catch (_: Exception) { return emptyList() }
        val own = rs.filter { it.artist.contains(seed, true) }.shuffled().take(8)
        val others = rs.filter { !it.artist.contains(seed, true) }.shuffled().take(6)
        val related = others.map { mainArtist(it.artist) }.filter { it.isNotBlank() && !it.equals(seed, true) }.distinct().take(2)
        val more = related.flatMap { rel -> try { a.searchSongs(rel).filter { it.artist.contains(rel, true) }.shuffled().take(4) } catch (_: Exception) { emptyList() } }
        return (own + others + more).filter { it.id !in exclude && keep(it) }.distinctBy { it.title.lowercase() }.shuffled()
    }

    fun startRadio(seed: Song? = null, artist: String? = null) {
        val a = api ?: return
        val name = artist ?: seed?.artist ?: return
        toast = "Starting ${mainArtist(name)} radio…"
        viewModelScope.launch {
            val batch = radioBatch(a, name, setOfNotNull(seed?.id))
            val list = listOfNotNull(seed) + batch
            if (list.isEmpty()) { toast = "Couldn't build a radio for this"; return@launch }
            playInternal(list, 0)
            radioSeed = mainArtist(name)
        }
    }

    private fun extendRadio() {
        val a = api ?: return
        val c = controller ?: return
        if (radioSeed == null || radioBusy) return
        if (c.mediaItemCount - c.currentMediaItemIndex > 3) return
        radioBusy = true
        val artist = c.currentMediaItem?.toSong()?.artist ?: radioSeed!!
        viewModelScope.launch {
            val have = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
            val more = radioBatch(a, artist, have).take(12)
            if (more.isNotEmpty()) c.addMediaItems(more.map(::item))
            radioBusy = false
        }
    }

    // ---------- sleep timer
    var sleepUntil by mutableStateOf(0L); private set
    var sleepEndOfTrack by mutableStateOf(false); private set
    fun setSleep(minutes: Int) {
        PlaybackService.sleepEndOfTrack = false; sleepEndOfTrack = false
        if (minutes <= 0) { PlaybackService.sleepAt = 0; sleepUntil = 0; toast = "Sleep timer off"; return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        PlaybackService.sleepAt = at; sleepUntil = at
        toast = "Music stops in $minutes min"
    }
    fun sleepAtEndOfTrack() {
        PlaybackService.sleepAt = 0; sleepUntil = 0
        PlaybackService.sleepEndOfTrack = true; sleepEndOfTrack = true
        toast = "Music stops after this song"
    }
    fun syncSleep() { sleepUntil = PlaybackService.sleepAt; sleepEndOfTrack = PlaybackService.sleepEndOfTrack }

    // ---------- equalizer
    var eqOn by mutableStateOf(prefs.getBoolean("eq_on", false)); private set
    var eqLevels by mutableStateOf(prefs.getString("eq_levels", "")!!.split(",").mapNotNull { it.trim().toIntOrNull() }); private set
    var bass by mutableIntStateOf(prefs.getInt("bass", 0)); private set
    fun setEqEnabled(on: Boolean) { eqOn = on; prefs.edit().putBoolean("eq_on", on).apply() }
    fun setEqBand(i: Int, level: Int, bands: Int) {
        val l = (0 until bands).map { eqLevels.getOrNull(it) ?: 0 }.toMutableList(); l[i] = level
        eqLevels = l; prefs.edit().putString("eq_levels", l.joinToString(",")).apply()
        if (!eqOn) setEqEnabled(true)
    }
    fun applyPreset(levels: List<Int>, info: EqInfo) {
        val scaled = (0 until info.bands).map { b -> (levels.getOrNull((b * levels.size) / info.bands) ?: 0).coerceIn(info.min, info.max) }
        eqLevels = scaled; prefs.edit().putString("eq_levels", scaled.joinToString(",")).apply(); setEqEnabled(true)
    }
    fun chooseBass(v: Int) { bass = v; prefs.edit().putInt("bass", v).apply(); if (!eqOn && v > 0) setEqEnabled(true) }

    // ---------- downloads (offline)
    var downloaded by mutableStateOf(Downloads.list(app)); private set
    val downloadProgress = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    private val downloadQueue = ArrayDeque<Song>()
    private var downloading = false

    fun isDownloaded(id: String) = downloaded.any { it.id == id }

    fun download(songs: List<Song>) {
        val todo = songs.filter { !isDownloaded(it.id) && it.id !in downloadProgress }
        if (todo.isEmpty()) { toast = "Already downloaded"; return }
        todo.forEach { downloadQueue.addLast(it); downloadProgress[it.id] = 0f }
        toast = "Downloading ${todo.size} song${if (todo.size > 1) "s" else ""}…"
        if (!downloading) runDownloads()
    }

    private fun runDownloads() {
        val a = api ?: return
        downloading = true
        viewModelScope.launch {
            var failed = 0
            while (downloadQueue.isNotEmpty()) {
                val s = downloadQueue.removeFirst()
                try {
                    val url = a.url("stream", mapOf("id" to s.id))
                    Downloads.download(app, url, s) { p -> viewModelScope.launch(Dispatchers.Main) { downloadProgress[s.id] = p } }
                } catch (_: Exception) { failed++ }
                downloadProgress.remove(s.id)
                downloaded = Downloads.list(app)
            }
            downloading = false
            toast = if (failed == 0) "Downloads finished" else "Downloads finished, $failed failed"
        }
    }

    fun removeDownload(id: String) { Downloads.remove(app, id); downloaded = Downloads.list(app) }
    fun clearDownloads() { Downloads.clear(app); downloaded = emptyList(); toast = "All downloads removed" }
    fun downloadBytes() = Downloads.totalBytes(app)

    // ---------- lyrics
    var lyrics by mutableStateOf<Lyrics?>(null); private set
    var lyricsFor by mutableStateOf<String?>(null); private set
    var lyricsLoading by mutableStateOf(false); private set
    fun loadLyrics(s: Song) {
        if (lyricsFor == s.id) return
        lyricsFor = s.id; lyrics = null; lyricsLoading = true
        viewModelScope.launch {
            val l = LyricsClient.fetch(s)
            if (lyricsFor == s.id) { lyrics = l; lyricsLoading = false }
        }
    }

    // ---------- sharing to stories
    fun shareSong(ctx: Context, s: Song) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                val uri = ShareCard.make(ctx, a.coverUrl(s.coverArt, 900), "Now playing", listOf(Triple(s.title, 64f, true), Triple(s.artist, 44f, false)), accentColor)
                ShareCard.share(ctx, uri, "${s.title} – ${s.artist} 🎧")
            } catch (e: Exception) { toast = "Couldn't create the image: ${e.message}" }
        }
    }

    fun shareWrapped(ctx: Context, w: Wrapped) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                val top = w.topSongs.firstOrNull()
                val lines = mutableListOf(Triple("${w.minutes} minutes listened", 60f, true))
                w.topArtists.take(3).forEachIndexed { i, (n, _) -> lines += Triple("${i + 1}. $n", 44f, false) }
                if (top != null) lines += Triple("Top song: ${top.song.title}", 40f, false)
                val uri = ShareCard.make(ctx, a.coverUrl(top?.song?.coverArt, 900), "${w.display}'s ${w.year} Wrapped", lines, accentColor)
                ShareCard.share(ctx, uri, "My ${w.year} on Issa Music")
            } catch (e: Exception) { toast = "Couldn't create the image: ${e.message}" }
        }
    }

    // ---------- friends
    var myProfile by mutableStateOf<Friend?>(null)
    var friends by mutableStateOf<List<Friend>>(emptyList())
    var inbox by mutableStateOf<List<Rec>>(emptyList())
    var socialError by mutableStateOf<String?>(null)
    val unseen: Int get() = inbox.count { !it.seen }
    var sendSong by mutableStateOf<Song?>(null)
    var sendPlaylist by mutableStateOf<Playlist?>(null)
    var tops by mutableStateOf<Map<String, List<TopItem>>>(emptyMap())

    var reactions by mutableStateOf<List<Reaction>>(emptyList())
    var jamInvites by mutableStateOf<List<Jam>>(emptyList())
    var collabs by mutableStateOf<List<Collab>>(emptyList())

    fun refreshSocial() {
        val s = social ?: return
        viewModelScope.launch {
            try {
                val all = s.users()
                myProfile = all.firstOrNull { it.name == s.me }
                friends = all.filter { it.name != s.me }
                inbox = s.inbox()
                socialError = null
                // reactions are turned off
                try { jamInvites = s.jams().filter { it.host != s.me } } catch (_: Exception) {}
                try { collabs = s.collabs() } catch (_: Exception) {}
                notifyNew()
                checkUpdate()
            } catch (e: Exception) { socialError = e.message ?: "Friends server not reachable" }
        }
    }

    /** Shows Android notifications for things that arrived since last time. */
    private fun notifyNew() {
        val seen = prefs.getString("notified", "")!!.split(",").filter { it.isNotBlank() }.toMutableSet()
        val first = !prefs.contains("notified")
        val fresh = mutableListOf<Triple<String, String, String>>()
        inbox.filter { !it.seen && opt.notifRecs }.forEach { r ->
            val k = "i${r.id}"; if (k !in seen) { seen += k; fresh += Triple(k, "${r.fromDisplay} sent you ${if (r.isPlaylist) "a playlist" else "a song"}", "${r.song.title}${if (r.message.isNotBlank()) " – “${r.message}”" else ""}") }
        }
        reactions.filter { !it.seen && opt.notifReactions }.forEach { r ->
            val k = "r${r.id}"; if (k !in seen) { seen += k; fresh += Triple(k, "${r.fromDisplay} reacted ${r.emoji}", r.song?.let { "to ${it.title}" } ?: "to what you're playing") }
        }
        jamInvites.filter { jamHost != it.host && opt.notifJam }.forEach { j ->
            val k = "j${j.host}${j.stateTs / 3_600_000}"; if (k !in seen) { seen += k; fresh += Triple(k, "${j.hostDisplay} started a Jam", "Tap to listen together") }
        }
        prefs.edit().putString("notified", seen.toList().takeLast(300).joinToString(",")).apply()
        if (!first) fresh.take(3).forEach { (k, t, b) -> Notify.show(app, k.hashCode(), t, b) }
    }

    fun react(f: Friend, emoji: String) {
        val s = social ?: return
        viewModelScope.launch {
            try { s.react(f.name, emoji, f.song); toast = "Sent $emoji to ${f.display}" } catch (e: Exception) { toast = "Couldn't send: ${e.message}" }
        }
    }

    fun markReactionsSeen() {
        val s = social ?: return
        if (reactions.none { !it.seen }) return
        viewModelScope.launch { try { s.reactionsSeen(); reactions = reactions.map { it.copy(seen = true) } } catch (_: Exception) {} }
    }

    // ---------- collaborative playlists
    fun createCollab(name: String, members: List<String>, first: Song? = null) {
        val s = social ?: return
        viewModelScope.launch {
            try {
                var c = s.createCollab(name, members)
                if (first != null) c = s.collabAdd(c.id, first)
                collabs = listOf(c) + collabs.filter { it.id != c.id }; toast = "Created \"$name\""
            } catch (e: Exception) { toast = "Couldn't create: ${e.message}" }
        }
    }
    fun collabAdd(c: Collab, song: Song) {
        val s = social ?: return
        viewModelScope.launch {
            try { val n = s.collabAdd(c.id, song); collabs = collabs.map { if (it.id == n.id) n else it }; toast = "Added to ${c.name}" }
            catch (e: Exception) { toast = "Couldn't add: ${e.message}" }
        }
    }
    fun collabRemove(c: Collab, index: Int) {
        val s = social ?: return
        viewModelScope.launch { try { val n = s.collabRemove(c.id, index); collabs = collabs.map { if (it.id == n.id) n else it } } catch (e: Exception) { toast = e.message } }
    }
    fun collabRename(c: Collab, name: String) {
        val s = social ?: return
        viewModelScope.launch {
            try { val n = s.collabRename(c.id, name); collabs = collabs.map { if (it.id == n.id) n else it }; toast = "Renamed to \"$name\"" }
            catch (e: Exception) { toast = "Couldn't rename: ${e.message}" }
        }
    }

    // ---------- playlist menu (rename / delete)
    var playlistMenu by mutableStateOf<Playlist?>(null)
    var collabMenu by mutableStateOf<Collab?>(null)
    var playlistTick by mutableIntStateOf(0); private set   // bump to make open playlist pages reload

    /** Playlists kept on the friends server have ids starting with "il-". */
    fun isServerList(id: String) = id.startsWith("il-")
    /** Songs of any playlist (server or music server). */
    suspend fun playlistSongs(id: String): Pair<Playlist, List<Song>> = if (isServerList(id)) social!!.list(id) else api!!.playlist(id)

    fun renamePlaylist(pl: Playlist, name: String) {
        val a = api ?: return
        viewModelScope.launch {
            try { if (isServerList(pl.id)) social?.updateList(pl.id, name = name) else a.renamePlaylist(pl.id, name); toast = "Renamed to \"$name\""; refreshPlaylists(); playlistTick++ }
            catch (e: Exception) { toast = "Couldn't rename: ${e.message}" }
        }
    }

    fun deletePlaylist(pl: Playlist) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                if (isServerList(pl.id)) social?.deleteList(pl.id) else a.deletePlaylist(pl.id); toast = "Deleted \"${pl.name}\""; refreshPlaylists()
                if (stack.lastOrNull() == Screen.PlaylistS(pl.id)) back()
            } catch (e: Exception) { toast = "Couldn't delete: ${e.message}" }
        }
    }

    fun collabDelete(c: Collab) {
        val s = social ?: return
        viewModelScope.launch { try { s.collabDelete(c.id); collabs = collabs.filter { it.id != c.id }; back() } catch (e: Exception) { toast = e.message } }
    }

    // ---------- Jam (listen together)
    var jamHost by mutableStateOf<String?>(null); private set     // host user name of the Jam we're in
    var jam by mutableStateOf<Jam?>(null); private set
    val inJam get() = jamHost != null
    val isJamHost get() = jamHost != null && jamHost == social?.me
    val isJamGuest get() = jamHost != null && jamHost != social?.me
    private var jamJob: kotlinx.coroutines.Job? = null

    private fun queueSongs(): Triple<List<Song>, Int, Long> {
        social?.jamFade = fadeSec
        val c = controller ?: return Triple(emptyList(), 0, 0)
        return Triple((0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSong() }, c.currentMediaItemIndex.coerceAtLeast(0), c.currentPosition)
    }

    fun startJam(invite: List<String>) {
        val s = social ?: return
        viewModelScope.launch {
            try {
                val (q, i, pos) = queueSongs()
                jamFeedSeen = Long.MAX_VALUE; applyJam(s.jamStart(invite, q, i, pos, isPlaying)); jamHost = s.me
                toast = "Jam started. Your friends can join now."
                runHostLoop()
            } catch (e: Exception) { toast = "Couldn't start Jam: ${e.message}" }
        }
    }

    private fun runHostLoop() {
        val s = social ?: return
        jamJob?.cancel()
        jamJob = viewModelScope.launch {
            while (isActive && isJamHost) {
                try {
                    val (q, i, pos) = queueSongs()
                    applyJam(s.jamState(q, i, pos, isPlaying))
                    if ((jam?.pendingCount ?: 0) > 0) {
                        val added = s.jamTake()
                        added.forEach { song -> enqueueQuiet(song) }
                        if (added.isNotEmpty()) toast = "${added.size} song${if (added.size > 1) "s" else ""} added to the Jam"
                    }
                } catch (_: Exception) {}
                delay(2500)
            }
        }
    }

    private var jamPushPending = false
    private fun pushJamSoon() {
        val s = social ?: return
        if (!isJamHost || jamPushPending) return
        jamPushPending = true
        viewModelScope.launch {
            delay(400); jamPushPending = false
            try { val (q, i, pos) = queueSongs(); applyJam(s.jamState(q, i, pos, isPlaying)) } catch (_: Exception) {}
        }
    }

    fun joinJam(host: String) {
        val s = social ?: return
        viewModelScope.launch {
            try {
                jamFeedSeen = Long.MAX_VALUE; applyJam(s.jamJoin(host)); jamHost = host; PlaybackService.jamGuest = true; jamSettleUntil = 0
                toast = "You joined ${jam?.hostDisplay}'s Jam"
                runGuestLoop()
            } catch (e: Exception) { toast = "Couldn't join: ${e.message}"; jamInvites = jamInvites.filter { it.host != host } }
        }
    }

    private fun runGuestLoop() {
        val s = social ?: return
        val host = jamHost ?: return
        jamJob?.cancel()
        jamJob = viewModelScope.launch {
            while (isActive && jamHost == host) {
                try {
                    val t0 = System.currentTimeMillis()
                    val j = s.jamGet(host)
                    val rtt = System.currentTimeMillis() - t0
                    applyJam(j)
                    followHost(j, rtt)
                } catch (e: Exception) {
                    if (e.message?.contains("ended", true) == true) { toast = "The Jam has ended"; jamHost = null; jam = null; endGuest(); break }
                }
                delay(2000)
            }
        }
    }

    /** After a jump the song needs a moment to load – don't correct again until then (that's what made joining laggy). */
    private var jamSettleUntil = 0L
    private fun setSpeed(c: MediaController, v: Float) { if (c.playbackParameters.speed != v) c.setPlaybackSpeed(v) }

    private fun followHost(j: Jam, rtt: Long) {
        val c = controller ?: return
        if (PlaybackService.crossfading) return // the song change is blending – check again when it's done
        if (j.queue.isEmpty()) { if (c.isPlaying) c.pause(); return }
        val now = System.currentTimeMillis()
        PlaybackService.jamFadeSec = j.fade // fade like the host, so songs change together
        val nearEnd = maxOf(5_000L, (if (j.fade >= 0) j.fade else fadeSec) * 1000L + 3_000) // with fade, songs change a few seconds early
        val idx = j.index.coerceIn(0, j.queue.size - 1)
        val expected = j.pos + (if (j.playing) (j.serverNow - j.stateTs).coerceAtLeast(0) + rtt / 2 else 0)
        val sameQueue = c.mediaItemCount == j.queue.size && (0 until c.mediaItemCount).all { c.getMediaItemAt(it).mediaId == j.queue[it].id }
        val jump = { i: Int, at: Long -> setSpeed(c, 1f); jamSettleUntil = now + 3_000; c.seekTo(i, at) }
        if (!sameQueue && c.mediaItemCount > 0 && c.currentMediaItem?.mediaId == j.queue[idx].id) {
            // songs were added or moved, but the same song is playing – update the list around it without touching playback
            c.shuffleModeEnabled = false
            val cur = c.currentMediaItemIndex
            if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
            if (idx + 1 < j.queue.size) c.addMediaItems(j.queue.drop(idx + 1).map(::item))
            if (cur > 0) c.removeMediaItems(0, cur)
            if (idx > 0) c.addMediaItems(0, j.queue.take(idx).map(::item))
        } else if (!sameQueue) {
            c.shuffleModeEnabled = false; setSpeed(c, 1f); jamSettleUntil = now + 3_000
            c.setMediaItems(j.queue.map(::item), idx, expected); c.prepare()
        } else if (c.currentMediaItemIndex != idx) {
            // at a song change one of us is a moment early – let the song finish/start by itself instead of jumping
            val hostNearEnd = j.queue[idx].duration > 0 && expected > j.queue[idx].duration * 1000L - nearEnd
            val meNearEnd = c.duration > 0 && c.duration - c.currentPosition < nearEnd
            val ok = (c.currentMediaItemIndex == idx + 1 && hostNearEnd) || (c.currentMediaItemIndex == idx - 1 && meNearEnd)
            if (!ok) jump(idx, expected)
        } else if (j.playing && now > jamSettleUntil && c.playbackState == Player.STATE_READY) {
            val drift = c.currentPosition - expected // > 0 = we're ahead
            val ad = kotlin.math.abs(drift)
            when {
                ad > 4_000 -> jump(idx, expected)
                ad > 300 -> setSpeed(c, if (drift > 0) (if (ad > 1_500) 0.9f else 0.95f) else (if (ad > 1_500) 1.1f else 1.05f)) // catch up smoothly instead of jumping
                else -> setSpeed(c, 1f)
            }
        }
        if (j.playing && !c.isPlaying) c.play()
        if (!j.playing && c.isPlaying) c.pause()
    }

    fun leaveJam() {
        val s = social ?: return
        val host = jamHost ?: return
        val wasHost = isJamHost
        jamJob?.cancel(); jamHost = null; jam = null; endGuest(); skipAsk = null
        viewModelScope.launch {
            try { if (wasHost) s.jamEnd() else { s.jamLeave(host); controller?.pause() } } catch (_: Exception) {}
            toast = if (wasHost) "Jam ended" else "You left the Jam"
        }
    }

    // ---------- Jam: "please skip this song" (guests ask, the host decides)
    data class SkipAsk(val songId: String, val title: String, val names: List<String>)
    var skipAsk by mutableStateOf<SkipAsk?>(null)
    private val skipVotes = mutableMapOf<String, MutableSet<String>>() // song id → who asked
    private val askedSkip = mutableSetOf<String>()                     // songs I (a guest) already asked about
    private var jamFeedSeen = 0L // events up to this id are handled (MAX = "take the current feed as seen")
    private fun applyJam(j: Jam) {
        jam = j
        if (jamFeedSeen == Long.MAX_VALUE) { jamFeedSeen = j.feed.maxOfOrNull { it.id } ?: 0L; return }
        val fresh = j.feed.filter { it.id > jamFeedSeen }
        if (fresh.isEmpty()) return
        jamFeedSeen = fresh.maxOf { it.id }
        if (!isJamHost) return
        val me = social?.me
        fresh.filter { it.type == "skip" && it.from != me }.forEach { e ->
            val who = skipVotes.getOrPut(e.songId) { mutableSetOf() }; who += e.fromDisplay
            val cur = current?.toSong() ?: return@forEach
            if (cur.id != e.songId) return@forEach
            val guests = j.members.filter { it != me }.size.coerceAtLeast(1)
            if (who.size >= guests && guests > 1) { // everyone listening wants a new song
                skipAsk = null; controller?.seekToNextMediaItem(); toast = "Skipped – everyone wanted a new song"
            } else skipAsk = SkipAsk(cur.id, cur.title, who.toList())
        }
    }
    /** Host: accept or ignore a skip request. */
    fun answerSkip(skip: Boolean) {
        val a = skipAsk ?: return
        skipAsk = null
        if (skip && current?.mediaId == a.songId) controller?.seekToNextMediaItem()
    }
    val canAskSkip get() = isJamGuest && current?.mediaId?.let { it !in askedSkip } == true
    /** Guest: ask the host to skip the song that's playing. */
    fun askSkip() {
        val s = social ?: return
        val host = jamHost ?: return
        val id = current?.mediaId ?: return
        if (!askedSkip.add(id)) return
        viewModelScope.launch {
            try { applyJam(s.jamSkip(host)); toast = "Asked ${jam?.hostDisplay ?: "the host"} to skip" }
            catch (e: Exception) { askedSkip.remove(id); toast = e.message ?: "Couldn't ask" }
        }
    }

    private fun endGuest() { PlaybackService.jamGuest = false; PlaybackService.jamFadeSec = -1; controller?.let { setSpeed(it, 1f) } }

    fun addToJam(song: Song) {
        val s = social ?: return
        val host = jamHost ?: return
        if (isJamHost) { enqueue(song); return }
        viewModelScope.launch { try { s.jamAdd(host, song); toast = "Added to the Jam queue" } catch (e: Exception) { toast = "Couldn't add: ${e.message}" } }
    }

    // ---------- app updates (published on the friends server's /admin page)
    var update by mutableStateOf<AppUpdate?>(null); private set
    var updateProgress by mutableStateOf<Float?>(null); private set
    var updateChecked by mutableStateOf(false); private set
    private var lastUpdateCheck = 0L

    fun checkUpdate(force: Boolean = false) {
        val s = social ?: return
        if (!force && System.currentTimeMillis() - lastUpdateCheck < 20 * 60_000) return
        lastUpdateCheck = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                val u = s.latestApp()
                update = u?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                updateChecked = true
                if (force) toast = if (update != null) "Version ${update!!.versionName} is available" else "You have the latest version"
            } catch (e: Exception) { if (force) toast = "Couldn't check for updates: ${e.message}" }
        }
    }

    fun installUpdate(ctx: Context) {
        val s = social ?: return
        val u = update ?: return
        if (updateProgress != null) return
        if (android.os.Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            toast = "Allow Issa Music to install updates, then tap Install again"
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        updateProgress = 0f
        prefs.edit().putString("notes_${u.versionCode}", u.notes).apply()
        viewModelScope.launch {
            try {
                val dir = java.io.File(app.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = java.io.File(dir, "IssaMusic-${u.versionName}.apk")
                s.downloadApp(f) { p -> viewModelScope.launch(Dispatchers.Main) { updateProgress = p } }
                val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", f)
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { toast = "Update failed: ${e.message}" }
            updateProgress = null
        }
    }

    // ---------- "What's new" after an update
    var whatsNew by mutableStateOf<String?>(null); private set
    private fun checkWhatsNew() {
        val seen = prefs.getInt("seen_version", 0)
        if (seen >= BuildConfig.VERSION_CODE) return
        val saved = prefs.getString("notes_${BuildConfig.VERSION_CODE}", null)
        if (!saved.isNullOrBlank()) { whatsNew = saved; return }
        val s = social ?: return
        viewModelScope.launch {
            val u = try { s.latestApp() } catch (_: Exception) { null }
            whatsNew = if (u != null && u.versionCode == BuildConfig.VERSION_CODE && u.notes.isNotBlank()) u.notes
                else if (seen > 0) "New features and fixes." else null
            if (whatsNew == null) prefs.edit().putInt("seen_version", BuildConfig.VERSION_CODE).apply()
        }
    }
    fun dismissWhatsNew() { whatsNew = null; prefs.edit().putInt("seen_version", BuildConfig.VERSION_CODE).apply() }

    // ---------- weekly / wrapped
    var weekly by mutableStateOf<List<WeekStat>>(emptyList()); private set
    var weeklyLoading by mutableStateOf(false); private set
    fun loadWeekly() {
        val s = social ?: return
        weeklyLoading = true
        viewModelScope.launch { try { weekly = s.weekly() } catch (e: Exception) { toast = "Couldn't load your week: ${e.message}" }; weeklyLoading = false }
    }
    var wrapped by mutableStateOf<Wrapped?>(null); private set
    fun loadWrapped(year: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)) {
        val s = social ?: return
        viewModelScope.launch { try { wrapped = s.wrapped(year) } catch (e: Exception) { toast = "Couldn't load Wrapped: ${e.message}" } }
    }

    fun loadTops() {
        val s = social ?: return
        viewModelScope.launch {
            try {
                val names = listOf(s.me) + s.users().map { it.name }.filter { it != s.me }
                tops = names.map { n -> async { n to (try { s.top(n) } catch (_: Exception) { emptyList() }) } }.awaitAll().toMap()
            } catch (_: Exception) {}
        }
    }

    fun markInboxSeen() {
        val s = social ?: return
        if (unseen == 0) return
        viewModelScope.launch { try { s.markSeen(); inbox = inbox.map { it.copy(seen = true) } } catch (_: Exception) {} }
    }

    fun recommend(to: Friend, song: Song, message: String) {
        val s = social ?: return
        viewModelScope.launch {
            try { s.recommend(to.name, song, message); toast = "Sent to ${to.display}" } catch (e: Exception) { toast = "Couldn't send: ${e.message}" }
        }
    }

    fun recommendPlaylist(to: Friend, pl: Playlist, message: String) {
        val s = social ?: return
        val a = api ?: return
        viewModelScope.launch {
            try {
                if (!pl.public && pl.owner.equals(a.user, true)) { if (isServerList(pl.id)) s.updateList(pl.id, public = true) else a.setPlaylistPublic(pl.id, true) }
                s.recommendPlaylist(to.name, pl, message); toast = "Shared \"${pl.name}\" with ${to.display}"; refreshPlaylists()
            } catch (e: Exception) { toast = "Couldn't share: ${e.message}" }
        }
    }

    fun setPlaylistPublic(pl: Playlist, on: Boolean, after: () -> Unit) {
        val a = api ?: return
        viewModelScope.launch {
            try { if (isServerList(pl.id)) social?.updateList(pl.id, public = on) else a.setPlaylistPublic(pl.id, on); toast = if (on) "Friends can now see \"${pl.name}\"" else "\"${pl.name}\" is private"; refreshPlaylists(); after() }
            catch (e: Exception) { toast = "Couldn't change sharing: ${e.message}" }
        }
    }

    fun displayName(): String = myProfile?.display ?: api?.user ?: ""
    fun nameOf(user: String): String = if (user == social?.me) displayName() else friends.firstOrNull { it.name == user }?.display ?: user

    fun setDisplayName(name: String) {
        val s = social ?: return
        viewModelScope.launch { try { s.setDisplay(name); toast = "Name saved"; refreshSocial() } catch (e: Exception) { toast = "Couldn't save: ${e.message}" } }
    }

    /** Picture picked in Settings; the cropper is shown while this is set. */
    var cropUri by mutableStateOf<Uri?>(null)

    fun uploadAvatar(bmp: Bitmap) {
        val s = social ?: run { toast = "Friends server not set up"; return }
        cropUri = null
        viewModelScope.launch {
            try {
                val jpeg = withContext(Dispatchers.Default) { ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray() }
                s.uploadAvatar(jpeg); toast = "Profile picture updated"; refreshSocial()
            } catch (e: Exception) { toast = "Couldn't update picture: ${e.message}" }
        }
    }

    fun setAvatar(uri: Uri) {
        val s = social ?: run { toast = "Friends server not set up"; return }
        viewModelScope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) { squareJpeg(uri, 400) }
                s.uploadAvatar(jpeg); toast = "Profile picture updated"; refreshSocial()
            } catch (e: Exception) { toast = "Couldn't update picture: ${e.message}" }
        }
    }

    private fun squareJpeg(uri: Uri, size: Int): ByteArray {
        val cr = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        val bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw Exception("Couldn't read the image")
        val side = minOf(bmp.width, bmp.height)
        val crop = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(crop, size, size, true)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    // ---------- uploads
    val uploads = mutableStateListOf<UploadItem>()
    var uploading by mutableStateOf(false)

    fun uploadFiles(uris: List<Uri>) {
        val s = social ?: run { toast = "Friends server not set up"; return }
        if (uris.isEmpty()) return
        uploading = true
        viewModelScope.launch {
            var anyScan = false
            for (uri in uris) {
                val (name, size) = withContext(Dispatchers.IO) { fileInfo(uri) }
                val idx = uploads.size
                uploads.add(UploadItem(name, 0f, "Uploading…"))
                try {
                    val scan = s.upload(name, size, { app.contentResolver.openInputStream(uri)!! }) { p ->
                        viewModelScope.launch(Dispatchers.Main) { if (idx < uploads.size) uploads[idx] = uploads[idx].copy(progress = p) }
                    }
                    anyScan = anyScan || scan
                    uploads[idx] = uploads[idx].copy(progress = 1f, status = "Uploaded", done = true)
                } catch (e: Exception) {
                    uploads[idx] = uploads[idx].copy(status = e.message ?: "Failed", failed = true)
                }
            }
            uploading = false
            if (!anyScan) try { api?.startScan(); anyScan = true } catch (_: Exception) {}
            toast = if (anyScan) "Upload done. Songs show up in a minute." else "Upload done. Songs show up after the next library scan."
        }
    }

    private fun fileInfo(uri: Uri): Pair<String, Long> {
        var name = "upload.mp3"; var size = -1L
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return name to size
    }

    // ---------- menus
    var menuSong by mutableStateOf<Song?>(null)
    var pickerSong by mutableStateOf<Song?>(null)

    // ---------- player
    private var controller: MediaController? = null
    var current by mutableStateOf<MediaItem?>(null)
    var currentIndex by mutableIntStateOf(0)
    var isPlaying by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var queue by mutableStateOf<List<MediaItem>>(emptyList())
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(0)
    var showFull by mutableStateOf(false)
    var showJam by mutableStateOf(false)
    var showSleep by mutableStateOf(false)
    var showLyrics by mutableStateOf(false)
    var showPlayerMore by mutableStateOf(false)
    val player: Player? get() = controller

    private var trackStart = 0L
    private var recordedFor = 0L

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { sync(player, events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (skipAsk?.songId != mediaItem?.mediaId) skipAsk = null
            trackStart = System.currentTimeMillis()
            mediaItem?.let { addHistory(it.toSong()) }
            extendRadio()
            pushJamSoon()
            pushResume(force = true)
        }
        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) autoFill(ended = true) }
        override fun onIsPlayingChanged(isPlaying: Boolean) { pushJamSoon(); pushResume(force = true); if (isPlaying) resumeOffer = null }
        override fun onPlayerError(error: PlaybackException) {
            lastError = "${error.errorCodeName}: ${error.message ?: ""}"
            toast = if (error.errorCode in 2000..2999) "Connection problem – trying again…" else "Couldn't play this song – skipping"
        }
    }

    private fun sync(p: Player, queueChanged: Boolean = true) {
        current = p.currentMediaItem
        currentIndex = p.currentMediaItemIndex
        isPlaying = p.isPlaying
        buffering = p.playbackState == Player.STATE_BUFFERING
        if (queueChanged || queue.size != p.mediaItemCount) queue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) } // only rebuild when the list changed – keeps the screen smooth
        shuffle = p.shuffleModeEnabled
        repeat = when (p.repeatMode) { Player.REPEAT_MODE_ALL -> 1; Player.REPEAT_MODE_ONE -> 2; else -> 0 }
        syncSleep()
        reportNowPlaying()
    }

    private var lastNp: String? = null
    private fun reportNowPlaying(force: Boolean = false) {
        val s = social ?: return
        if (!shareActivity) return
        val song = current?.toSong()
        val key = "${song?.id}:$isPlaying"
        if (!force && key == lastNp) return
        lastNp = key
        viewModelScope.launch { try { s.nowPlaying(song, isPlaying) } catch (_: Exception) {} }
    }

    init {
        // the car's Like button and the app share the same liked songs
        viewModelScope.launch { androidx.compose.runtime.snapshotFlow { starred }.collect { PlaybackService.likedIds = it; PlaybackService.refreshCarButtons?.invoke() } }
        PlaybackService.onLikedInCar = { id, on -> viewModelScope.launch { starred = if (on) starred + id else starred - id; toast = if (on) "Added to Liked Songs" else "Removed from Liked Songs" } }
        val b = prefs.getString("base", null); val u = prefs.getString("user", null); val p = prefs.getString("pass", null)
        if (b != null && u != null && p != null) {
            api = Api(b, u, p).also { it.maxBitRate = quality }; social = Social(socialBase(), u, p)
            refreshStarred()
            // friends, Jam, inbox etc. come a moment later so Home and its covers get the connection first
            viewModelScope.launch { delay(1_500); refreshSocial(); syncSearches(); checkWhatsNew(); delay(15_000); refreshGenerated() }
        }
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        f.addListener({
            try { controller = f.get().also { it.addListener(listener); sync(it); restoreQueue(it); checkResume() } } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(5_000); ticks++
                val c = controller
                if (c != null && c.isPlaying && trackStart != 0L && recordedFor != trackStart && c.currentPosition >= 30_000) {
                    recordedFor = trackStart
                    val song = c.currentMediaItem?.toSong()
                    val s = social
                    if (song != null && s != null && shareActivity) launch { try { s.play(song) } catch (_: Exception) {} }
                }
                if (ticks % 12 == 0 && isPlaying) reportNowPlaying(force = true)
                if (ticks % 3 == 0 && isPlaying) pushResume()
                // autoplay: when the last song in the queue has under 30 s left, add similar music
                if (c != null && c.isPlaying && c.currentMediaItemIndex >= c.mediaItemCount - 1 && c.duration > 0 && c.duration - c.currentPosition < 30_000) autoFill()
            }
        }
    }

    fun savedBase() = prefs.getString("base", null) ?: "http://192.168.0.14:5274"
    fun savedUser() = prefs.getString("user", null) ?: ""

    fun login(base: String, user: String, pass: String) {
        loggingIn = true; loginError = null
        val a = Api(base, user.trim(), pass).also { it.maxBitRate = quality }
        viewModelScope.launch {
            try {
                a.ping()
                prefs.edit().putString("base", a.base).putString("user", a.user).putString("pass", a.pass).apply()
                api = a; social = Social(socialBase(), a.user, a.pass)
                stack.clear(); stack.add(Screen.Home); home = null; prefs.edit().remove("home_cache").apply()
                refreshStarred(); refreshSocial()
            } catch (e: Exception) { loginError = "Login failed: ${e.message}" }
            loggingIn = false
        }
    }

    fun logout() {
        if (inJam) leaveJam()
        controller?.run { stop(); clearMediaItems() }
        prefs.edit().remove("pass").apply()
        api = null; social = null; result = null; query = ""; starred = emptySet(); showFull = false
        home = null; prefs.edit().remove("home_cache").apply(); friends = emptyList(); inbox = emptyList(); myProfile = null; tops = emptyMap(); playlists = emptyList()
    }

    private fun item(s: Song): MediaItem {
        val a = api!!
        a.maxBitRate = currentBitrate(app, prefs); a.maxCover = if (opt.hiresCovers && !opt.dataSaver) 600 else 300
        val extras = Bundle().apply {
            putString("albumId", s.albumId); putString("artistId", s.artistId)
            putString("coverArt", s.coverArt); putInt("duration", s.duration); putString("genre", s.genre)
        }
        val md = MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album)
            .setArtworkUri(a.coverUrl(s.coverArt, 512)?.let { Uri.parse(it) }).setExtras(extras).build()
        // Downloaded songs play from the phone, everything else streams from the server.
        val local = Downloads.file(app, s.id)
        val uri = if (local != null) Uri.fromFile(local) else Uri.parse(a.streamUrl(s.id))
        return MediaItem.Builder().setMediaId(s.id).setUri(uri).setMediaMetadata(md)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build()).build()
    }

    private fun guestBlocked(): Boolean {
        if (isJamGuest) { toast = "${jam?.hostDisplay ?: "The host"} controls the Jam. Songs you pick are added to the queue."; return true }
        return false
    }

    /** A song picked in search: play it, then keep going with music of the same vibe (not the other search results). */
    fun playFromSearch(s: Song) {
        if (isJamGuest) { addToJam(s); return }
        val a = api ?: return
        radioSeed = null
        playInternal(listOf(s), 0)
        radioSeed = mainArtist(s.artist) // keeps extending with similar music as you listen
        viewModelScope.launch {
            val more = radioBatch(a, s.artist, setOf(s.id), s.title).take(25)
            val c = controller ?: return@launch
            if (more.isNotEmpty() && c.currentMediaItem?.mediaId == s.id) c.addMediaItems(more.map(::item))
        }
    }

    fun play(list: List<Song>, index: Int, shuffled: Boolean = false) {
        if (isJamGuest) { list.getOrNull(index)?.let { addToJam(it) }; return }
        radioSeed = null
        playInternal(list, index, shuffled)
    }

    private fun playInternal(list: List<Song>, index: Int, shuffled: Boolean = false) {
        val c = controller ?: run { toast = "Player is starting, try again"; return }
        if (api == null || list.isEmpty()) return
        c.shuffleModeEnabled = shuffled
        c.setMediaItems(list.map(::item), if (shuffled) list.indices.random() else index, 0L)
        c.prepare(); c.play()
    }

    fun playNext(s: Song) {
        if (isJamGuest) { addToJam(s); return }
        val c = controller ?: return
        if (c.mediaItemCount == 0) { play(listOf(s), 0); return }
        c.addMediaItem(c.currentMediaItemIndex + 1, item(s)); toast = "Playing next"
    }

    fun enqueue(s: Song) {
        if (isJamGuest) { addToJam(s); return }
        val c = controller ?: return
        if (c.mediaItemCount == 0) { play(listOf(s), 0); return }
        c.addMediaItem(item(s)); toast = "Added to queue"
    }

    private fun enqueueQuiet(s: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { playInternal(listOf(s), 0); return }
        c.addMediaItem(item(s))
    }

    fun toggle() { if (guestBlocked()) return; controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { if (guestBlocked()) return; noteSkip(); controller?.seekToNextMediaItem() }
    fun prev() {
        if (guestBlocked()) return
        controller?.let { if (it.currentPosition > 4000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() }
    }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled; if (it.shuffleModeEnabled && opt.smartShuffle) smartShuffleFill() } }

    /** Smart shuffle: like Spotify – while shuffling, songs that fit the list are mixed in (marked ✦ in the queue). */
    fun setSmartShuffle(on: Boolean) {
        opt.smartShuffle = on
        val c = controller ?: return
        if (on) { if (!c.shuffleModeEnabled) c.shuffleModeEnabled = true; smartShuffleFill() }
        else { // take the mixed-in songs out again (not the one playing)
            (c.mediaItemCount - 1 downTo 0).filter { it != c.currentMediaItemIndex && c.getMediaItemAt(it).mediaMetadata.extras?.getBoolean("smart") == true }.forEach { c.removeMediaItem(it) }
            toast = "Smart shuffle off"
        }
    }
    private var smartBusy = false
    private fun smartShuffleFill() {
        val a = api ?: return
        val c = controller ?: return
        if (smartBusy || isJamGuest || c.mediaItemCount < 3) return
        smartBusy = true
        viewModelScope.launch {
            try {
                val songs = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSong() }
                val have = songs.map { it.id }.toSet()
                val artists = songs.map { mainArtist(it.artist) }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4).map { it.key }
                val add = similarPool(a, artists, have, (songs.size / 3).coerceIn(4, 20))
                if (add.isNotEmpty()) {
                    val cur = c.currentMediaItemIndex
                    add.forEach { s ->
                        val m = item(s); val md = m.mediaMetadata.buildUpon().setExtras((m.mediaMetadata.extras ?: android.os.Bundle()).apply { putBoolean("smart", true) }).build()
                        val at = (cur + 1 + (Math.random() * (c.mediaItemCount - cur)).toInt()).coerceIn(cur + 1, c.mediaItemCount)
                        c.addMediaItem(at, m.buildUpon().setMediaMetadata(md).build())
                    }
                    toast = "Smart shuffle: ${add.size} songs that fit were mixed in ✦"
                }
            } catch (_: Exception) {}
            smartBusy = false
        }
    }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        }
    }
    fun jumpTo(i: Int) { if (guestBlocked()) return; controller?.run { seekTo(i, 0L); play() } }
    fun seekTo(ms: Long) { if (guestBlocked()) return; controller?.seekTo(ms) }

    fun addToPlaylist(pl: Playlist, s: Song) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                if (isServerList(pl.id)) social?.addToList(pl.id, listOf(s))
                else { if (a.isExternal(s.id)) toast = "Adding to ${pl.name}…"; a.addToPlaylist(pl.id, s.id) }
                toast = "Added to ${pl.name}"; refreshPlaylists(); playlistTick++
            }
            catch (e: Exception) { toast = "Failed: ${e.message}. Play the song once so it's saved on the server." }
        }
    }

    fun newPlaylist(name: String, s: Song?) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                val so = social
                if (so != null) so.createList(name, listOfNotNull(s)) // instant, and any song can be in it
                else { val (_, n) = a.createPlaylistSafe(name, listOfNotNull(s?.id)); if (s != null && n == 0) { toast = "Created \"$name\" – but the song couldn't be added"; refreshPlaylists(); return@launch } }
                toast = "Created \"$name\""; refreshPlaylists()
            }
            catch (e: Exception) { toast = "Failed: ${e.message}" }
        }
    }

    // ================= queue =================
    var showQueue by mutableStateOf(false)
    var lastError by mutableStateOf<String?>(null)
    var appVisible = true

    private var restored = false
    /** Brings back the last queue after the app was closed (if the setting is on). */
    private fun restoreQueue(c: MediaController) {
        if (restored) return; restored = true
        if (!opt.restoreQueue || c.mediaItemCount > 0 || api == null) return
        val songs = try { val a = JSONArray(prefs.getString("saved_queue", "[]")); (0 until a.length()).mapNotNull { songFromJson(a.optJSONObject(it)) } } catch (_: Exception) { emptyList() }
        if (songs.isEmpty()) return
        val idx = prefs.getInt("saved_index", 0).coerceIn(0, songs.size - 1)
        c.setMediaItems(songs.map(::item), idx, prefs.getLong("saved_pos", 0L).coerceAtLeast(0L))
        c.shuffleModeEnabled = prefs.getBoolean("saved_shuffle", false)
        c.repeatMode = prefs.getInt("saved_repeat", Player.REPEAT_MODE_OFF)
        c.prepare()
        if (opt.resumeOnOpen) c.play()
    }

    fun moveQueue(from: Int, to: Int) {
        if (guestBlocked()) return
        val c = controller ?: return
        if (from == to || from !in 0 until c.mediaItemCount || to !in 0 until c.mediaItemCount) return
        c.moveMediaItem(from, to)
    }
    fun removeQueue(i: Int) {
        if (guestBlocked()) return
        val c = controller ?: return
        if (i == c.currentMediaItemIndex || i !in 0 until c.mediaItemCount) return
        c.removeMediaItem(i)
    }
    fun clearUpcoming() {
        if (guestBlocked()) return
        val c = controller ?: return
        val from = c.currentMediaItemIndex + 1
        if (from < c.mediaItemCount) { c.removeMediaItems(from, c.mediaItemCount); toast = "Upcoming songs cleared" }
    }
    fun saveQueueAsPlaylist(name: String) {
        val a = api ?: return
        val ids = queue.map { it.mediaId }.distinct()
        if (ids.isEmpty()) { toast = "The queue is empty"; return }
        viewModelScope.launch {
            try {
                val so = social
                val n = if (so != null) { so.createList(name, queue.map { it.toSong() }.distinctBy { it.id }); ids.size } else { toast = "Saving \"$name\"…"; a.createPlaylistSafe(name, ids).second }
                toast = "Saved $n songs as \"$name\""; refreshPlaylists()
            }
            catch (e: Exception) { toast = "Couldn't save: ${e.message}" }
        }
    }

    private var filling = false
    /** "Continue with similar music": adds songs like the ones at the end of the queue. */
    fun continueSimilar(quiet: Boolean = false, playAfter: Boolean = false) {
        val a = api ?: return
        val c = controller ?: return
        if (filling || isJamGuest || c.mediaItemCount == 0) return
        filling = true
        val seedSong = c.getMediaItemAt(c.mediaItemCount - 1).toSong()
        viewModelScope.launch {
            val have = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
            val more = try { radioBatch(a, seedSong.artist, have).take(15) } catch (_: Exception) { emptyList() }
            if (more.isNotEmpty()) {
                c.addMediaItems(more.map(::item))
                if (playAfter) { c.seekToNextMediaItem(); c.prepare(); c.play() }
                if (!quiet) toast = "Added ${more.size} similar songs"
            } else if (!quiet) toast = "Couldn't find similar songs right now"
            filling = false
        }
    }
    private fun autoFill(ended: Boolean = false) {
        val c = controller ?: return
        if (!opt.autoplaySimilar || radioSeed != null || isJamGuest || c.repeatMode != Player.REPEAT_MODE_OFF || c.shuffleModeEnabled) return
        if (c.currentMediaItemIndex >= c.mediaItemCount - 1 || ended) continueSimilar(quiet = true, playAfter = ended)
    }

    // ================= account =================
    var pwBusy by mutableStateOf(false)
    fun changePassword(current: String, new: String, done: (String?) -> Unit) {
        val s = social ?: return done("Friends server not connected")
        val a = api ?: return
        pwBusy = true
        viewModelScope.launch {
            try {
                s.changePassword(current, new)
                prefs.edit().putString("pass", new).apply()
                api = Api(a.base, a.user, new).also { it.maxBitRate = quality }
                social = Social(socialBase(), a.user, new)
                toast = "Password changed"; done(null)
            } catch (e: Exception) { done(e.message ?: "Couldn't change the password") }
            pwBusy = false
        }
    }

    // ================= music identity (profile) =================
    val profiles = androidx.compose.runtime.mutableStateMapOf<String, JSONObject>()
    var profileError by mutableStateOf<String?>(null); private set
    var profileBusy by mutableStateOf(false); private set
    fun loadProfile(user: String) {
        val s = social ?: run { profileError = "Friends server not connected"; return }
        profileError = null
        viewModelScope.launch {
            try { profiles[user.lowercase()] = s.identity(user) }
            catch (e: Exception) { profileError = e.message ?: "Couldn't load the profile" }
        }
    }
    fun saveProfile(theme: String, bio: String, favs: JSONObject, done: () -> Unit = {}) {
        val s = social ?: return
        profileBusy = true
        viewModelScope.launch {
            try { profiles[s.me] = s.saveIdentity(theme, bio, favs); toast = "Profile saved"; done() }
            catch (e: Exception) { toast = "Couldn't save: ${e.message}" }
            profileBusy = false
        }
    }
    /** Background picture picked on the profile; the cropper is shown while this is set. */
    var bannerCropUri by mutableStateOf<Uri?>(null)
    fun setBanner(bmp: Bitmap) {
        val s = social ?: run { toast = "Friends server not set up"; return }
        bannerCropUri = null
        profileBusy = true
        viewModelScope.launch {
            try {
                val jpeg = withContext(Dispatchers.Default) { ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray() }
                s.uploadBanner(jpeg); toast = "Background image updated"; profiles[s.me] = s.identity(s.me)
            } catch (e: Exception) { toast = "Couldn't update the image: ${e.message}" }
            profileBusy = false
        }
    }
    /** Artist photos by name (looked up once, then remembered). */
    val artistImages = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private val artistLookups = mutableSetOf<String>()
    fun artistImage(name: String) {
        val a = api ?: return
        val k = name.lowercase()
        if (!artistLookups.add(k)) return
        viewModelScope.launch {
            try {
                val r = a.search(name).artists
                val hit = r.firstOrNull { it.name.equals(name, true) } ?: r.firstOrNull()
                hit?.coverArt?.let { artistImages[k] = it }
            } catch (_: Exception) { artistLookups.remove(k) }
        }
    }
    /** Opens an artist by name (favourites only know the name). */
    fun openArtistNamed(name: String) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                val r = a.search(name).artists
                val hit = r.firstOrNull { it.name.equals(name, true) } ?: r.firstOrNull()
                if (hit != null) go(Screen.ArtistS(hit.id)) else toast = "Couldn't find $name"
            } catch (e: Exception) { toast = e.message ?: "Couldn't open the artist" }
        }
    }
    // ================= stats =================
    var statsRange by mutableStateOf("week")
    var stats by mutableStateOf<JSONObject?>(null); private set
    var statsError by mutableStateOf<String?>(null); private set
    fun loadStats(range: String = statsRange) {
        val s = social ?: return
        statsRange = range; statsError = null
        viewModelScope.launch {
            try { val j = s.myStats(range); if (range == statsRange) stats = j }
            catch (e: Exception) { statsError = e.message ?: "Couldn't load your stats" }
        }
    }

    // ================= storage & diagnostics =================
    fun coverCacheBytes() = dirSize(app.cacheDir.resolve("covers"))
    fun otherCacheBytes() = dirSize(app.cacheDir) - coverCacheBytes()
    fun clearTempFiles() {
        app.cacheDir.listFiles()?.filter { it.name != "covers" }?.forEach { it.deleteRecursively() }
        toast = "Temporary files removed"
    }
    suspend fun diagnostics(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        out += "Network" to networkName(app)
        out += "Streaming quality now" to (currentBitrate(app, prefs).let { if (it == 0) "Original" else "$it kbps" })
        val a = api
        out += "Music server" to (a?.base ?: "–")
        out += "Music server answer" to (try { "${a?.pingMs()} ms" } catch (e: Exception) { "Not reachable: ${e.message}" })
        out += "Friends server" to socialBase()
        out += "Friends server answer" to (try { val t = System.currentTimeMillis(); social?.meProfile(); "${System.currentTimeMillis() - t} ms" } catch (e: Exception) { "Not reachable: ${e.message}" })
        out += "Last playback error" to (lastError ?: "None")
        out += "App version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        out += "Android" to "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) • ${android.os.Build.MODEL}"
        out += "Cover cache" to mb(coverCacheBytes())
        out += "Downloads" to mb(downloadBytes())
        return out
    }

    // ================= continue on another device =================
    private val deviceId: String = prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
    private val deviceName: String = try { android.provider.Settings.Global.getString(app.contentResolver, "device_name") } catch (_: Exception) { null } ?: android.os.Build.MODEL
    var resumeOffer by mutableStateOf<JSONObject?>(null)
    private var lastResumePush = 0L

    /** Tells the server where you are, so your PC / web app can continue from here (and the other way round). */
    fun pushResume(force: Boolean = false) {
        val s = social ?: return
        val c = controller ?: return
        if (c.mediaItemCount == 0 || isJamGuest) return
        val now = System.currentTimeMillis()
        if (!force && now - lastResumePush < 15_000) return
        lastResumePush = now
        if (c.isPlaying) prefs.edit().putLong("last_local_play", now).apply()
        val start = (c.currentMediaItemIndex - 20).coerceAtLeast(0)
        val q = JSONArray(); for (i in start until minOf(c.mediaItemCount, start + 150)) q.put(c.getMediaItemAt(i).toSong().toJson())
        val st = JSONObject().put("queue", q).put("index", c.currentMediaItemIndex - start).put("pos", c.currentPosition).put("playing", c.isPlaying)
        viewModelScope.launch { try { s.putResume(st, deviceName, deviceId) } catch (_: Exception) {} }
    }

    /** Asks the server if you were listening somewhere else more recently than here. */
    fun checkResume() {
        val s = social ?: return
        if (!opt.offerResume) return
        viewModelScope.launch {
            try {
                val j = s.getResume()
                val ts = j.optLong("ts"); val st = j.optJSONObject("state") ?: return@launch
                val fresh = System.currentTimeMillis() - ts < 30 * 60_000L // continuing happens by itself, so only a recent session counts
                val newer = ts > prefs.getLong("last_local_play", 0L) + 5_000
                val elsewhere = j.optString("deviceId") != deviceId
                val q = st.optJSONArray("queue")
                if (fresh && newer && elsewhere && q != null && q.length() > 0 && controller?.isPlaying != true && ts != prefs.getLong("resume_dismissed", 0L)) { resumeOffer = j; acceptResume() } // no question: it just continues here
            } catch (_: Exception) {}
        }
    }
    fun acceptResume() {
        val j = resumeOffer ?: return; resumeOffer = null
        val c = controller ?: return
        val st = j.optJSONObject("state") ?: return
        val a = st.optJSONArray("queue") ?: return
        val songs = (0 until a.length()).mapNotNull { songFromJson(a.optJSONObject(it)) }
        if (songs.isEmpty() || api == null) return
        if (isJamGuest) { toast = "Leave the Jam first"; return }
        radioSeed = null
        c.setMediaItems(songs.map(::item), st.optInt("index").coerceIn(0, songs.size - 1), st.optLong("pos").coerceAtLeast(0L))
        c.prepare(); c.play()
        prefs.edit().putLong("last_local_play", System.currentTimeMillis()).apply()
    }
    fun dismissResume() { resumeOffer?.let { prefs.edit().putLong("resume_dismissed", it.optLong("ts")).apply() }; resumeOffer = null }

    // ================= "right now" mix (what you usually play at this time) =================
    var moment by mutableStateOf<Mix?>(null); private set
    private var momentKey = ""
    fun loadMoment(force: Boolean = false) {
        val s = social ?: return
        val a = api ?: return
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val wd = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
        val key = "$wd:$hour"
        if (!force && key == momentKey && moment != null) return
        momentKey = key
        viewModelScope.launch {
            try {
                val j = s.moment(hour, wd)
                val arr = j.optJSONArray("songs") ?: JSONArray()
                val mine = (0 until arr.length()).mapNotNull { songFromJson(arr.optJSONObject(it)) }
                val seeds = j.optJSONArray("seeds")?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()
                var songs = mine.shuffled()
                if (songs.size < 15 && seeds.isNotEmpty()) songs = (songs + try { radioBatch(a, seeds.first(), songs.map { it.id }.toSet()).take(20 - songs.size) } catch (_: Exception) { emptyList() }).distinctBy { it.id }
                moment = if (songs.size >= 5) Mix(j.optString("title"), "Based on what you usually play now", songs, 0xFF0EA5E9, seeds.firstOrNull() ?: "") else null
            } catch (_: Exception) {}
        }
    }
}
