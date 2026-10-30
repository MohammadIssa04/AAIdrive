package com.musik.app

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import org.json.JSONArray

data class EqInfo(val bands: Int, val freqs: List<Int>, val min: Int, val max: Int)

@UnstableApi
class PlaybackService : MediaLibraryService() {

    companion object {
        /** The live player, so the widget and sleep timer can reach it. */
        var player: ExoPlayer? = null
            private set
        /** Wall-clock time (ms) when the sleep timer pauses playback, 0 = off. */
        var sleepAt = 0L
        var sleepEndOfTrack = false
        var eqInfo: EqInfo? = null
            private set
        var widgetCover: Bitmap? = null
        /** Audio session of the main player (for the visualizer). */
        var audioSession = C.AUDIO_SESSION_ID_UNSET
            private set
        /** In someone else's Jam: no crossfade, the host decides when songs change. */
        @Volatile var jamGuest = false
        /** True while two songs are blending (a Jam guest must not jump around then). */
        @Volatile var crossfading = false
        /** While two songs blend, the song that's fading in – the app and the car already show it. */
        var xfItem by androidx.compose.runtime.mutableStateOf<MediaItem?>(null)
            private set
        /** Your liked songs (kept up to date by the app) so the car's "Like" button shows the right heart. */
        @Volatile var likedIds: Set<String> = emptySet()
        var onLikedInCar: ((String, Boolean) -> Unit)? = null
        var refreshCarButtons: (() -> Unit)? = null
        private var sideRef: ExoPlayer? = null
        /** (position, duration) of the song that's fading in, or null when no crossfade is running. */
        fun xfProgress(): Pair<Long, Long>? { if (xfItem == null) return null; val s = sideRef ?: return null
            return s.currentPosition to (if (s.duration > 0) s.duration else 0L) }
        /** Exact position of what you hear – read straight from the player (no delay through the session),
         *  during a crossfade from the song fading in. (position, duration, mediaId) or null if nothing plays. */
        fun audiblePosition(): Triple<Long, Long, String?>? {
            val x = xfItem; val s = sideRef
            if (x != null && s != null) return Triple(s.currentPosition, if (s.duration > 0) s.duration else 0L, x.mediaId)
            val p = player ?: return null
            if (p.mediaItemCount == 0) return null
            return Triple(p.currentPosition, if (p.duration > 0) p.duration else 0L, p.currentMediaItem?.mediaId)
        }
        const val ACTION_CAR_RESUME = "issa.CAR_RESUME"
        const val CMD_LIKE = "issa.LIKE"
        const val CMD_MORE = "issa.MORE_LIKE_THIS"
        /** The host's fade length while you're a Jam guest (-1 = use your own). */
        @Volatile var jamFadeSec = -1
    }

    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("musik", MODE_PRIVATE) }
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private val cache = HashMap<String, MediaItem>()

    private fun api(): Api? {
        val b = prefs.getString("base", null) ?: return null
        val u = prefs.getString("user", null) ?: return null
        val p = prefs.getString("pass", null) ?: return null
        return Api(b, u, p).also { it.maxBitRate = currentBitrate(this, prefs) }
    }

    /* ---------------- crossfade + sleep timer ----------------
     * Real crossfade: a second "side" player starts the next song while the current one fades out (equal-power curves).
     * When the fade is done the two players SWAP roles – the side player, already playing the new song, becomes the
     * main player of the session and gets the rest of the queue. Nothing is reloaded or seeked at the end of the fade,
     * so there is no gap, no jump and no stutter. (Before 4.3 the main player re-loaded the new song and seeked to
     * where the side player was – that re-buffer + inexact seek was the lag at the end of every fade.)
     * The fade clock is the new song's own playback position, so pausing or buffering simply holds the fade.
     */

    private lateinit var sourceFactory: DefaultMediaSourceFactory
    private val musicAttrs = AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build()
    private var side: ExoPlayer? = null
    private var xfMs = 0L            // fade length
    private var xfFrom = -1L         // side position when the fade really started (-1 = new song not playing yet)
    private var xfPhase = 0          // 0 none, 1 fading (side plays the next song), 2 swapped – the new main finishes rising to full volume
    private var finStart = 0L
    private var finFrom = 1f
    private var finMs = 0L

    /** Big buffer: the whole song loads early, so the next one has time to load before the change (no stutter). */
    private fun loadControl() = androidx.media3.exoplayer.DefaultLoadControl.Builder()
        .setBufferDurationsMs(30_000, 240_000, 1_500, 2_500).setPrioritizeTimeOverSizeThresholds(true).build()

    private fun sidePlayer(): ExoPlayer = side ?: ExoPlayer.Builder(this).setMediaSourceFactory(sourceFactory).setLoadControl(loadControl())
        .setAudioAttributes(musicAttrs, false).build().also { s ->
            player?.let { p -> if (p.audioSessionId != C.AUDIO_SESSION_ID_UNSET) s.setAudioSessionId(p.audioSessionId) }
            s.addListener(sideListener)
            side = s
        }

    /** On whichever player is the side one: if the next song can't load during a crossfade, keep playing the current one. */
    private val sideListener = object : Player.Listener { override fun onPlayerError(error: PlaybackException) { cancelCrossfade() } }

    // Playback recovery: a network hiccup retries the same song from the same spot (twice),
    // a song that can't be played is skipped – but never more than a few in a row.
    private var retryId: String? = null
    private var retryCount = 0
    private var failStreak = 0
    private fun recover(p: ExoPlayer, error: PlaybackException) {
        cancelCrossfade()
        val id = p.currentMediaItem?.mediaId
        if (id != retryId) { retryId = id; retryCount = 0 }
        val network = error.errorCode in 2000..2999
        if (network && retryCount < 2) {
            retryCount++
            val pos = p.currentPosition
            handler.postDelayed({ if (p.currentMediaItem?.mediaId == id) { p.seekTo(pos); p.prepare(); p.play() } }, 1500L * retryCount)
            return
        }
        failStreak++
        if (failStreak < 3 && p.hasNextMediaItem()) handler.postDelayed({ p.seekToNextMediaItem(); p.prepare(); p.play() }, 900)
        else failStreak = 0
    }

    private fun knownDuration(p: ExoPlayer): Long =
        if (p.duration > 0) p.duration else (p.currentMediaItem?.mediaMetadata?.extras?.getInt("duration") ?: 0) * 1000L

    private fun startCrossfade(p: ExoPlayer, ms: Long) {
        val next = p.nextMediaItemIndex
        if (next < 0 || xfPhase != 0) return
        val s = sidePlayer(); sideRef = s
        val nextItem = p.getMediaItemAt(next)
        if (sidePreparedFor != nextItem.mediaId || s.currentMediaItem?.mediaId != nextItem.mediaId || s.playbackState == Player.STATE_IDLE) {
            s.setMediaItem(nextItem); s.prepare(); sidePreparedFor = nextItem.mediaId
        } else if (s.currentPosition > 500) s.seekTo(0)
        s.volume = 0f; s.playWhenReady = p.playWhenReady
        xfMs = ms; xfFrom = -1; xfPhase = 1
        // show the next song (cover, title, artist) right away – on the phone, the lock screen and in the car
        xfItem = nextItem; car?.refreshMetadata(); refreshCarButtons?.invoke()
    }

    /** The fade is done (or the user skipped / seeked during it): the side player becomes the main player. */
    private fun swapPlayers(rampMs: Long) {
        val old = player ?: return
        val new = side ?: return cancelCrossfade()
        val id = new.currentMediaItem?.mediaId ?: return cancelCrossfade()
        val n = when (id) {
            old.currentMediaItem?.mediaId -> old.currentMediaItemIndex
            old.getMediaItemAtOrNull(old.nextMediaItemIndex)?.mediaId -> old.nextMediaItemIndex
            else -> -1
        }
        if (n < 0 || new.playbackState == Player.STATE_IDLE) return cancelCrossfade()
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        // the new song keeps playing untouched – only the rest of the queue is added around it
        if (n > 0) new.addMediaItems(0, items.subList(0, n))
        if (n + 1 < items.size) new.addMediaItems(items.subList(n + 1, items.size))
        new.repeatMode = old.repeatMode
        if (old.shuffleModeEnabled && items.size > 1) {
            val tl = old.currentTimeline; val order = ArrayList<Int>()
            var w = tl.getFirstWindowIndex(true)
            while (w != C.INDEX_UNSET && order.size < items.size) { order += w; w = tl.getNextWindowIndex(w, Player.REPEAT_MODE_OFF, true) }
            if (order.size == items.size) new.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
            new.shuffleModeEnabled = true
        }
        // the old player goes quiet and becomes the side player
        val wasVol = new.volume
        old.removeListener(mainListener); new.removeListener(sideListener); new.addListener(mainListener)
        // hand the session (lock screen, notification, car) to the new player FIRST – while the old one still has the song –
        // so they never see an empty player for a moment (that showed the phone's name instead of the song)
        player = new; side = old; sideRef = null; sidePreparedFor = null
        val c = CarPlayer(new); car = c
        xfItem = null
        session?.setPlayer(c)
        // now the old player goes quiet and becomes the side player
        old.volume = 0f; old.stop(); old.clearMediaItems()
        old.setAudioAttributes(musicAttrs, false); old.setHandleAudioBecomingNoisy(false); old.setWakeMode(C.WAKE_MODE_NONE)
        old.addListener(sideListener)
        new.setAudioAttributes(musicAttrs, true); new.setHandleAudioBecomingNoisy(prefs.getBoolean("noisy_pause", true)); new.setWakeMode(C.WAKE_MODE_NETWORK)
        // finish the volume rise smoothly from wherever the fade was
        val full = (appGain(prefs) * sleepFactor()).coerceAtLeast(0.0001f)
        finFrom = (wasVol / full).coerceIn(0f, 1f); finStart = System.currentTimeMillis(); finMs = rampMs
        xfPhase = if (finFrom >= 0.999f || rampMs <= 0) 0 else 2
        crossfading = xfPhase != 0
        if (xfPhase == 0) new.volume = full
        c.refreshMetadata(); lastButtonsLiked = null; updateCarButtons(); saveQueue(new)
        handler.postDelayed({ if (car === c) c.refreshMetadata() }, 400) // and once more when everything has settled
    }

    private fun Player.getMediaItemAtOrNull(i: Int): MediaItem? = if (i in 0 until mediaItemCount) getMediaItemAt(i) else null

    private var sidePreparedFor: String? = null
    private var warmedFor: String? = null
    /** About half a minute before a song ends: get the next one ready, so the change is instant.
     *  1) ask the server for its first bytes – the proxy then fetches Deezer songs before they're needed
     *  2) with crossfade on, load it in the side player (silent and paused) so the fade starts right away. */
    private fun prewarmNext(p: ExoPlayer, fadeMs: Long) {
        if (xfPhase != 0 || p.mediaItemCount < 2 || !p.hasNextMediaItem()) return
        val dur = knownDuration(p); if (dur <= 0) return
        val left = dur - p.currentPosition
        val next = p.getMediaItemAt(p.nextMediaItemIndex)
        if (left < 45_000 && warmedFor != next.mediaId) {
            warmedFor = next.mediaId
            val uri = next.localConfiguration?.uri
            if (uri != null && uri.scheme?.startsWith("http") == true) scope.launch(Dispatchers.IO) {
                try {
                    val c = java.net.URL(uri.toString()).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 20_000; c.readTimeout = 120_000; c.setRequestProperty("Range", "bytes=0-65535")
                    c.inputStream.use { ins -> val buf = ByteArray(16_384); var n = 0; while (n < 65_536) { val r = ins.read(buf); if (r < 0) break; n += r } }
                    c.disconnect()
                } catch (_: Exception) {}
            }
        }
        if (fadeMs > 0 && left < fadeMs + 20_000 && sidePreparedFor != next.mediaId && p.repeatMode != Player.REPEAT_MODE_ONE && !sleepEndOfTrack) {
            val s = sidePlayer()
            s.playWhenReady = false; s.volume = 0f
            s.setMediaItem(next); s.prepare()
            sidePreparedFor = next.mediaId
        }
    }

    private fun cancelCrossfade() {
        if (xfPhase == 1 || side?.isPlaying == true) side?.let { it.stop(); it.clearMediaItems(); sidePreparedFor = null }
        xfPhase = 0; xfFrom = -1; crossfading = false
        if (xfItem != null) { xfItem = null; car?.refreshMetadata() }
        player?.volume = (appGain(prefs) * sleepFactor()).coerceIn(0f, 1f)
    }

    private fun sleepFactor(): Float {
        if (sleepAt <= 0) return 1f
        val left = sleepAt - System.currentTimeMillis()
        return if (left in 1 until 15_000) left / 15_000f else 1f
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val now = System.currentTimeMillis()
                var sleepFactor = 1f
                if (sleepAt > 0) {
                    val left = sleepAt - now
                    if (left <= 0) { p.pause(); side?.pause(); sleepAt = 0 } else if (left < 15_000) sleepFactor = left / 15_000f
                }
                val fadeMs = (if (jamGuest && jamFadeSec >= 0) jamFadeSec else prefs.getInt("fade", 0)) * 1000L
                crossfading = xfPhase != 0
                prewarmNext(p, fadeMs)
                if (xfPhase == 0 && fadeMs > 0 && p.isPlaying && p.repeatMode != Player.REPEAT_MODE_ONE && p.hasNextMediaItem() && !sleepEndOfTrack) {
                    val dur = knownDuration(p)
                    if (dur > 0) {
                        // short songs get a shorter fade (never more than a third of either song)
                        val nextDur = (p.getMediaItemAtOrNull(p.nextMediaItemIndex)?.mediaMetadata?.extras?.getInt("duration") ?: 0) * 1000L
                        val f = minOf(fadeMs, dur / 3, if (nextDur > 0) nextDur / 3 else fadeMs)
                        val left = dur - p.currentPosition
                        if (left in 400..f) startCrossfade(p, left)
                    }
                }
                val gain = appGain(prefs) * sleepFactor // the app's own volume (fine steps for very quiet listening) × sleep fade
                var target = gain
                when (xfPhase) {
                    1 -> {
                        val sd = side
                        val nx = p.getMediaItemAtOrNull(p.nextMediaItemIndex)?.mediaId
                        if (sd == null || (nx != sd.currentMediaItem?.mediaId && p.currentMediaItem?.mediaId != sd.currentMediaItem?.mediaId)) cancelCrossfade() // queue changed
                        else {
                            val left = knownDuration(p) - p.currentPosition
                            if (xfFrom < 0 && sd.isPlaying) { // the new song really started: start the fade clock now
                                xfFrom = sd.currentPosition; xfMs = minOf(xfMs, left).coerceAtLeast(1)
                            }
                            val prog = if (xfFrom < 0) 0f else ((sd.currentPosition - xfFrom).toFloat() / xfMs).coerceIn(0f, 1f)
                            val half = (Math.PI / 2).toFloat()
                            target = gain * kotlin.math.cos(prog * half)
                            val sv = (gain * kotlin.math.sin(prog * half)).coerceIn(0f, 1f)
                            if (kotlin.math.abs(sd.volume - sv) > 0.0005f) sd.volume = sv
                            if (xfFrom >= 0 && (prog >= 1f || left <= 60)) { swapPlayers(if (prog >= 1f) 0 else 250); handler.postDelayed(this, 50); return }
                        }
                    }
                    2 -> {
                        val h = ((now - finStart).toFloat() / finMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                        target = gain * (finFrom + (1f - finFrom) * h)
                        if (h >= 1f) { xfPhase = 0; crossfading = false }
                    }
                }
                if (kotlin.math.abs(p.volume - target) > 0.0005f) p.volume = target.coerceIn(0f, 1f)
                saveTicks++
                if (saveTicks >= 300) { saveTicks = 0; if (p.isPlaying) saveQueue(p) } // every 15 s while playing
            }
            handler.postDelayed(this, 50)
        }
    }

    /* ---------------- equalizer / bass boost ---------------- */

    private var saveTicks = 0
    private var leveler: android.media.audiofx.DynamicsProcessing? = null

    /** Remembers the queue so it can come back after the app is closed. */
    private var saveJob: kotlinx.coroutines.Job? = null
    /** Remembers the queue for next time – bundled (many changes in a row = one save) and written off the main thread. */
    private fun saveQueue(p: Player) {
        if (jamGuest || p.mediaItemCount == 0) return // a Jam guest's queue belongs to the host – keep your own
        saveJob?.cancel()
        saveJob = scope.launch {
            kotlinx.coroutines.delay(800)
            try {
                val songs = (0 until minOf(p.mediaItemCount, 500)).map { p.getMediaItemAt(it).toSong() }
                val idx = p.currentMediaItemIndex; val pos = p.currentPosition; val sh = p.shuffleModeEnabled; val rp = p.repeatMode
                kotlinx.coroutines.withContext(Dispatchers.Default) {
                    val a = JSONArray(); songs.forEach { a.put(it.toJson()) }
                    prefs.edit().putString("saved_queue", a.toString()).putInt("saved_index", idx)
                        .putLong("saved_pos", pos).putBoolean("saved_shuffle", sh).putInt("saved_repeat", rp).apply()
                }
            } catch (_: Exception) {}
        }
    }

    private fun attachEffects(sessionId: Int) {
        releaseEffects()
        audioSession = sessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        if (android.os.Build.VERSION.SDK_INT >= 28) try {
            // "Even out volume": a gentle compressor + limiter, so quiet and loud songs end up closer together
            val dp = android.media.audiofx.DynamicsProcessing(0, sessionId,
                android.media.audiofx.DynamicsProcessing.Config.Builder(android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2, false, 0, true, 1, false, 0, true).build())
            dp.setMbcBandAllChannelsTo(0, android.media.audiofx.DynamicsProcessing.MbcBand(true, 20000f, 15f, 300f, 3f, -24f, 6f, -90f, 1f, 0f, 5f))
            dp.setLimiterAllChannelsTo(android.media.audiofx.DynamicsProcessing.Limiter(true, true, 0, 1f, 80f, 10f, -2f, 0f))
            leveler = dp
        } catch (_: Throwable) { leveler = null }
        try {
            val e = Equalizer(0, sessionId)
            val r = e.bandLevelRange
            eqInfo = EqInfo(e.numberOfBands.toInt(), (0 until e.numberOfBands).map { e.getCenterFreq(it.toShort()) / 1000 }, r[0].toInt(), r[1].toInt())
            eq = e
        } catch (_: Throwable) { eqInfo = null }
        try { bass = BassBoost(0, sessionId) } catch (_: Throwable) {}
        applyEffects()
    }

    private fun applyEffects() {
        val on = prefs.getBoolean("eq_on", false)
        val treble = prefs.getInt("treble", 0)
        try {
            eq?.let { e ->
                val levels = prefs.getString("eq_levels", "")!!.split(",").mapNotNull { it.trim().toIntOrNull() }
                val r = e.bandLevelRange
                for (b in 0 until e.numberOfBands) {
                    val hz = e.getCenterFreq(b.toShort()) / 1000
                    val t = if (hz >= 6000) treble else if (hz >= 2500) treble / 2 else 0 // treble lifts the top bands
                    e.setBandLevel(b.toShort(), ((levels.getOrNull(b) ?: 0) + t).coerceIn(r[0].toInt(), r[1].toInt()).toShort())
                }
                e.enabled = on || treble != 0
            }
        } catch (_: Throwable) {}
        try { leveler?.enabled = prefs.getBoolean("leveling", false) } catch (_: Throwable) {}
        try {
            bass?.let { b ->
                val s = prefs.getInt("bass", 0).coerceIn(0, 1000)
                if (b.strengthSupported) b.setStrength(s.toShort())
                b.enabled = on && s > 0
            }
        } catch (_: Throwable) {}
    }

    private fun releaseEffects() {
        try { eq?.release() } catch (_: Throwable) {}
        try { bass?.release() } catch (_: Throwable) {}
        try { leveler?.release() } catch (_: Throwable) {}
        eq = null; bass = null; leveler = null
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "eq_on" || key == "eq_levels" || key == "bass" || key == "treble" || key == "leveling") applyEffects()
        if (key == "noisy_pause") player?.setHandleAudioBecomingNoisy(prefs.getBoolean("noisy_pause", true))
        if (key == "car_short" || key == "car_next") car?.refreshMetadata()
        if (key == "car_queue") session?.let { s -> s.mediaNotificationControllerInfo?.let { c ->
            s.setAvailableCommands(c, MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(androidx.media3.session.SessionCommand(CMD_LIKE, Bundle.EMPTY)).add(androidx.media3.session.SessionCommand(CMD_MORE, Bundle.EMPTY)).build(),
                playerCommandsFor(Player.Commands.Builder().addAllCommands().build())) } }
    }

    /* ---------------- items for Android Auto ---------------- */

    private fun songItem(s: Song, a: Api): MediaItem {
        val extras = Bundle().apply {
            putString("albumId", s.albumId); putString("artistId", s.artistId); putString("coverArt", s.coverArt); putInt("duration", s.duration); putString("genre", s.genre)
        }
        val local = Downloads.file(this, s.id)
        val uri = if (local != null) Uri.fromFile(local) else Uri.parse(a.streamUrl(s.id))
        val md = MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album)
            .setArtworkUri(a.coverUrl(s.coverArt, 512)?.let { Uri.parse(it) }).setExtras(extras)
            .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
        return MediaItem.Builder().setMediaId(s.id).setUri(uri).setMediaMetadata(md)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build()).build().also { cache[s.id] = it }
    }

    private fun folder(id: String, title: String, art: Uri? = null) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtworkUri(art).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS).build()
    ).build()

    private fun history(): List<Song> = try {
        val a = JSONArray(prefs.getString("history", "[]")); (0 until a.length()).mapNotNull { songFromJson(a.optJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    /** The friends server (for Made for you, your own playlists, Right now and Friends' Top 10 in the car). */
    private fun social(): Social? {
        val u = prefs.getString("user", null) ?: return null
        val p = prefs.getString("pass", null) ?: return null
        val base = prefs.getString("social", null) ?: Uri.parse((prefs.getString("base", null) ?: "http://192.168.0.14:5274").trim()).let { "${it.scheme ?: "http"}://${it.host ?: "192.168.0.14"}:5280" }
        return Social(base, u, p)
    }

    /** Songs of each list shown in the car, so picking a song plays the whole list from there. */
    private val listSongs = HashMap<String, List<Song>>()
    private val parentOf = HashMap<String, String>()
    private fun songs(parent: String, list: List<Song>, a: Api): List<MediaItem> {
        listSongs[parent] = list; list.forEach { parentOf[it.id] = parent }
        return list.map { songItem(it, a) }
    }
    private fun folderOf(id: String, title: String, sub: String?, art: String?, a: Api) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setSubtitle(sub).setArtist(sub).setArtworkUri(a.coverUrl(art, 300)?.let(Uri::parse))
            .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build().also { cache[id] = it }

    private suspend fun allPlaylists(a: Api, so: Social?): List<Playlist> {
        val own = try { so?.lists() ?: emptyList() } catch (_: Exception) { emptyList() }
        val nav = try { a.playlists().filter { it.owner.isEmpty() || it.owner.equals(a.user, true) || it.public } } catch (_: Exception) { emptyList() }
        return own + nav
    }

    private suspend fun children(parentId: String): List<MediaItem> {
        val a = api() ?: return emptyList()
        val so = social()
        return when {
            parentId == "root" -> listOfNotNull(
                if (so != null) folder("now", "Right now") else null,
                if (so != null) folder("made", "Made for you") else null,
                folder("liked", "Liked Songs"), folder("recent", "Recently played"), folder("playlists", "Playlists"),
                if (so != null) folder("friends", "Friends' Top 10") else null,
                folder("downloads", "Downloaded"))
            parentId == "liked" -> songs(parentId, a.starred().songs, a)
            parentId == "recent" -> songs(parentId, history().take(40), a)
            parentId == "downloads" -> songs(parentId, Downloads.list(this), a)
            parentId == "now" -> {
                val cal = java.util.Calendar.getInstance()
                val j = so?.moment(cal.get(java.util.Calendar.HOUR_OF_DAY), (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7)
                val arr = j?.optJSONArray("songs") ?: JSONArray()
                songs(parentId, (0 until arr.length()).mapNotNull { songFromJson(arr.optJSONObject(it)) }.take(50), a)
            }
            parentId == "made" -> {
                val ids = try { so?.genpl()?.map { it.id }?.toSet() ?: emptySet() } catch (_: Exception) { emptySet() }
                allPlaylists(a, so).filter { it.id in ids }.map { folderOf("pl:" + it.id, it.name, "${it.songCount} songs", it.coverArt, a) }
            }
            parentId == "playlists" -> allPlaylists(a, so).map { folderOf("pl:" + it.id, it.name, "${it.songCount} songs", it.coverArt, a) }
            parentId.startsWith("pl:") -> { val id = parentId.removePrefix("pl:"); songs(parentId, if (id.startsWith("il-") && so != null) so.list(id).second else a.playlist(id).second, a) }
            parentId == "friends" -> (so?.users() ?: emptyList()).filter { it.name != so?.me }.map { f -> folderOf("top:" + f.name, "${f.display}'s Top 10", if (f.playing) "Listening now" else null, f.song?.coverArt, a) }
            parentId.startsWith("top:") -> songs(parentId, so?.top(parentId.removePrefix("top:"))?.map { it.song } ?: emptyList(), a)
            parentId.startsWith("artist:") -> a.artist(parentId.removePrefix("artist:")).second.map { al -> folderOf("album:" + al.id, al.name, al.year?.toString(), al.coverArt, a) }
            parentId.startsWith("album:") -> songs(parentId, a.album(parentId.removePrefix("album:")).second, a)
            else -> emptyList()
        }
    }

    /** Search in iDrive: artists and albums (open them) and playlists first, then songs. */
    private suspend fun searchItems(query: String): List<MediaItem> {
        val a = api() ?: return emptyList()
        val r = a.search(query)
        val q = query.lowercase()
        val pls = try { allPlaylists(a, social()).filter { it.name.lowercase().contains(q) }.take(3) } catch (_: Exception) { emptyList() }
        return r.artists.take(3).map { folderOf("artist:" + it.id, it.name, "Artist", it.coverArt, a) } +
            r.albums.take(4).map { folderOf("album:" + it.id, it.name, it.artist, it.coverArt, a) } +
            pls.map { folderOf("pl:" + it.id, it.name, "Playlist", it.coverArt, a) } +
            songs("search:$query", r.songs.take(25), a)
    }

    private val callback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(folder("root", "Issa Music"), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val list = try { children(parentId) } catch (_: Exception) { emptyList() }
            LibraryResult.ofItemList(ImmutableList.copyOf(list), params)
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = cache[mediaId] ?: return Futures.immediateFuture(LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val n = try { searchItems(query).size } catch (_: Exception) { 0 }
                session.notifySearchResultChanged(browser, query, n, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val list = try { searchItems(query) } catch (_: Exception) { emptyList() }
            LibraryResult.ofItemList(ImmutableList.copyOf(list), params)
        }

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            val cmds = base.availableSessionCommands.buildUpon()
                .add(androidx.media3.session.SessionCommand(CMD_LIKE, Bundle.EMPTY)).add(androidx.media3.session.SessionCommand(CMD_MORE, Bundle.EMPTY)).build()
            // the notification controller also decides what the car and Bluetooth get (e.g. whether they see the whole queue)
            val players = if (session.isMediaNotificationController(controller)) playerCommandsFor(base.availablePlayerCommands) else base.availablePlayerCommands
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(cmds).setAvailablePlayerCommands(players).build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: androidx.media3.session.SessionCommand, args: Bundle): ListenableFuture<androidx.media3.session.SessionResult> {
            val p = player
            val item = car?.currentMediaItem
            Feedback.command(customCommand.customAction)
            when (customCommand.customAction) {
                CMD_LIKE -> if (item != null) {
                    val on = item.mediaId !in likedIds
                    likedIds = if (on) likedIds + item.mediaId else likedIds - item.mediaId
                    updateCarButtons()
                    onLikedInCar?.invoke(item.mediaId, on)
                    scope.launch { try { api()?.star(item.mediaId, on) } catch (_: Exception) {} }
                }
                CMD_MORE -> if (p != null && item != null) {
                    // keep only the song that's playing, then fill up with music like it
                    val cur = p.currentMediaItemIndex
                    if (cur + 1 < p.mediaItemCount) p.removeMediaItems(cur + 1, p.mediaItemCount)
                    val a = api(); val artist = item.mediaMetadata.artist?.toString()?.split(",", "&", " feat")?.firstOrNull()?.trim()
                    if (a != null && !artist.isNullOrBlank()) scope.launch {
                        try {
                            val have = setOf(item.mediaId)
                            val found = a.searchSongs(artist, 40).filter { it.id !in have && !SmartSearch.isVariant(it.title) }.shuffled().take(20)
                            if (found.isNotEmpty()) p.addMediaItems(found.map { songItem(it, a) })
                        } catch (_: Exception) {}
                    }
                }
            }
            return Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
        }

        // Picking a song in the car plays the whole list (album, playlist, …) from that song – and "play X" searches
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val fromOtherApp = controller.packageName != packageName
            val one = mediaItems.singleOrNull()
            val query = one?.requestMetadata?.searchQuery
            if (fromOtherApp && one != null && (!query.isNullOrBlank() || parentOf[one.mediaId] != null)) return scope.future {
                val a = api()
                if (a != null && !query.isNullOrBlank()) {
                    val r = try { a.search(query) } catch (_: Exception) { null }
                    val first = r?.songs?.firstOrNull()
                    val more = if (first != null) try { a.searchSongs(first.artist.split(",", "&").first().trim(), 30).filter { it.id != first.id }.shuffled().take(20) } catch (_: Exception) { emptyList() } else emptyList()
                    val list = listOfNotNull(first) + more
                    MediaSession.MediaItemsWithStartPosition(list.map { songItem(it, a) }, 0, 0)
                } else {
                    val list = listSongs[parentOf[one.mediaId]] ?: emptyList()
                    val at = list.indexOfFirst { it.id == one.mediaId }
                    if (a != null && at >= 0) MediaSession.MediaItemsWithStartPosition(list.map { songItem(it, a) }, at, startPositionMs.coerceAtLeast(0))
                    else MediaSession.MediaItemsWithStartPosition(onAddMediaItems(mediaSession, controller, mediaItems).get(), startIndex, startPositionMs)
                }
            }
            return Futures.transform(onAddMediaItems(mediaSession, controller, mediaItems), { MediaSession.MediaItemsWithStartPosition(it, startIndex, startPositionMs) }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        }

        // Play pressed in the car (or on headphones) while the app was closed: continue where you left off
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val a = api() ?: throw IllegalStateException("not signed in")
            val arr = try { JSONArray(prefs.getString("saved_queue", "[]")) } catch (_: Exception) { JSONArray() }
            val list = (0 until arr.length()).mapNotNull { songFromJson(arr.optJSONObject(it)) }
            if (list.isEmpty()) throw IllegalStateException("nothing to resume")
            MediaSession.MediaItemsWithStartPosition(list.map { songItem(it, a) }, prefs.getInt("saved_index", 0).coerceIn(0, list.size - 1), prefs.getLong("saved_pos", 0))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val a = api()
            val fixed = mediaItems.map { item ->
                when {
                    item.localConfiguration != null -> item
                    item.requestMetadata.mediaUri != null -> item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
                    cache[item.mediaId] != null -> cache[item.mediaId]!!
                    a != null -> {
                        val local = Downloads.file(this@PlaybackService, item.mediaId)
                        item.buildUpon().setUri(if (local != null) Uri.fromFile(local) else Uri.parse(a.streamUrl(item.mediaId))).build()
                    }
                    else -> item
                }
            }.toMutableList()
            return Futures.immediateFuture(fixed)
        }
    }

    /* ---------------- widget ---------------- */

    private var lastCoverKey: String? = null
    private fun updateWidget() {
        val p = player ?: return
        PlayerWidget.push(this)
        val art = p.currentMediaItem?.mediaMetadata?.artworkUri?.toString()
        if (art != lastCoverKey) {
            lastCoverKey = art
            if (art == null) { widgetCover = null; PlayerWidget.push(this); return }
            scope.launch {
                val r = imageLoader.execute(ImageRequest.Builder(this@PlaybackService).data(art).size(300).allowHardware(false).build())
                widgetCover = ((r as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                PlayerWidget.push(this@PlaybackService)
            }
        }
    }

    /* ---------------- lifecycle ---------------- */

    /** Listens to whichever player is the main one (it moves over when the players swap after a crossfade). */
    private val mainListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            attachEffects(audioSessionId)
            side?.setAudioSessionId(audioSessionId)
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            refreshCarButtons?.invoke()
            if (sleepEndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) { sleepEndOfTrack = false; p.pause(); cancelCrossfade(); return }
            if (xfPhase == 1) {
                val s = side
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && s != null && s.currentMediaItem?.mediaId == mediaItem?.mediaId) {
                    // the old song ended a moment before the fade did: hand over now (the new song is already playing)
                    if (s.isPlaying || s.playbackState == Player.STATE_READY) { p.volume = 0f; swapPlayers(250) }
                    else cancelCrossfade() // the new song is still loading – the main player just plays it normally
                } else cancelCrossfade()   // user picked another song
            } else if (xfPhase == 2 && reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) { xfPhase = 0; crossfading = false }
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (xfPhase == 1) side?.playWhenReady = playWhenReady // pausing holds the fade where it is
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && xfPhase == 1 && oldPosition.mediaItemIndex == newPosition.mediaItemIndex) cancelCrossfade()
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED && sleepEndOfTrack) sleepEndOfTrack = false
        }
        override fun onPlayerError(error: PlaybackException) { player?.let { recover(it, error) } }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) failStreak = 0 }
        override fun onEvents(player: Player, events: Player.Events) {
            updateWidget()
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) saveQueue(player)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30_000).setReadTimeoutMs(180_000).setAllowCrossProtocolRedirects(true)
        sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sourceFactory).setLoadControl(loadControl())
            .setAudioAttributes(musicAttrs, true)
            .setHandleAudioBecomingNoisy(prefs.getBoolean("noisy_pause", true))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = p
        p.addListener(mainListener)
        attachEffects(p.audioSessionId)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        car = CarPlayer(p)
        val carPlayer = car!!
        session = MediaLibrarySession.Builder(this, carPlayer, callback).build()
        refreshCarButtons = { updateCarButtons() }
        updateCarButtons()
        handler.post(tick)
    }

    /* ---------------- what the car (AAIdrive / iDrive), Bluetooth and the lock screen see ---------------- */
    private var car: CarPlayer? = null

    /** "Song (feat. X) [Remastered 2011] - From Spider-Man" → "Song", so it fits on small car screens instead of jumping around. */
    private fun shortTitle(t: String?): String {
        if (t.isNullOrBlank()) return t.orEmpty()
        val s = t.replace(Regex("\\s*[\\(\\[][^)\\]]*[\\)\\]]"), "").replace(Regex("\\s+-\\s+.*(remaster|version|edit|mix|from|live|mono|stereo).*$", RegexOption.IGNORE_CASE), "").trim()
        return s.ifBlank { t }
    }

    private inner class CarPlayer(val p: ExoPlayer) : ForwardingPlayer(p), CarHost {
        // what CarLyrics and Feedback look at
        override fun context(): android.content.Context = this@PlaybackService
        override fun item(): MediaItem? = getCurrentMediaItem()
        override fun position(): Long = getCurrentPosition()
        override fun duration(): Long = getDuration()
        override fun playing(): Boolean = isPlaying()
        override fun refresh() = refreshMetadata()

        private val listeners = java.util.concurrent.CopyOnWriteArrayList<Player.Listener>()
        override fun addListener(listener: Player.Listener) { super.addListener(listener); listeners += listener }
        override fun removeListener(listener: Player.Listener) { super.removeListener(listener); listeners -= listener }
        /** Tell the car / lock screen to read the title and cover again (used when a crossfade starts). */
        fun refreshMetadata() { val m = mediaMetadata; listeners.forEach { try { it.onMediaMetadataChanged(m) } catch (_: Exception) {} } }

        override fun getCurrentMediaItem(): MediaItem? = xfItem ?: super.getCurrentMediaItem()

        override fun getMediaMetadata(): MediaMetadata {
            val x = xfItem
            var md = x?.mediaMetadata ?: super.getMediaMetadata()
            // never an empty title on the lock screen / in the car: fall back to the song's own info
            if (md.title.isNullOrBlank()) (x ?: super.getCurrentMediaItem())?.mediaMetadata?.let { im -> if (!im.title.isNullOrBlank()) md = md.buildUpon().populate(im).build() }
            if (md.title.isNullOrBlank()) return md
            val short = prefs.getBoolean("car_short", true)
            if (short) md = md.buildUpon().setTitle(shortTitle(md.title?.toString())).build()
            // in the car: the line being sung instead of "Next: song" (also what drives the taste feedback)
            CarLyrics.line(this, md.title, md.artist)?.let { return md.buildUpon().setAlbumTitle(it).build() }
            if (!prefs.getBoolean("car_next", true)) return md
            // "Next: Title · Artist" on the album line, so iDrive previews what's coming
            var n = nextMediaItemIndex
            if (x != null && n != C.INDEX_UNSET) n = currentTimeline.getNextWindowIndex(n, repeatMode, shuffleModeEnabled)
            if (n == C.INDEX_UNSET || n >= mediaItemCount) return md
            val m = getMediaItemAt(n).mediaMetadata
            val title = m.title?.toString().orEmpty().let { if (short) shortTitle(it) else it }
            if (title.isBlank()) return md
            val artist = m.artist?.toString().orEmpty()
            return md.buildUpon().setAlbumTitle("Next: $title" + if (artist.isNotBlank()) " · $artist" else "").build()
        }

        // Next always works – at the end of an album it keeps going with similar music
        override fun getAvailableCommands(): Player.Commands {
            val c = super.getAvailableCommands()
            if (mediaItemCount == 0 || jamGuest) return c
            return c.buildUpon().add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
        }
        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
        /** During a crossfade the screen already shows the new song, so skip/seek act on it: swap first. */
        private fun handOver(): CarPlayer? { if (xfPhase != 1 || car !== this) return null; swapPlayers(0); return car.takeIf { it !== this } }
        override fun seekToNext() { handOver()?.let { return it.seekToNext() }
            if (p.hasNextMediaItem() || jamGuest || p.mediaItemCount == 0) super.seekToNext() else continueAtEnd() }
        override fun seekToNextMediaItem() { handOver()?.let { return it.seekToNextMediaItem() }
            if (p.hasNextMediaItem() || jamGuest || p.mediaItemCount == 0) super.seekToNextMediaItem() else continueAtEnd() }
        override fun seekToPrevious() { handOver()?.let { return it.seekToPrevious() }; super.seekToPrevious() }
        override fun seekToPreviousMediaItem() { handOver()?.let { return it.seekToPreviousMediaItem() }; super.seekToPreviousMediaItem() }
        override fun seekTo(positionMs: Long) { handOver()?.let { return it.seekTo(positionMs) }; super.seekTo(positionMs) }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) { if (xfPhase == 1) cancelCrossfade(); super.seekTo(mediaItemIndex, positionMs) }
    }

    private var extending = false
    /** Next was pressed on the last song: find music like it, add it and play on. */
    private fun continueAtEnd() {
        val p = player ?: return
        val a = api() ?: return
        if (extending) return
        val artist = p.currentMediaItem?.mediaMetadata?.artist?.toString()?.split(",", "&", " feat", " ft.", " x ")?.firstOrNull()?.trim()
        if (artist.isNullOrBlank()) return
        extending = true
        val have = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }.toSet()
        val hiddenSongs = prefs.getStringSet("hidden_songs", emptySet()) ?: emptySet()
        val hiddenArtists = prefs.getStringSet("hidden_artists", emptySet()) ?: emptySet()
        scope.launch {
            try {
                val found = a.searchSongs(artist, 40).filter { !SmartSearch.isVariant(it.title) && it.id !in have && it.id !in hiddenSongs && it.artist.split(",", "&").first().trim().lowercase() !in hiddenArtists }
                val pick = (found.filter { it.artist.contains(artist, true) }.shuffled().take(8) + found.filter { !it.artist.contains(artist, true) }.shuffled().take(7)).shuffled()
                if (pick.isNotEmpty()) { p.addMediaItems(pick.map { songItem(it, a) }); p.seekToNextMediaItem(); p.play() }
            } catch (_: Exception) {}
            extending = false
        }
    }

    /** Our own buttons for the car's "Actions" list (and the notification): Like and More like this. */
    private var lastButtonsLiked: Boolean? = null
    private fun updateCarButtons() {
        val sess = session ?: return
        val id = car?.currentMediaItem?.mediaId
        val liked = id != null && id in likedIds
        if (liked == lastButtonsLiked) return // nothing changed – don't make the notification and car redraw
        lastButtonsLiked = liked
        val like = androidx.media3.session.CommandButton.Builder().setDisplayName(if (liked) "Liked" else "Like")
            .setIconResId(if (liked) R.drawable.ic_car_liked else R.drawable.ic_car_like)
            .setSessionCommand(androidx.media3.session.SessionCommand(CMD_LIKE, Bundle.EMPTY)).build()
        val more = androidx.media3.session.CommandButton.Builder().setDisplayName("More like this")
            .setIconResId(R.drawable.ic_car_radio)
            .setSessionCommand(androidx.media3.session.SessionCommand(CMD_MORE, Bundle.EMPTY)).build()
        try { sess.setCustomLayout(ImmutableList.of(like, more)) } catch (_: Exception) {}
    }

    private fun playerCommandsFor(base: Player.Commands): Player.Commands =
        // the car's dashboard only shows Back / the song / Next when it doesn't get the whole queue
        if (prefs.getBoolean("car_queue", false)) base else base.buildUpon().remove(Player.COMMAND_GET_TIMELINE).build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAR_RESUME) { resumeForCar(); return START_NOT_STICKY }
        return super.onStartCommand(intent, flags, startId)
    }

    /** The phone just connected to the car: continue the music where you left off. */
    private fun resumeForCar() {
        val p = player ?: return
        if (p.isPlaying) return
        if (p.mediaItemCount == 0) {
            val a = api() ?: return
            val arr = try { JSONArray(prefs.getString("saved_queue", "[]")) } catch (_: Exception) { JSONArray() }
            val list = (0 until arr.length()).mapNotNull { songFromJson(arr.optJSONObject(it)) }
            if (list.isEmpty()) return
            p.setMediaItems(list.map { songItem(it, a) }, prefs.getInt("saved_index", 0).coerceIn(0, list.size - 1), prefs.getLong("saved_pos", 0))
            p.prepare()
        }
        p.play()
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // the app was swiped away: stop the music too, instead of letting it play on
        session?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        releaseEffects()
        side?.release(); side = null
        scope.cancel()
        session?.run { player.release(); release() }
        session = null; player = null
        PlayerWidget.push(this)
        super.onDestroy()
    }
}
