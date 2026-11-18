package com.musik.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/* ================= lyrics (lrclib.net, free, no account) ================= */

data class LyricLine(val timeMs: Long, val text: String)
data class Lyrics(val synced: List<LyricLine>, val plain: String?)

object LyricsClient {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val cache = java.util.Collections.synchronizedMap(HashMap<String, Lyrics?>())

    private fun mainArtist(a: String) = a.split(",", "&", " feat.", " feat ", " ft.", " x ").first().trim()

    private fun parse(o: JSONObject): Lyrics? {
        val synced = o.optString("syncedLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        if (synced == null && plain == null) return null
        val lines = synced?.lines()?.mapNotNull { line ->
            val m = Regex("""^\[(\d+):(\d+)(?:[.:](\d+))?]\s?(.*)$""").find(line.trim()) ?: return@mapNotNull null
            val (mm, ss, frac, text) = m.destructured
            val ms = mm.toLong() * 60_000 + ss.toLong() * 1000 + (frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0)
            LyricLine(ms, text)
        }.orEmpty()
        return Lyrics(lines, plain)
    }

    private fun get(url: String): String? = http.newCall(
        Request.Builder().url(url).header("User-Agent", "IssaMusic/1.3 (personal music app)").build()
    ).execute().use { if (it.isSuccessful) it.body?.string() else null }

    suspend fun fetch(s: Song): Lyrics? = withContext(Dispatchers.IO) {
        cache[s.id]?.let { return@withContext it }
        if (cache.containsKey(s.id)) return@withContext null
        val artist = mainArtist(s.artist)
        val res = try {
            val q = "track_name=${Uri.encode(s.title)}&artist_name=${Uri.encode(artist)}" +
                (if (s.album.isNotBlank()) "&album_name=${Uri.encode(s.album)}" else "") + (if (s.duration > 0) "&duration=${s.duration}" else "")
            get("https://lrclib.net/api/get?$q")?.let { parse(JSONObject(it)) }
                ?: get("https://lrclib.net/api/search?track_name=${Uri.encode(s.title)}&artist_name=${Uri.encode(artist)}")?.let { txt ->
                    val a = JSONArray(txt)
                    val objs = (0 until a.length()).map { a.getJSONObject(it) }
                    (objs.firstOrNull { it.optString("syncedLyrics").isNotBlank() && it.optString("syncedLyrics") != "null" } ?: objs.firstOrNull())?.let { parse(it) }
                }
        } catch (_: Exception) { null }
        cache[s.id] = res
        res
    }
}

/* ================= notifications ================= */

object Notify {
    private const val CHANNEL = "friends"
    fun show(ctx: Context, id: Int, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Friends", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Songs, reactions and Jams from friends"
            })
        }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(open).build()
        try { nm.notify(id, n) } catch (_: SecurityException) {}
    }
}

/* ================= share cards (Instagram / Snapchat stories) ================= */

object ShareCard {
    private suspend fun bitmap(ctx: Context, url: String?): Bitmap? {
        if (url == null) return null
        val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(900).allowHardware(false).build())
        return ((r as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
    }

    /** Builds a 1080x1920 story image. [lines] are (text, sizePx, bold) under the cover. */
    suspend fun make(ctx: Context, coverUrl: String?, headline: String, lines: List<Triple<String, Float, Boolean>>, accent: Int): Uri =
        withContext(Dispatchers.Default) {
            val cover = bitmap(ctx, coverUrl)
            val w = 1080; val h = 1920
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val top = cover?.let { Palette.from(it).generate().let { p -> p.vibrantSwatch ?: p.dominantSwatch }?.rgb } ?: accent
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, 0xFF121212.toInt(), Shader.TileMode.CLAMP) })
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
            text.textSize = 44f; text.typeface = Typeface.DEFAULT_BOLD
            c.drawText(headline.uppercase(), w / 2f, 260f, text)
            val size = 760f; val left = (w - size) / 2; val topY = 360f
            if (cover != null) {
                val src = android.graphics.Rect(0, 0, cover.width, cover.height)
                c.save(); val path = android.graphics.Path().apply { addRoundRect(RectF(left, topY, left + size, topY + size), 36f, 36f, android.graphics.Path.Direction.CW) }
                c.clipPath(path); c.drawBitmap(cover, src, RectF(left, topY, left + size, topY + size), Paint(Paint.FILTER_BITMAP_FLAG)); c.restore()
            } else {
                c.drawRoundRect(RectF(left, topY, left + size, topY + size), 36f, 36f, Paint().apply { color = accent })
            }
            var y = topY + size + 130f
            lines.forEach { (t, sz, bold) ->
                text.textSize = sz; text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                text.color = if (bold) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt()
                val clipped = if (text.measureText(t) > w - 120) { var s = t; while (s.length > 3 && text.measureText("$s…") > w - 120) s = s.dropLast(1); "$s…" } else t
                c.drawText(clipped, w / 2f, y, text); y += sz * 1.45f
            }
            text.textSize = 40f; text.typeface = Typeface.DEFAULT_BOLD; text.color = 0xFFFFFFFF.toInt()
            c.drawCircle(w / 2f - 150f, h - 150f, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
            c.drawText("Issa Music", w / 2f + 20f, h - 136f, text)
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            val f = File(dir, "issa-${System.currentTimeMillis()}.png")
            f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        }

    fun share(ctx: Context, uri: Uri, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share to…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
