package com.musik.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Covers are kept on the phone (memory + disk, size chosen in Settings; oldest are removed first) so they show instantly. */
class IssaApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = build(this)

    companion object {
        @Volatile private var installed = false
        /** Inside the AAIdrive build there is another Application class, so the cover loader is set up here instead. */
        fun install(ctx: android.content.Context) {
            if (installed) return
            installed = true
            coil.Coil.setImageLoader(build(ctx.applicationContext))
        }

        fun build(ctx: android.content.Context): ImageLoader = ImageLoader.Builder(ctx)
        .memoryCache { MemoryCache.Builder(ctx).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(ctx.cacheDir.resolve("covers")).maxSizeBytes(ctx.getSharedPreferences("musik", android.content.Context.MODE_PRIVATE).getInt("cache_mb", 300).coerceIn(50, 2000) * 1024L * 1024).build() }
        .respectCacheHeaders(false) // the music server says "don't cache" – covers never change, so ignore that
        .okHttpClient { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 }).build() } // 5 covers at a time was the default
        .crossfade(120)
        .build()
    }
}
