package com.via.bgplay

import android.content.Context

/**
 * Tiny "recently visited" list for the home page (max 12 entries, newest first).
 */
object RecentSites {

    private const val FILE = "via_bgplay_recent"
    private const val SEPARATOR = "\u0001"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun list(context: Context): List<Pair<String, String>> {
        val raw = prefs(context).getString("items", "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split(SEPARATOR)
            if (parts.size >= 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
        }
    }

    fun remember(context: Context, url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        val current = list(context).filterNot { it.first == url }
        val updated = (listOf(url to title.trim().ifBlank { url }) + current).take(12)
        val encoded = updated.joinToString("\n") { it.first + SEPARATOR + it.second }
        prefs(context).edit().putString("items", encoded).apply()
    }
}
