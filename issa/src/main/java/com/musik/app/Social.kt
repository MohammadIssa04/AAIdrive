package com.musik.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Friend(
    val name: String, val display: String, val avatarTs: Long,
    val song: Song?, val npTs: Long, val playing: Boolean
)

data class Rec(
    val id: Long, val from: String, val fromDisplay: String, val fromAvatarTs: Long,
    val song: Song, val message: String, val ts: Long, val seen: Boolean, val isPlaylist: Boolean = false
)

data class TopItem(val song: Song, val count: Int)
data class AppUpdate(val versionCode: Int, val versionName: String, val notes: String, val size: Long)
data class Reaction(val id: Long, val from: String, val fromDisplay: String, val emoji: String, val song: Song?, val ts: Long, val seen: Boolean)
data class CollabSong(val song: Song, val addedBy: String)
data class Collab(val id: Long, val name: String, val owner: String, val ownerDisplay: String, val members: List<Pair<String, String>>, val songs: List<CollabSong>)
data class Jam(
    val host: String, val hostDisplay: String, val queue: List<Song>, val index: Int, val pos: Long, val playing: Boolean,
    val stateTs: Long, val fade: Int, val members: List<String>, val invited: List<String>, val pendingCount: Int, val serverNow: Long,
    val feed: List<JamEvent> = emptyList()
)
/** A chat message or a hype in a Jam. */
data class GenPl(val id: String, val kind: String, val range: String, val size: Int, val auto: Boolean, val ts: Long)
data class JamEvent(val id: Long, val type: String, val from: String, val fromDisplay: String, val text: String, val emoji: String, val ts: Long, val songId: String = "")
data class WeekStat(
    val name: String, val display: String, val minutes: Int, val plays: Int, val topSongs: List<TopItem>,
    val topArtists: List<Pair<String, Int>>, val common: List<Song>
)
data class Wrapped(
    val year: Int, val display: String, val minutes: Int, val plays: Int, val uniqueArtists: Int, val uniqueSongs: Int,
    val topSongs: List<TopItem>, val topArtists: List<Pair<String, Int>>, val topMonth: String
)

private fun JSONArray.objs(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.arr(k: String): JSONArray = optJSONArray(k) ?: JSONArray()
private fun tops(a: JSONArray) = a.objs().mapNotNull { o -> songFromJson(o.optJSONObject("song"))?.let { TopItem(it, o.optInt("count")) } }
private fun artists(a: JSONArray) = a.objs().map { it.optString("name") to it.optInt("count") }

/** Streams a file to the server while reporting progress (0..1). */
class ProgressBody(
    private val size: Long, private val open: () -> java.io.InputStream, private val onProgress: (Float) -> Unit
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = size
    override fun writeTo(sink: okio.BufferedSink) {
        open().use { input ->
            val buf = ByteArray(64 * 1024)
            var sent = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                sent += n
                if (size > 0) onProgress((sent.toFloat() / size).coerceIn(0f, 1f))
            }
        }
    }
}

class Social(base: String, val user: String, private val pass: String) {
    val root: String = base.trim().trimEnd('/')
    val me: String = user.lowercase()
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 }).build()
    private val json = "application/json".toMediaType()

    fun avatarUrl(name: String, ts: Long): String? =
        if (ts <= 0) null else "$root/api/avatar/${Uri.encode(name.lowercase())}?v=$ts"

    private suspend fun req(method: String, path: String, body: RequestBody? = null): String = withContext(Dispatchers.IO) {
        val r = Request.Builder().url(root + path).header("X-User", user).header("X-Pass", pass).method(method, body).build()
        http.newCall(r).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = try { JSONObject(txt).optString("error") } catch (_: Exception) { "" }
                throw Exception(msg.ifEmpty { if (resp.code == 404) "Friends server needs an update" else "Friends server error (${resp.code})" })
            }
            txt
        }
    }

    private fun friend(o: JSONObject) = Friend(
        o.getString("name"), o.optString("display", o.getString("name")), o.optLong("avatarTs"),
        songFromJson(o.optJSONObject("song")), o.optLong("npTs"), o.optBoolean("playing")
    )

    suspend fun meProfile(): Friend = friend(JSONObject(req("GET", "/api/me")))

    suspend fun changePassword(current: String, new: String) {
        req("POST", "/api/password", JSONObject().put("current", current).put("new", new).toString().toRequestBody(json))
    }

    suspend fun myStats(range: String, top: Int = 10): JSONObject = JSONObject(req("GET", "/api/stats/me?range=$range&top=$top"))
    // own pictures for playlists
    suspend fun plCovers(): Map<String, Long> = JSONObject(req("GET", "/api/plcovers")).let { o -> o.keys().asSequence().associateWith { o.optLong(it) } }
    suspend fun uploadPlCover(id: String, jpeg: ByteArray) { req("PUT", "/api/plcover/${Uri.encode(id)}", jpeg.toRequestBody("image/jpeg".toMediaType())) }
    suspend fun removePlCover(id: String) { req("POST", "/api/plcover/remove", JSONObject().put("id", id).toString().toRequestBody(json)) }
    /** Songs by a line of their lyrics or a title written another way (title, artist, line – line "" = title match). */
    suspend fun lyricSearch(q: String): List<Triple<String, String, String>> =
        JSONArray(req("GET", "/api/lyricsearch?q=" + android.net.Uri.encode(q))).objs().map { Triple(it.optString("title"), it.optString("artist"), it.optString("line")) }
    /** Songs with the same vibe (the server asks Deezer: the artist's radio + related artists). */
    suspend fun vibe(artist: String, title: String?, n: Int, exclude: Collection<String>): List<Song> =
        JSONArray(req("GET", "/api/vibe?artist=" + android.net.Uri.encode(artist) + "&title=" + android.net.Uri.encode(title ?: "") + "&n=$n" +
            "&exclude=" + android.net.Uri.encode(exclude.filter { it.startsWith("ext-") }.take(200).joinToString(",")))).objs().mapNotNull { songFromJson(it) }
    // ---- recent searches, kept on the server so every device has the same ones
    suspend fun searchHistory(): List<String> = JSONObject(req("GET", "/api/searchhistory")).optJSONArray("searches")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    suspend fun rememberSearch(q: String) { req("POST", "/api/searchhistory", JSONObject().put("q", q).toString().toRequestBody(json)) }
    suspend fun clearSearchHistory() { req("POST", "/api/searchhistory", JSONObject().put("clear", true).toString().toRequestBody(json)) }
    /** What you listen to (all devices) – for personal search ranking. */
    suspend fun taste(): JSONObject = JSONObject(req("GET", "/api/taste"))
    suspend fun genpl(): List<GenPl> = JSONArray(req("GET", "/api/genpl")).objs().map { GenPl(it.optString("id"), it.optString("kind"), it.optString("range"), it.optInt("size", 50), it.optBoolean("auto", true), it.optLong("ts")) }
    suspend fun saveGenpl(id: String, kind: String, range: String, size: Int, auto: Boolean) {
        req("POST", "/api/genpl", JSONObject().put("id", id).put("kind", kind).put("range", range).put("size", size).put("auto", auto).toString().toRequestBody(json))
    }
    suspend fun removeGenpl(id: String) { req("POST", "/api/genpl/remove", JSONObject().put("id", id).toString().toRequestBody(json)) }
    // ---- playlists kept on the friends server (id "il-…"): instant to make, any song can be in them (also Deezer ones)
    private fun plOf(o: JSONObject) = Playlist(o.optString("id"), o.optString("name"), o.optInt("songCount"), o.optString("owner"), o.optBoolean("public"), o.optString("coverArt").ifEmpty { null })
    private fun songsJson(songs: List<Song>) = JSONArray().also { a -> songs.forEach { a.put(it.toJson()) } }
    suspend fun lists(): List<Playlist> = JSONArray(req("GET", "/api/lists")).objs().map(::plOf)
    suspend fun list(id: String): Pair<Playlist, List<Song>> = JSONObject(req("GET", "/api/lists/${Uri.encode(id)}")).let { o -> plOf(o) to o.arr("songs").objs().mapNotNull { songFromJson(it) } }
    suspend fun createList(name: String, songs: List<Song>): Playlist = plOf(JSONObject(post("/api/lists/create", JSONObject().put("name", name).put("songs", songsJson(songs)))()))
    suspend fun addToList(id: String, songs: List<Song>) { post("/api/lists/add", JSONObject().put("id", id).put("songs", songsJson(songs)))() }
    suspend fun removeFromList(id: String, index: Int) { post("/api/lists/remove", JSONObject().put("id", id).put("index", index))() }
    suspend fun updateList(id: String, name: String? = null, songs: List<Song>? = null, public: Boolean? = null) {
        val o = JSONObject().put("id", id); if (name != null) o.put("name", name); if (songs != null) o.put("songs", songsJson(songs)); if (public != null) o.put("public", public)
        post("/api/lists/update", o)()
    }
    suspend fun deleteList(id: String) { post("/api/lists/delete", JSONObject().put("id", id))() }
    fun plCoverUrl(id: String, ts: Long) = "$root/api/plcover/${Uri.encode(id)}?v=$ts"

    suspend fun putResume(state: JSONObject, device: String, deviceId: String) {
        req("POST", "/api/resume", JSONObject().put("state", state).put("device", device).put("deviceId", deviceId).toString().toRequestBody(json))
    }
    suspend fun identity(user: String): JSONObject = JSONObject(req("GET", "/api/identity?user=${Uri.encode(user.lowercase())}"))
    suspend fun saveIdentity(theme: String, bio: String, favs: JSONObject): JSONObject =
        JSONObject(req("POST", "/api/identity", JSONObject().put("theme", theme).put("bio", bio).put("favs", favs).toString().toRequestBody(json)))
    suspend fun uploadBanner(jpeg: ByteArray) { req("PUT", "/api/banner", jpeg.toRequestBody("image/jpeg".toMediaType())) }
    fun bannerUrl(name: String, ts: Long): String? = if (ts <= 0) null else "$root/api/banner/${Uri.encode(name.lowercase())}?v=$ts"
    suspend fun getResume(): JSONObject = JSONObject(req("GET", "/api/resume"))
    suspend fun moment(hour: Int, weekday: Int): JSONObject = JSONObject(req("GET", "/api/moment?hour=$hour&weekday=$weekday"))

    suspend fun users(): List<Friend> {
        val a = JSONArray(req("GET", "/api/users"))
        return (0 until a.length()).map { friend(a.getJSONObject(it)) }
    }

    suspend fun setDisplay(name: String) {
        req("POST", "/api/profile", JSONObject().put("display", name).toString().toRequestBody(json))
    }

    suspend fun uploadAvatar(jpeg: ByteArray) {
        req("PUT", "/api/avatar", jpeg.toRequestBody("image/jpeg".toMediaType()))
    }

    suspend fun nowPlaying(song: Song?, playing: Boolean) {
        val o = JSONObject().put("playing", playing)
        if (song != null) o.put("song", song.toJson())
        req("POST", "/api/nowplaying", o.toString().toRequestBody(json))
    }

    suspend fun recommend(to: String, song: Song, message: String) {
        val o = JSONObject().put("to", to).put("song", song.toJson()).put("message", message)
        req("POST", "/api/recommend", o.toString().toRequestBody(json))
    }

    suspend fun inbox(): List<Rec> {
        val a = JSONArray(req("GET", "/api/inbox"))
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val so = o.optJSONObject("song")
            val s = songFromJson(so) ?: return@mapNotNull null
            Rec(o.getLong("id"), o.getString("from"), o.optString("fromDisplay"), o.optLong("fromAvatarTs"),
                s, o.optString("message"), o.optLong("ts"), o.optBoolean("seen"), isPlaylist = so?.optString("type") == "playlist")
        }
    }

    suspend fun recommendPlaylist(to: String, pl: Playlist, message: String) {
        val p = JSONObject().put("id", pl.id).put("title", pl.name).put("artist", pl.owner).put("coverArt", pl.coverArt ?: "")
            .put("duration", pl.songCount)
        val o = JSONObject().put("to", to).put("playlist", p).put("message", message)
        req("POST", "/api/recommend", o.toString().toRequestBody(json))
    }

    suspend fun play(song: Song) {
        req("POST", "/api/play", JSONObject().put("song", song.toJson()).toString().toRequestBody(json))
    }

    suspend fun top(user: String, days: Int = 7, limit: Int = 10): List<TopItem> {
        val a = JSONArray(req("GET", "/api/top?user=${Uri.encode(user)}&days=$days&limit=$limit"))
        return (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            songFromJson(o.optJSONObject("song"))?.let { TopItem(it, o.optInt("count")) }
        }
    }

    private fun post(path: String, o: JSONObject = JSONObject()) = suspend { req("POST", path, o.toString().toRequestBody(json)) }

    // ---- reactions
    suspend fun react(to: String, emoji: String, song: Song?) {
        val o = JSONObject().put("to", to).put("emoji", emoji)
        if (song != null) o.put("song", song.toJson())
        post("/api/react", o)()
    }

    suspend fun reactions(): List<Reaction> = JSONArray(req("GET", "/api/reactions")).objs().map {
        Reaction(it.getLong("id"), it.getString("from"), it.optString("fromDisplay"), it.optString("emoji"),
            songFromJson(it.optJSONObject("song")), it.optLong("ts"), it.optBoolean("seen"))
    }

    suspend fun reactionsSeen() { post("/api/reactions/seen")() }

    // ---- collaborative playlists
    private fun collab(o: JSONObject) = Collab(
        o.getLong("id"), o.optString("name"), o.optString("owner"), o.optString("ownerDisplay"),
        o.arr("members").objs().map { it.optString("name") to it.optString("display") },
        o.arr("songs").objs().mapNotNull { s -> songFromJson(s)?.let { CollabSong(it, s.optString("addedBy")) } }
    )

    suspend fun collabs(): List<Collab> = JSONArray(req("GET", "/api/collab")).objs().map(::collab)
    suspend fun createCollab(name: String, members: List<String>): Collab =
        collab(JSONObject(post("/api/collab/create", JSONObject().put("name", name).put("members", JSONArray(members)))()))
    suspend fun collabAdd(id: Long, song: Song): Collab = collab(JSONObject(post("/api/collab/add", JSONObject().put("id", id).put("song", song.toJson()))()))
    suspend fun collabRemove(id: Long, index: Int): Collab = collab(JSONObject(post("/api/collab/remove", JSONObject().put("id", id).put("index", index))()))
    suspend fun collabRename(id: Long, name: String): Collab = collab(JSONObject(post("/api/collab/rename", JSONObject().put("id", id).put("name", name))()))
    suspend fun collabDelete(id: Long) { post("/api/collab/delete", JSONObject().put("id", id))() }

    // ---- Jam
    private fun jam(o: JSONObject): Jam {
        val st = o.optJSONObject("state") ?: JSONObject()
        return Jam(
            o.getString("host"), o.optString("hostDisplay"), st.arr("queue").objs().mapNotNull { songFromJson(it) },
            st.optInt("index"), st.optLong("pos"), st.optBoolean("playing"), st.optLong("ts", o.optLong("ts")), st.optInt("fade", -1),
            o.arr("members").let { a -> (0 until a.length()).map { a.getString(it) } },
            o.arr("invited").let { a -> (0 until a.length()).map { a.getString(it) } },
            o.optInt("pendingCount"), o.optLong("serverNow"),
            o.arr("feed").objs().map { e -> JamEvent(e.optLong("id"), e.optString("type"), e.optString("from"), e.optString("fromDisplay", e.optString("from")),
                e.optString("text"), e.optString("emoji"), e.optLong("ts"), e.optString("songId")) }
        )
    }

    /** Your fade length, sent with the Jam so guests blend songs at the same moment. */
    var jamFade = 0
    private fun stateJson(queue: List<Song>, index: Int, pos: Long, playing: Boolean) = JSONObject()
        .put("queue", JSONArray().also { a -> queue.take(200).forEach { a.put(it.toJson()) } })
        .put("index", index).put("pos", pos).put("playing", playing).put("fade", jamFade)

    suspend fun jamStart(invite: List<String>, queue: List<Song>, index: Int, pos: Long, playing: Boolean): Jam =
        jam(JSONObject(post("/api/jam/start", JSONObject().put("invite", JSONArray(invite)).put("state", stateJson(queue, index, pos, playing)))()))
    suspend fun jamState(queue: List<Song>, index: Int, pos: Long, playing: Boolean): Jam =
        jam(JSONObject(post("/api/jam/state", JSONObject().put("state", stateJson(queue, index, pos, playing)))()))
    suspend fun jamTake(): List<Song> = JSONArray(post("/api/jam/take")()).objs().mapNotNull { songFromJson(it) }
    suspend fun jamEnd() { post("/api/jam/end")() }
    suspend fun jamGet(host: String): Jam = jam(JSONObject(req("GET", "/api/jam?host=${Uri.encode(host)}")))
    suspend fun jamJoin(host: String): Jam = jam(JSONObject(post("/api/jam/join", JSONObject().put("host", host))()))
    suspend fun jamLeave(host: String) { post("/api/jam/leave", JSONObject().put("host", host))() }
    suspend fun jamChat(host: String, text: String): Jam = jam(JSONObject(post("/api/jam/chat", JSONObject().put("host", host).put("text", text))()))
    suspend fun jamSkip(host: String): Jam = jam(JSONObject(post("/api/jam/skip", JSONObject().put("host", host))()))
    suspend fun jamHype(host: String, emoji: String): Jam = jam(JSONObject(post("/api/jam/hype", JSONObject().put("host", host).put("emoji", emoji))()))
    suspend fun jamAdd(host: String, song: Song) { post("/api/jam/add", JSONObject().put("host", host).put("song", song.toJson()))() }
    suspend fun jams(): List<Jam> = JSONArray(req("GET", "/api/jams")).objs().map(::jam)

    // ---- stats
    suspend fun weekly(): List<WeekStat> = JSONArray(req("GET", "/api/weekly")).objs().map {
        WeekStat(it.optString("name"), it.optString("display"), it.optInt("minutes"), it.optInt("plays"), tops(it.arr("topSongs")),
            artists(it.arr("topArtists")), it.arr("common").objs().mapNotNull { s -> songFromJson(s) })
    }

    suspend fun wrapped(year: Int): Wrapped = JSONObject(req("GET", "/api/wrapped?year=$year")).let {
        Wrapped(it.optInt("year"), it.optString("display"), it.optInt("minutes"), it.optInt("plays"), it.optInt("uniqueArtists"),
            it.optInt("uniqueSongs"), tops(it.arr("topSongs")), artists(it.arr("topArtists")), it.optString("topMonth"))
    }

    // ---- app updates
    suspend fun latestApp(): AppUpdate? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$root/api/app/latest").build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val o = JSONObject(r.body?.string().orEmpty())
            if (o.optInt("versionCode") <= 0) null
            else AppUpdate(o.optInt("versionCode"), o.optString("versionName"), o.optString("notes"), o.optLong("size"))
        }
    }

    suspend fun downloadApp(dest: java.io.File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        uploadHttp.newCall(Request.Builder().url("$root/api/app/download").build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Download failed (${r.code})")
            val body = r.body ?: throw Exception("Empty download")
            val total = body.contentLength()
            val tmp = java.io.File(dest.path + ".part")
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (total > 0 && tmp.length() != total) throw Exception("Download was incomplete")
            tmp.renameTo(dest)
        }
    }

    private val uploadHttp = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES).readTimeout(2, TimeUnit.MINUTES).build()

    suspend fun upload(name: String, size: Long, open: () -> java.io.InputStream, onProgress: (Float) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val r = Request.Builder().url("$root/api/upload?name=${Uri.encode(name)}")
                .header("X-User", user).header("X-Pass", pass).put(ProgressBody(size, open, onProgress)).build()
            uploadHttp.newCall(r).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = try { JSONObject(txt).optString("error") } catch (_: Exception) { "" }
                    throw Exception(msg.ifEmpty { "Upload failed (${resp.code})" })
                }
                JSONObject(txt).optBoolean("scanStarted")
            }
        }

    suspend fun markSeen() { req("POST", "/api/inbox/seen", "{}".toRequestBody(json)) }
}
