package com.via.bgplay.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Downloads (and caches) notification artwork.
 *
 * Artwork arrives as an http(s) URL, a data: URI or a protocol relative URL. Failures
 * are remembered so that a broken image is never downloaded twice per session.
 */
object ArtworkLoader {

    private val cache = object : LruCache<String, Bitmap>(24) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val failures = HashSet<String>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bgp-artwork").apply { isDaemon = true }
    }

    fun cached(url: String?): Bitmap? = if (url == null) null else cache.get(url)

    fun load(url: String?, callback: (Bitmap?) -> Unit) {
        if (url.isNullOrBlank()) {
            callback(null)
            return
        }
        cache.get(url)?.let {
            callback(it)
            return
        }
        if (failures.contains(url)) {
            callback(null)
            return
        }
        executor.execute {
            val bitmap = try {
                decode(url)
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) {
                cache.put(url, bitmap)
            } else {
                synchronized(failures) { failures.add(url) }
            }
            callback(bitmap)
        }
    }

    private fun decode(url: String): Bitmap? {
        if (url.startsWith("data:")) return decodeDataUri(url)

        val normalized = url.trim()
            .replace(" ", "%20")
            .let { if (it.startsWith("//")) "https:$it" else it }

        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return null

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(normalized).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) ViaBackgroundPlay")
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
            }
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { stream: InputStream -> stream.readBytes() }
            if (bytes.isEmpty()) return null
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun decodeDataUri(uri: String): Bitmap? {
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val header = uri.substring(0, comma)
        val payload = uri.substring(comma + 1)
        return try {
            val bytes = if (header.contains(";base64")) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        cache.evictAll()
        synchronized(failures) { failures.clear() }
    }
}
