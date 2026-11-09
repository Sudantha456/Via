package com.via.bgplay.playback

import org.json.JSONObject

/**
 * Snapshot of whatever the page is currently playing. Built from the JSON the injected
 * JavaScript sends through the bridge.
 */
data class MediaInfo(
    val title: String,
    val artist: String,
    val album: String,
    val siteName: String,
    val host: String,
    val artworkUrl: String?,
    val pageUrl: String,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val canPrev: Boolean,
    val canNext: Boolean
) {

    val notifTitle: String get() = title.ifBlank { siteName }

    val notifSubtitle: String
        get() {
            val parts = mutableListOf<String>()
            if (artist.isNotBlank()) parts.add(artist)
            if (album.isNotBlank() && !album.equals(artist, ignoreCase = true)) parts.add(album)
            if (siteName.isNotBlank() && !siteName.equals(artist, ignoreCase = true) && !siteName.equals(title, ignoreCase = true)) {
                parts.add(siteName)
            }
            return parts.joinToString(" · ")
        }

    companion object {
        const val EMPTY_TITLE = ""

        fun fromJson(json: JSONObject): MediaInfo? {
            val title = json.optString("title").trim()
            val host = json.optString("host").trim()
            if (title.isEmpty() && json.optString("artist").isEmpty() && host.isEmpty()) return null
            return MediaInfo(
                title = title,
                artist = json.optString("artist").trim(),
                album = json.optString("album").trim(),
                siteName = json.optString("siteName").trim().ifEmpty { host },
                host = host,
                artworkUrl = json.optString("artwork").takeIf { it.isNotBlank() },
                pageUrl = json.optString("pageUrl"),
                durationMs = (json.optDouble("duration", 0.0) * 1000.0).toLong().coerceAtLeast(0L),
                positionMs = (json.optDouble("position", 0.0) * 1000.0).toLong().coerceAtLeast(0L),
                playing = json.optBoolean("playing", true),
                canPrev = json.optBoolean("canPrev", true),
                canNext = json.optBoolean("canNext", true)
            )
        }
    }
}
