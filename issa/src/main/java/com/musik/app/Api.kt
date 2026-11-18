package com.musik.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Song(
    val id: String, val title: String, val artist: String, val album: String,
    val albumId: String?, val artistId: String?, val coverArt: String?, val duration: Int,
    val genre: String? = null
)

data class Album(val id: String, val name: String, val artist: String, val artistId: String?, val coverArt: String?, val year: Int?)
data class Artist(val id: String, val name: String, val coverArt: String?, val albumCount: Int?)
data class Playlist(
    val id: String, val name: String, val songCount: Int,
    val owner: String = "", val public: Boolean = false, val coverArt: String? = null
)
data class SearchResult(val artists: List<Artist>, val albums: List<Album>, val songs: List<Song>)
data class Starred(val songs: List<Song>, val albums: List<Album>, val artists: List<Artist>)

private fun JSONObject.str(k: String): String? = optString(k, "").ifEmpty { null }
private fun JSONObject.list(k: String): List<JSONObject> {
    val v = opt(k) ?: return emptyList()
    return when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
        is JSONObject -> listOf(v)
        else -> emptyList()
    }
}

fun parseSong(o: JSONObject) = Song(
    id = o.getString("id"), title = o.str("title") ?: "", artist = o.str("artist") ?: "",
    album = o.str("album") ?: "", albumId = o.str("albumId"), artistId = o.str("artistId"),
    coverArt = o.str("coverArt") ?: o.str("albumId"), duration = o.optInt("duration", 0), genre = o.str("genre")
)

fun Song.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("title", title); put("artist", artist); put("album", album)
    put("albumId", albumId ?: ""); put("artistId", artistId ?: ""); put("coverArt", coverArt ?: ""); put("duration", duration)
    if (!genre.isNullOrBlank()) put("genre", genre)
}

fun songFromJson(o: JSONObject?): Song? = if (o == null || o.optString("id").isEmpty()) null else parseSong(o)

fun parseAlbum(o: JSONObject) = Album(
    id = o.getString("id"), name = o.str("name") ?: o.str("title") ?: "", artist = o.str("artist") ?: "",
    artistId = o.str("artistId"), coverArt = o.str("coverArt") ?: o.getString("id"),
    year = if (o.has("year")) o.optInt("year") else null
)

fun parseArtist(o: JSONObject) = Artist(
    id = o.getString("id"), name = o.str("name") ?: "", coverArt = o.str("coverArt") ?: o.getString("id"),
    albumCount = if (o.has("albumCount")) o.optInt("albumCount") else null
)

class Api(base: String, val user: String, val pass: String) {
    val root: String = base.trim().trimEnd('/')
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 24 }) // the default is 5 at a time: searches and lists queued up behind each other
        .build()

    val base get() = root

    fun url(ep: String, params: Map<String, String> = emptyMap()): String {
        Feedback.api(ep, params) // like / unlike teaches the taste profile
        // searches go through the Issa server (typos fixed there) when it is up; everything else straight to the music server
        val b = Uri.parse("${SearchRoute.root(root, ep)}/rest/$ep.view").buildUpon()
            .appendQueryParameter("u", user)
            .appendQueryParameter("p", pass)
            .appendQueryParameter("v", "1.16.1")
            .appendQueryParameter("c", "IssaMusic")
            .appendQueryParameter("f", "json")
        params.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        return b.build().toString()
    }

    /** 0 = original quality, otherwise max kbps. */
    var maxBitRate: Int = 0
    /** Biggest cover size to download (300 or 600). */
    var maxCover: Int = 600

    fun streamUrl(id: String) =
        url("stream", if (maxBitRate > 0) mapOf("id" to id, "maxBitRate" to maxBitRate.toString(), "format" to "mp3") else mapOf("id" to id))
    // only two sizes, so the same cover is downloaded once and reused everywhere
    fun coverUrl(id: String?, size: Int = 300): String? =
        id?.let { url("getCoverArt", mapOf("id" to it, "size" to (if (size <= 300 || maxCover <= 300) "300" else "600"))) }

    companion object {
        /** Answers that don't change often are kept for a few minutes, so going back and forth between pages is instant. */
        private val CACHED = setOf("getAlbum", "getArtist", "getPlaylist", "getPlaylists", "search3", "getArtistInfo2", "getSimilarSongs2", "getTopSongs")
        private val WRITES = setOf("createPlaylist", "updatePlaylist", "deletePlaylist", "star", "unstar", "startScan")
        private val cache = object : LinkedHashMap<String, Pair<Long, JSONObject>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JSONObject>>?) = size > 150
        }
        private const val TTL = 3 * 60_000L
        fun clearCache() = synchronized(cache) { cache.clear() }
    }

    private suspend fun call(ep: String, params: Map<String, String> = emptyMap()): JSONObject {
        if (ep in WRITES) clearCache()
        val key = if (ep in CACHED) "$user|$ep|" + params.entries.sortedBy { it.key }.joinToString("&") else null
        if (key != null) synchronized(cache) { cache[key] }?.let { (t, j) -> if (System.currentTimeMillis() - t < TTL) return j }
        val j = callNet(ep, params)
        if (key != null) synchronized(cache) { cache[key] = System.currentTimeMillis() to j }
        return j
    }

    private suspend fun callNet(ep: String, params: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url(ep, params)).build()).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (txt.isBlank()) throw Exception("Empty response from server (${r.code})")
                val j = try { JSONObject(txt).getJSONObject("subsonic-response") } catch (e: Exception) {
                    throw Exception("Unexpected response from server (${r.code})")
                }
                if (j.optString("status") != "ok") {
                    throw Exception(j.optJSONObject("error")?.optString("message") ?: "Unknown error")
                }
                j
            }
        }

    private suspend fun callPairs(ep: String, pairs: List<Pair<String, String>>): JSONObject = withContext(Dispatchers.IO) {
        if (ep in WRITES) clearCache()
        val b = Uri.parse(url(ep)).buildUpon(); pairs.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        http.newCall(Request.Builder().url(b.build().toString()).build()).execute().use { r ->
            val j = try { JSONObject(r.body?.string().orEmpty()).getJSONObject("subsonic-response") } catch (e: Exception) { throw Exception("Unexpected response from server (${r.code})") }
            if (j.optString("status") != "ok") throw Exception(j.optJSONObject("error")?.optString("message") ?: "Unknown error")
            j
        }
    }

    /** How long the music server takes to answer, in ms. */
    suspend fun pingMs(): Long { val t = System.currentTimeMillis(); call("ping"); return System.currentTimeMillis() - t }

    suspend fun ping() { call("ping") }

    /** Songs only – lighter and faster than a full search (used for Home and radio). */
    suspend fun searchSongs(q: String, count: Int = 40): List<Song> =
        (call("search3", mapOf("query" to q, "artistCount" to "0", "albumCount" to "0", "songCount" to count.toString()))
            .optJSONObject("searchResult3") ?: JSONObject()).list("song").map(::parseSong)

    suspend fun search(q: String): SearchResult {
        val r = call("search3", mapOf("query" to q, "artistCount" to "10", "albumCount" to "20", "songCount" to "40"))
            .optJSONObject("searchResult3") ?: JSONObject()
        return SearchResult(r.list("artist").map(::parseArtist), r.list("album").map(::parseAlbum), r.list("song").map(::parseSong))
    }

    suspend fun artist(id: String): Pair<Artist, List<Album>> {
        val a = call("getArtist", mapOf("id" to id)).getJSONObject("artist")
        return parseArtist(a) to a.list("album").map(::parseAlbum)
    }

    suspend fun album(id: String): Pair<Album, List<Song>> {
        val a = call("getAlbum", mapOf("id" to id)).getJSONObject("album")
        val al = parseAlbum(a)
        return al to a.list("song").map { parseSong(it).let { s -> if (s.coverArt == null) s.copy(coverArt = al.coverArt) else s } }
    }

    suspend fun starred(): Starred {
        val s = call("getStarred2").optJSONObject("starred2") ?: JSONObject()
        return Starred(s.list("song").map(::parseSong), s.list("album").map(::parseAlbum), s.list("artist").map(::parseArtist))
    }

    suspend fun star(id: String, on: Boolean) { call(if (on) "star" else "unstar", mapOf("id" to id)) }

    private fun parsePlaylist(o: JSONObject, count: Int? = null) = Playlist(
        o.getString("id"), o.str("name") ?: "", count ?: o.optInt("songCount", 0),
        owner = o.str("owner") ?: "", public = o.optBoolean("public", false), coverArt = o.str("coverArt") ?: o.getString("id")
    )

    suspend fun playlists(): List<Playlist> =
        (call("getPlaylists").optJSONObject("playlists") ?: JSONObject()).list("playlist").map { parsePlaylist(it) }

    suspend fun playlist(id: String): Pair<Playlist, List<Song>> {
        val p = call("getPlaylist", mapOf("id" to id)).getJSONObject("playlist")
        val entries = p.list("entry").map(::parseSong)
        return parsePlaylist(p, entries.size) to entries
    }

    /** Navidrome itself (port 4533), used when the proxy doesn't pass a playlist change through. */
    private val direct: Api? by lazy {
        if (root.endsWith(":5274")) Api(root.removeSuffix(":5274") + ":4533", user, pass) else null
    }

    private suspend fun callOrDirect(ep: String, params: Map<String, String>) {
        try { call(ep, params) } catch (e: Exception) {
            val d = direct ?: throw e
            try { d.call(ep, params) } catch (_: Exception) { throw e }
        }
    }

    suspend fun setPlaylistPublic(id: String, public: Boolean) {
        callOrDirect("updatePlaylist", mapOf("playlistId" to id, "public" to public.toString()))
    }

    suspend fun renamePlaylist(id: String, name: String) {
        callOrDirect("updatePlaylist", mapOf("playlistId" to id, "name" to name))
    }

    suspend fun startScan() { call("startScan") }

    suspend fun createPlaylist(name: String, songId: String? = null) {
        val m = mutableMapOf("name" to name)
        if (songId != null) m["songId"] = songId
        call("createPlaylist", m)
    }

    /** Creates a playlist with many songs in one call. */
    suspend fun createPlaylistWith(name: String, songIds: List<String>) =
        callPairs("createPlaylist", listOf("name" to name) + songIds.map { "songId" to it })

    /** Swaps all songs in a playlist for new ones (keeps its id, name and picture). */
    suspend fun replacePlaylistSongs(id: String, songIds: List<String>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val n = playlist(id).second.size
        if (n > 0) callPairs("updatePlaylist", listOf("playlistId" to id) + (0 until n).map { "songIndexToRemove" to it.toString() })
        return addSongsSafe(id, songIds, onProgress)
    }

    /** Songs found on Deezer (ids like "ext-deezer-…") only exist on the music server after it has downloaded them. */
    fun isExternal(id: String) = id.startsWith("ext-")

    /** Adds songs one at a time (three in parallel). The proxy downloads Deezer songs before adding them,
     *  and it can only take one song per request – so a list made of many songs takes a little while. Returns how many got in. */
    suspend fun addSongsSafe(playlistId: String, songIds: List<String>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int = kotlinx.coroutines.coroutineScope {
        var done = 0; var ok = 0
        songIds.chunked(3).forEach { chunk ->
            val res = chunk.map { sid -> async { try { call("updatePlaylist", mapOf("playlistId" to playlistId, "songIdToAdd" to sid)); true } catch (_: Exception) { false } } }.awaitAll()
            ok += res.count { it }; done += chunk.size; onProgress(done, songIds.size)
        }
        ok
    }

    /** Creates a playlist that really gets all its songs (local ones right away, Deezer ones one by one). Returns (id, songs added). */
    suspend fun createPlaylistSafe(name: String, songIds: List<String>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Pair<String?, Int> {
        val local = songIds.filter { !isExternal(it) }; val ext = songIds.filter(::isExternal)
        val r = callPairs("createPlaylist", listOf("name" to name) + local.map { "songId" to it })
        val id = r.optJSONObject("playlist")?.optString("id")?.ifEmpty { null } ?: playlists().filter { it.name == name }.maxByOrNull { it.id }?.id
            ?: return null to 0
        onProgress(local.size, songIds.size)
        val added = if (ext.isEmpty()) 0 else addSongsSafe(id, ext) { d, _ -> onProgress(local.size + d, songIds.size) }
        return id to (local.size + added)
    }

    suspend fun addToPlaylist(playlistId: String, songId: String) {
        call("updatePlaylist", mapOf("playlistId" to playlistId, "songIdToAdd" to songId))
    }

    suspend fun removeFromPlaylist(playlistId: String, index: Int) {
        call("updatePlaylist", mapOf("playlistId" to playlistId, "songIndexToRemove" to index.toString()))
    }

    suspend fun deletePlaylist(id: String) { callOrDirect("deletePlaylist", mapOf("id" to id)) }
}
