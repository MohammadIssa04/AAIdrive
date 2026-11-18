package com.musik.app

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/* Settings that are saved on the phone and update the screen when they change. */
private class BoolPref(val p: SharedPreferences, val key: String, d: Boolean) : ReadWriteProperty<Any?, Boolean> {
    private val s = mutableStateOf(p.getBoolean(key, d))
    override fun getValue(thisRef: Any?, property: KProperty<*>) = s.value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { s.value = value; p.edit().putBoolean(key, value).apply() }
}
private class IntPref(val p: SharedPreferences, val key: String, d: Int) : ReadWriteProperty<Any?, Int> {
    private val s = mutableIntStateOf(p.getInt(key, d))
    override fun getValue(thisRef: Any?, property: KProperty<*>) = s.intValue
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { s.intValue = value; p.edit().putInt(key, value).apply() }
}
private class StrPref(val p: SharedPreferences, val key: String, d: String) : ReadWriteProperty<Any?, String> {
    private val s = mutableStateOf(p.getString(key, d) ?: d)
    override fun getValue(thisRef: Any?, property: KProperty<*>) = s.value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { s.value = value; p.edit().putString(key, value).apply() }
}

class Opts(p: SharedPreferences) {
    var fineVolumeKeys by BoolPref(p, "fine_volume_keys", true)  // phone volume buttons go below the lowest step
    // Home
    var homeTime by BoolPref(p, "home_time", true)          // Morning / afternoon / evening mix
    var homeMixes by BoolPref(p, "home_mixes", true)
    var homeDiscover by BoolPref(p, "home_discover", true)
    var homeRecent by BoolPref(p, "home_recent", true)
    var homeFavs by BoolPref(p, "home_favs", true)
    var homeFriends by BoolPref(p, "home_friends", true)
    var homeMoment by BoolPref(p, "home_moment", true)        // "Right now" mix from what you play at this time
    var offerResume by BoolPref(p, "offer_resume", true)      // "Continue from your PC?"
    // Queue
    var autoplaySimilar by BoolPref(p, "autoplay_similar", true)
    var restoreQueue by BoolPref(p, "restore_queue", true)
    var resumeOnOpen by BoolPref(p, "resume_on_open", false)
    // Volume & sound
    var volApp by IntPref(p, "vol_app", 100)
    var volMax by IntPref(p, "vol_max", 100)
    var volStart by IntPref(p, "vol_start", -1)              // -1 = keep the last volume
    var leveling by BoolPref(p, "leveling", false)
    var noisyPause by BoolPref(p, "noisy_pause", true)
    var treble by IntPref(p, "treble", 0)                    // -1000..1000 (millibel added to the top bands)
    // Quality & data
    var qualityMobile by IntPref(p, "quality_mobile", -1)    // -1 = same as Wi-Fi
    var dataSaver by BoolPref(p, "data_saver", false)
    var hiresCovers by BoolPref(p, "hires_covers", true)
    var cacheMb by IntPref(p, "cache_mb", 300)
    var bgRefresh by StrPref(p, "bg_refresh", "normal")      // normal | saver | off
    // Notifications
    var notifRecs by BoolPref(p, "notif_recs", true)
    var notifReactions by BoolPref(p, "notif_reactions", true)
    var notifJam by BoolPref(p, "notif_jam", true)
    var notifUpdates by BoolPref(p, "notif_updates", true)
    // Player look
    var playerLayout by StrPref(p, "player_layout", "classic") // classic | big | minimal
    var coverAnim by BoolPref(p, "cover_anim", true)
    var miniStyle by StrPref(p, "mini_style", "color")        // color | dark
    var playerBg by StrPref(p, "player_bg", "color")          // color | blur | black
    var transitions by BoolPref(p, "transitions", true)
    var playerVolume by BoolPref(p, "player_volume", false)
    var smartShuffle by BoolPref(p, "smart_shuffle", false)   // mix songs that fit into a shuffled list
    // Search
    var searchPersonal by BoolPref(p, "search_personal", true) // rank artists and songs you know first
    var searchLyrics by BoolPref(p, "search_lyrics", true)     // also look for songs by a line of their lyrics
    var homeWeekly by BoolPref(p, "home_weekly", true)         // "Your week in music" card on Home
    var homeStats by BoolPref(p, "home_stats", true)           // "Your stats" card on Home
    // Theme
    var themeStyle by StrPref(p, "theme_style", "default")    // default | minimal | neon | amoled | glass
    var dynamicAccent by BoolPref(p, "dynamic_accent", false)  // accent colour follows the album cover
}

/** Linear 0..1 gain for the player from the app volume and the volume limit (quiet levels get finer steps). */
fun appGain(p: SharedPreferences): Float {
    val v = minOf(p.getInt("vol_app", 100), p.getInt("vol_max", 100)).coerceIn(0, 100) / 100f
    return v * v * v // cubic: follows how loud it sounds, with very fine steps at the quiet end
}

fun onMobileData(ctx: Context): Boolean = try {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val c = cm.getNetworkCapabilities(cm.activeNetwork)
    c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
} catch (_: Exception) { false }

fun networkName(ctx: Context): String = try {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val c = cm.getNetworkCapabilities(cm.activeNetwork)
    when {
        c == null -> "No connection"
        c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi + VPN (Tailscale)"
        c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "Mobile data + VPN (Tailscale)"
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Connected"
    }
} catch (_: Exception) { "Unknown" }

/** Which bitrate to stream with right now (0 = original). */
fun currentBitrate(ctx: Context, p: SharedPreferences): Int {
    val wifi = p.getInt("quality", 0)
    if (p.getBoolean("data_saver", false)) return 96
    if (!onMobileData(ctx)) return wifi
    val m = p.getInt("quality_mobile", -1)
    return if (m < 0) wifi else m
}

fun dirSize(f: java.io.File?): Long = if (f == null || !f.exists()) 0 else if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { dirSize(it) }
fun mb(bytes: Long) = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${"%.1f".format(bytes / 1048576.0)} MB"
