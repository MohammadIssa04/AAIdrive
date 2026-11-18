package com.musik.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/** Songs saved on the phone for offline listening. Files live in the app's private storage. */
object Downloads {
    private val lock = Any()
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build()

    private fun dir(ctx: Context) = File(ctx.filesDir, "offline").apply { mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun file(ctx: Context, id: String): File? = File(dir(ctx), safe(id) + ".audio").takeIf { it.exists() && it.length() > 0 }
    fun isDownloaded(ctx: Context, id: String) = file(ctx, id) != null

    fun list(ctx: Context): List<Song> = synchronized(lock) {
        try {
            val a = JSONArray(indexFile(ctx).takeIf { it.exists() }?.readText() ?: "[]")
            (0 until a.length()).mapNotNull { songFromJson(a.optJSONObject(it)) }.filter { isDownloaded(ctx, it.id) }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(ctx: Context, songs: List<Song>) = synchronized(lock) {
        val a = JSONArray(); songs.forEach { a.put(it.toJson()) }
        indexFile(ctx).writeText(a.toString())
    }

    fun totalBytes(ctx: Context): Long = dir(ctx).listFiles()?.filter { it.name.endsWith(".audio") }?.sumOf { it.length() } ?: 0L

    suspend fun download(ctx: Context, url: String, song: Song, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (isDownloaded(ctx, song.id)) return@withContext
        val dest = File(dir(ctx), safe(song.id) + ".audio")
        val tmp = File(dir(ctx), safe(song.id) + ".part")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Download failed (${r.code})")
            val body = r.body ?: throw Exception("Empty download")
            val type = body.contentType()?.toString().orEmpty()
            if (type.contains("json") || type.contains("xml")) throw Exception("The server couldn't provide this song")
            val total = body.contentLength()
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
        }
        if (tmp.length() < 10_000) { tmp.delete(); throw Exception("Download was too small") }
        tmp.renameTo(dest)
        save(ctx, (list(ctx).filter { it.id != song.id } + song))
    }

    fun remove(ctx: Context, id: String) {
        file(ctx, id)?.delete()
        save(ctx, list(ctx).filter { it.id != id })
    }

    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }
}
