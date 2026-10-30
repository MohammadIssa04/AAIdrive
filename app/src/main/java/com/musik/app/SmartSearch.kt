package com.musik.app

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** A song found through a line of its lyrics ("" = found by its title written another way). */
data class LyricHit(val song: Song, val line: String, val why: String? = null)

/** What the search most likely was, and the best match to show on top. */
sealed class TopHit {
    data class ArtistTop(val a: Artist) : TopHit()
    data class SongTop(val s: Song) : TopHit()
    data class AlbumTop(val a: Album) : TopHit()
}

data class RankedSearch(
    val kind: String,                 // artist | song | album | lyrics | mixed
    val result: SearchResult,
    val lyrics: List<LyricHit>,
    val why: Map<String, String>,     // song id → "Because you listen to …"
    val mineArtists: Set<String>,     // artist ids you listen to
    val top: TopHit?,
    val partial: Boolean = false
)

/**
 * Your listening, for ranking search results personally. From the Issa server (all your devices: plays with recent
 * ones counting more, artists in your playlists) plus this phone (likes, recent plays, early skips).
 */
class Taste(
    val artists: Map<String, Pair<String, Float>>, // plain artist → (name, weight 0..1)
    val songs: Map<String, Int>,                   // song id or "title|artist" → plays
    val liked: Set<String>,                        // ids and "title|artist" keys
    val skipsSongs: Map<String, Int>, val skipsArtists: Map<String, Int>,
    val genres: Set<String>, val any: Boolean
)

/**
 * Search that understands more than the exact title:
 *  - other spellings and scripts ("فيروز" = "Fairuz" = "Fairouz"), typos, partial and incomplete titles
 *  - a line from the lyrics, or a title in another language – looked up via the Issa server (Genius)
 * and ranks by relevance first, then by YOUR listening – but only among results that really match.
 * No history: plain relevance.
 */
object SmartSearch {
    /** Common names that are written in many ways. */
    private val ALIASES = listOf(
        listOf("fairuz", "fairouz", "fayrouz", "feiruz", "فيروز"),
        listOf("umm kulthum", "oum kalthoum", "om kalthoum", "umm kalthoum", "um kalthum", "ام كلثوم", "أم كلثوم"),
        listOf("abdel halim hafez", "abdelhalim hafez", "abd el halim", "abdul halim", "عبد الحليم حافظ", "عبدالحليم"),
        listOf("amr diab", "amr diyab", "عمرو دياب"), listOf("nancy ajram", "نانسي عجرم"), listOf("elissa", "اليسا", "إليسا"),
        listOf("mohamed abdu", "mohammed abdo", "محمد عبده"), listOf("kadim al sahir", "kazem el saher", "كاظم الساهر"),
        listOf("wael kfoury", "وائل كفوري"), listOf("cairokee", "كايروكي"), listOf("wegz", "ويجز"), listOf("marwan pablo", "مروان بابلو"),
        listOf("21 savage", "twenty one savage"), listOf("travis scott", "travi\$ scott"), listOf("asap rocky", "a\$ap rocky"))

    private val AR = mapOf('ا' to "a", 'أ' to "a", 'إ' to "i", 'آ' to "a", 'ب' to "b", 'ت' to "t", 'ث' to "th", 'ج' to "j", 'ح' to "h", 'خ' to "kh",
        'د' to "d", 'ذ' to "dh", 'ر' to "r", 'ز' to "z", 'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "d", 'ط' to "t", 'ظ' to "z", 'ع' to "a", 'غ' to "gh",
        'ف' to "f", 'ق' to "q", 'ك' to "k", 'ل' to "l", 'م' to "m", 'ن' to "n", 'ه' to "h", 'و' to "ou", 'ي' to "i", 'ى' to "a", 'ة' to "a", 'ء' to "", 'ئ' to "i", 'ؤ' to "o")

    fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun hasArabic(s: String) = s.any { it in '؀'..'ۿ' }
    private fun translit(s: String) = s.map { AR[it] ?: it.toString() }.joinToString("").replace(Regex("\\s+"), " ").trim()
    /** "Song (feat. X) [Remastered] - Live" → "song" */
    private fun short(t: String): String {
        val s = t.replace(Regex("\\s*[\\(\\[][^)\\]]*[\\)\\]]"), "").replace(Regex("\\s+-\\s+.*(remaster|version|edit|mix|from|live|mono|stereo).*$", RegexOption.IGNORE_CASE), "").trim()
        return s.ifBlank { t }
    }
    private fun core(t: String) = plain(short(t))
    /** Remix, sped up, karaoke, cover … – not the original song. */
    private val VARIANT = Regex("\\b(remix(?:e[sd])?|rmx|re-?mix|sped ?up|speed ?up|slowed|reverb|nightcore|8d|karaoke|instrumental|cover|tribute|in the style of|acapella|a cappella|mashup|bootleg|extended mix|club mix|vip mix|dub mix|bass ?boosted)\\b", RegexOption.IGNORE_CASE)
    fun isVariant(title: String) = VARIANT.containsMatchIn(title)
    fun mainArtistOf(a: String) = a.split(",", "&", " feat.", " feat ", " ft.", " x ").first().trim()
    fun key(title: String, artist: String) = core(title) + "|" + plain(mainArtistOf(artist))
    fun key(s: Song) = key(s.title, s.artist)

    /** Other ways to write the search, best first (not including the search itself). */
    fun variants(q: String): List<String> {
        val p = plain(q)
        val out = mutableListOf<String>()
        ALIASES.forEach { group -> group.firstOrNull { p.contains(plain(it)) }?.let { hit -> group.filter { it != hit }.take(3).forEach { alt -> out += p.replace(plain(hit), alt) } } }
        if (hasArabic(q)) out += translit(q)
        if (p != q.lowercase().trim()) out += p // without accents and punctuation
        return out.map { it.trim() }.filter { it.length >= 2 && !it.equals(q.trim(), true) }.distinct().take(4)
    }

    /** Edit distance that gives up above [maxD]. */
    private fun dist(a: String, b: String, maxD: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > maxD) return maxD + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i; var best = i
            for (j in 1..b.length) { cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1); best = min(best, cur[j]) }
            if (best > maxD) return maxD + 1
            prev = cur
        }
        return prev[b.length]
    }

    /** How well one typed word matches: 1 exact, .85 start of a word, .7 small typo. */
    private fun wordScore(w: String, words: List<String>): Float {
        var b = 0f
        for (x in words) {
            if (x == w) return 1f
            if (w.length >= 2 && x.startsWith(w)) b = max(b, .85f)
            else if (w.length >= 4) { val m = if (w.length >= 8) 2 else 1; if (dist(w, x.take(w.length + 1), m) <= m) b = max(b, .75f) }
        }
        return b
    }

    private val STOP = setOf("with", "the", "a", "an", "of", "and", "to", "in", "on", "for", "my", "me", "you", "i", "at", "by", "is")

    /** 0..100 – how well a title/name (+ artist, album) matches the query (already plain). */
    fun match(q: String, text: String, artist: String = "", album: String = ""): Int {
        val t = core(text); val a = plain(artist); val al = plain(album)
        if (q.isEmpty() || t.isEmpty()) return 0
        if (t == q) return 100
        if (q.length >= 3 && t.startsWith(q)) return 90
        if (a.isNotEmpty() && q.startsWith("$t ") && a.contains(q.substring(t.length + 1).trim().split(" ").first())) return 97 // "creepin metro boomin"
        if (a.isNotEmpty() && q.endsWith(" $t") && a.contains(q.substring(0, q.length - t.length).trim().split(" ").first())) return 97
        // small words ("with", "the", "of") match half the catalogue – they don't count, unless the search is only small words
        val all = q.split(" ").filter { it.isNotEmpty() }
        val qw = all.filter { it !in STOP }.ifEmpty { all }; val tw = t.split(" "); val aw = a.split(" "); val lw = al.split(" ")
        var sum = 0f; var inTitle = 0
        for (w in qw) { val st = wordScore(w, tw); val sa = wordScore(w, aw); val sl = wordScore(w, lw) * .8f; val m = maxOf(st, sa, sl); sum += m; if (st >= m && st > 0) inTitle++ }
        val cov = sum / qw.size
        if (cov >= .99f) return if (inTitle > 0) 80 + min(8, inTitle * 2) else 70
        if (t.length >= 4 && q.contains(t)) return 74
        return Math.round(cov * if (inTitle > 0) 68 else 55)
    }

    /** Builds the profile. [server] is /api/taste (may be null when offline). */
    fun taste(server: JSONObject?, liked: List<Song>, likedArtists: List<String>, recent: List<Song>, skipsSongs: Map<String, Int>, skipsArtists: Map<String, Int>): Taste {
        val arts = HashMap<String, Pair<String, Float>>(); val songs = HashMap<String, Int>(); val likedK = HashSet<String>()
        val sa = server?.optJSONArray("artists")
        var maxScore = 1.0
        if (sa != null) for (i in 0 until sa.length()) maxScore = max(maxScore, sa.optJSONObject(i)?.optDouble("score", 0.0) ?: 0.0)
        if (sa != null) for (i in 0 until sa.length()) { val o = sa.optJSONObject(i) ?: continue; val n = o.optString("name")
            arts[plain(n)] = n to (ln(1 + o.optDouble("score", 0.0)) / ln(1 + maxScore)).toFloat() }
        val ss = server?.optJSONArray("songs")
        if (ss != null) for (i in 0 until ss.length()) { val o = ss.optJSONObject(i) ?: continue; val n = o.optInt("plays")
            songs[o.optString("id")] = n; val k = key(o.optString("title"), o.optString("artist")); songs[k] = max(n, songs[k] ?: 0) }
        fun bump(name: String, d: Float) { val k = plain(mainArtistOf(name)); if (k.isEmpty()) return; val x = arts[k]; arts[k] = (x?.first ?: mainArtistOf(name)) to min(1f, (x?.second ?: 0f) + d) }
        liked.forEach { likedK += it.id; likedK += key(it); bump(it.artist, .12f) }
        likedArtists.forEach { bump(it, .35f) }
        recent.forEach { bump(it.artist, .04f); val k = key(it); if (k !in songs) songs[k] = 1 }
        val genres = HashSet<String>(); server?.optJSONArray("genres")?.let { g -> for (i in 0 until g.length()) genres += g.optString(i).lowercase() }
        return Taste(arts, songs, likedK, skipsSongs, skipsArtists, genres, (server?.optInt("plays") ?: 0) > 0 || liked.isNotEmpty() || recent.isNotEmpty())
    }

    private data class Fam(val f: Float, val why: String?)
    private fun familiar(s: Song, t: Taste?, hiddenSongs: Set<String>, hiddenArtists: Set<String>): Fam {
        if (t == null) return Fam(0f, null)
        val ak = plain(mainArtistOf(s.artist)); val art = t.artists[ak]; val k = key(s)
        val plays = max(t.songs[s.id] ?: 0, t.songs[k] ?: 0)
        val liked = s.id in t.liked || k in t.liked
        val sk = t.skipsSongs[k] ?: 0
        val eff = max(0, plays - 2 * sk) // early skips cancel out plays
        var f = .45f * (art?.second ?: 0f) + .35f * min(1f, (ln(1.0 + eff) / ln(13.0)).toFloat()) + (if (liked) .25f else 0f) -
            .12f * min(3, sk) - .02f * min(10, t.skipsArtists[ak] ?: 0)
        if (s.id in hiddenSongs || mainArtistOf(s.artist).lowercase() in hiddenArtists) f -= 1f
        val why = when {
            plays >= 3 -> "You've played this $plays times"
            liked -> "In your Liked Songs"
            art != null && art.second >= .2f -> "Because you listen to ${art.first}"
            art == null && s.genre != null && s.genre.lowercase() in t.genres -> "Similar to music you play"
            else -> null
        }
        return Fam(f.coerceIn(-1f, 1f), why)
    }

    /** Ranks everything that was found. [hits]: songs found by the lyric server (line "" = title match). */
    fun rank(q: String, raw: SearchResult, hits: List<LyricHit>, t: Taste?, personal: Boolean, hiddenSongs: Set<String>, hiddenArtists: Set<String>): RankedSearch {
        val qs = (listOf(plain(q)) + variants(q).map { plain(it) }).filter { it.isNotEmpty() }.distinct()
        fun rel(text: String, artist: String = "", album: String = "") = qs.maxOf { match(it, text, artist, album) }
        val pers = personal && t != null && t.any
        val wantVariant = isVariant(q) // you searched for a remix / sped up yourself
        fun gate(r: Int) = if (r >= 60) 1f else if (r >= 40) .5f else 0f
        // one song per title + artist – the copy on your own server first
        val byKey = LinkedHashMap<String, Song>()
        raw.songs.forEach { s -> val k = if (isVariant(s.title)) plain(s.title) + "|" + plain(mainArtistOf(s.artist)) + "|v" else key(s); val o = byKey[k]; if (o == null || (o.id.startsWith("ext-") && !s.id.startsWith("ext-"))) byKey[k] = s }
        val lyr = hits.withIndex().associate { (i, h) -> key(h.song) to (h.line to i) }
        data class R(val s: Song, val rel: Int, val score: Float, val why: String?, val line: String?)
        var songs = byKey.values.map { s ->
            var r = max(rel(s.title, s.artist, s.album), if (match(qs[0], s.artist) >= 90) 72 else 0)
            val l = lyr[key(s)]
            if (l != null) r = max(r, if (l.first.isNotBlank()) 80 - l.second * 2 else 86 - l.second * 2)
            val fm = familiar(s, t, hiddenSongs, hiddenArtists)
            if (!wantVariant && isVariant(s.title)) r -= 22 // the real song first – remixes, sped up, karaoke after
            R(s, r, r + if (pers) 32f * fm.f * gate(r) else 0f, if (pers && r >= 50 && fm.f > .05f) fm.why else null, l?.first?.takeIf { it.isNotBlank() })
        }
        val strong = songs.count { it.rel >= 50 }
        songs = songs.filter { it.rel >= if (strong >= 5) 35 else 15 }.sortedByDescending { it.score }
        data class A(val a: Artist, val rel: Int, val score: Float, val mine: Boolean)
        val artists = raw.artists.distinctBy { plain(it.name) }.map { a -> val r = rel(a.name); val w = t?.artists?.get(plain(a.name))?.second ?: 0f
            A(a, r, r + if (pers) 36f * w * gate(r) else 0f, w >= .2f) }.filter { it.rel >= 45 }.sortedByDescending { it.score }
        data class B(val a: Album, val rel: Int, val score: Float)
        val albums = raw.albums.distinctBy { plain(it.name) + "|" + plain(it.artist) }.map { a -> val r = max(rel(a.name, a.artist), if (rel(a.artist) >= 90) 55 else 0)
            val w = t?.artists?.get(plain(mainArtistOf(a.artist)))?.second ?: 0f; B(a, r, r + if (pers) 30f * w * gate(r) else 0f) }.filter { it.rel >= 45 }.sortedByDescending { it.score }
        val bs = songs.firstOrNull(); val ba = artists.firstOrNull(); val bb = albums.firstOrNull()
        val words = qs[0].split(" ").size
        val kind = when {
            ba != null && ba.rel >= 90 && (bs == null || bs.rel < 97) -> "artist"
            bs != null && bs.rel >= 88 && bs.line == null -> "song"
            bb != null && bb.rel >= 90 -> "album"
            hits.isNotEmpty() && words >= 3 && (bs == null || bs.rel < 80 || bs.line != null) -> "lyrics"
            else -> "mixed"
        }
        if (kind == "artist") { val ak = plain(ba!!.a.name); songs = songs.sortedWith(compareByDescending<R> { plain(mainArtistOf(it.s.artist)) == ak }.thenByDescending { it.score }) }
        val lyricList = songs.filter { it.line != null }.map { LyricHit(it.s, it.line!!, it.why) }
        val songList = songs.filter { kind != "lyrics" || it.line == null }.map { it.s }
        val top = when (kind) { "artist" -> TopHit.ArtistTop(ba!!.a); "song" -> TopHit.SongTop(bs!!.s); "album" -> TopHit.AlbumTop(bb!!.a); else -> null }
        return RankedSearch(kind, SearchResult(artists.map { it.a }.take(10), albums.map { it.a }.take(20), songList.take(50)), lyricList,
            songs.mapNotNull { r -> r.why?.let { r.s.id to it } }.toMap(), artists.filter { it.mine }.map { it.a.id }.toSet(), top)
    }

    /** Everything at once: normal search (shown right away) + other spellings + single words (typos) + lyrics, ranked for you. */
    /* ---- lyric line → song, right here on the phone (also when the server can't help): finds the artist you typed
       ("… burning like fire inna"), reads the lyrics of their songs (lrclib.net) and looks for your line – typos are fine. */
    private fun lpWords(s: String) = plain(s.replace(Regex("['’`´]"), "")).split(" ").filter { it.isNotEmpty() }
    private fun lwMatch(w: String, x: String): Float {
        if (w == x) return 1f
        if (w.length >= 3 && (x.startsWith(w) || (x.length >= 3 && w.startsWith(x)))) return .9f
        if (w.length >= 4) { val m = if (w.length >= 8) 2 else 1; if (dist(w, x, m) <= m) return .8f }
        return 0f
    }
    /** How well the typed words match somewhere in the lyrics (0..1) and the line(s) they match. */
    fun lyricMatch(qw: List<String>, text: String): Pair<Float, String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val pl = lines.map { lpWords(it) }
        var best = 0f; var bi = 0; var bn = 1
        for (i in lines.indices) for (n in 1..3) {
            val words = pl.subList(i, min(lines.size, i + n)).flatten(); if (words.isEmpty()) continue
            var got = 0f; var pos = 0
            for (w in qw) {
                var sc = 0f; var at = -1
                for (j in pos until min(words.size, pos + 4)) { val m = lwMatch(w, words[j]); if (m > sc) { sc = m; at = j } }
                if (sc > 0) { got += sc; pos = at + 1 } else got += .5f * (words.maxOfOrNull { lwMatch(w, it) } ?: 0f)
            }
            val s = got / qw.size
            if (s > best + 1e-6f) { best = s; bi = i; bn = n }
        }
        return best to lines.subList(bi, min(lines.size, bi + bn)).joinToString(" / ")
    }
    private suspend fun localLyricScan(a: Api, q: String, lyricText: suspend (Song) -> String?): List<LyricHit> = coroutineScope {
        val words = lpWords(q); if (words.size < 4) return@coroutineScope emptyList()
        val cand = listOf(Triple(words.takeLast(2).joinToString(" "), true, 2), Triple(words.last(), true, 1),
            Triple(words.take(2).joinToString(" "), false, 2), Triple(words.first(), false, 1)).filter { it.first.length >= 2 }
        val okA = cand.map { c -> async { withTimeoutOrNull(6_000) { try { a.search(c.first).artists.any { plain(it.name) == c.first } } catch (_: Exception) { false } } ?: false } }.awaitAll()
        val hit = cand.filterIndexed { i, _ -> okA[i] }.firstOrNull() ?: return@coroutineScope emptyList()
        val (artist, atEnd, n) = hit
        val line = if (atEnd) words.dropLast(n) else words.drop(n)
        if (line.size < 3) return@coroutineScope emptyList()
        val songs = (withTimeoutOrNull(8_000) { try { a.searchSongs(artist, 60) } catch (_: Exception) { null } } ?: emptyList())
            .filter { plain(mainArtistOf(it.artist)) == artist && !isVariant(it.title) }.distinctBy { core(it.title) }.take(16)
        songs.map { s -> async {
            val t = withTimeoutOrNull(9_000) { try { lyricText(s) } catch (_: Exception) { null } } ?: return@async null
            val (sc, l) = lyricMatch(line, t)
            if (sc >= .7f) Triple(s, l, sc) else null
        } }.awaitAll().filterNotNull().sortedByDescending { it.third }.take(4).map { LyricHit(it.first, it.second) }
    }

    suspend fun run(a: Api, q: String, taste: suspend () -> Taste?, lyrics: (suspend (String) -> List<Triple<String, String, String>>)?, personal: Boolean,
                    hiddenSongs: Set<String>, hiddenArtists: Set<String>, lyricText: (suspend (Song) -> String?)? = null, onFirst: (RankedSearch) -> Unit): RankedSearch = coroutineScope {
        // 1) the normal search – shown right away
        val tasteJob = async { if (personal) try { taste() } catch (_: Exception) { null } else null }
        val first = a.search(q)
        val t0 = withTimeoutOrNull(300) { tasteJob.await() } // never wait long for the profile
        val firstRanked = rank(q, first, emptyList(), t0, personal, hiddenSongs, hiddenArtists)
        onFirst(firstRanked.copy(partial = true))
        val words = plain(q).split(" ").filter { it.isNotEmpty() }
        val vs = variants(q)
        val needMore = first.songs.size < 10 || hasArabic(q) || vs.isNotEmpty()
        val lyricLike = lyrics != null && words.size >= 4
        if (!needMore && !lyricLike) return@coroutineScope rank(q, first, emptyList(), tasteJob.await(), personal, hiddenSongs, hiddenArtists)
        // 2) the extra work waits until you stop typing (a new letter cancels this whole search), so typing never floods the servers
        kotlinx.coroutines.delay(650)
        val lyricsJob = async { if (!lyricLike) emptyList() else withTimeoutOrNull(15_000) { try { lyrics!!(q) } catch (_: Exception) { null } } ?: emptyList() }
        // few results: maybe a typo – also try the start of each longer word ("creepn" → "creep", "crep")
        val typo = if (first.songs.size < 5 && words.size <= 3) words.filter { it.length >= 4 }.flatMap { w -> listOfNotNull(w, if (w.length >= 5) w.dropLast(1) else null, if (w.length >= 6) w.take(4) else null) } else emptyList()
        val extra = if (!needMore) emptyList() else (vs + typo).distinct().filter { it != plain(q) }.take(4).map { v -> async { withTimeoutOrNull(8_000) { try { a.search(v) } catch (_: Exception) { null } } } }.awaitAll().filterNotNull()
        val t = tasteJob.await()
        // lyric/title matches → the playable copy: title AND artist must fit. Your artists are looked up first.
        val lines = lyricsJob.await().take(12).withIndex()
            .sortedByDescending { (i, l) -> (t?.artists?.get(plain(mainArtistOf(l.second)))?.second ?: 0f) * 6 - i }.map { it.value }.take(4)
        val found = lines.map { (title, artist, line) -> async {
            val ct = core(title); val ca = plain(mainArtistOf(artist))
            fun fits(x: Song): Boolean { val xt = core(x.title); val xa = plain(x.artist)
                return (xt == ct || (ct.length >= 4 && (xt.startsWith(ct) || ct.startsWith(xt)))) && (ca.isEmpty() || xa.contains(ca.split(" ").first())) }
            var hit: LyricHit? = null
            for (qq in listOf("${short(title)} ${mainArtistOf(artist)}", short(title))) { // title + artist first, then the title alone
                val r = withTimeoutOrNull(8_000) { try { a.searchSongs(qq, 15) } catch (_: Exception) { null } } ?: emptyList()
                r.filter { fits(it) }.sortedBy { isVariant(it.title) }.firstOrNull()?.let { hit = LyricHit(it, line) }
                if (hit != null) break
            }
            hit
        } }.awaitAll().filterNotNull()
        // 3) only if the server found nothing: look in the lyrics right here on the phone (an old server, or it's unreachable)
        val mine = if (lyricLike && found.none { it.line.isNotBlank() } && lyricText != null)
            (try { withTimeoutOrNull(12_000) { localLyricScan(a, q, lyricText) } } catch (_: Exception) { null } ?: emptyList()) else emptyList()
        val all = mine + found
        val raw = SearchResult(first.artists + extra.flatMap { it.artists }, first.albums + extra.flatMap { it.albums },
            first.songs + extra.flatMap { it.songs } + all.map { it.song })
        rank(q, raw, all, t, personal, hiddenSongs, hiddenArtists)
    }
}
