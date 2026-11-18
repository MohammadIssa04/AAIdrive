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
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(getSharedPreferences("musik", MODE_PRIVATE).getInt("cache_mb", 300).coerceIn(50, 2000) * 1024L * 1024).build() }
        .respectCacheHeaders(false) // the music server says "don't cache" – covers never change, so ignore that
        .okHttpClient { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 }).build() } // 5 covers at a time was the default
        .crossfade(120)
        .build()
}
