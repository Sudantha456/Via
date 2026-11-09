package com.via.bgplay.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Central place for every user setting. Everything is read live (no caching) so the
 * service and the UI can never disagree about the current configuration.
 */
object Prefs {

    private const val FILE = "via_bgplay_prefs"

    const val KEY_BACKGROUND_PLAY = "background_play"
    const val KEY_MEDIA_NOTIFICATION = "media_notification"
    const val KEY_MEDIA_SESSION = "media_session"
    const val KEY_MUTE_HIDDEN = "mute_hidden"
    const val KEY_WAKE_LOCK = "wake_lock"
    const val KEY_AUTOPLAY_NEXT = "autoplay_next"
    const val KEY_AUDIO_SITES = "audio_sites"
    const val KEY_DESKTOP_MODE = "desktop_mode"
    const val KEY_LAST_URL = "last_url"
    const val KEY_ONBOARDED = "onboarded"

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun backgroundPlay(context: Context): Boolean =
        get(context).getBoolean(KEY_BACKGROUND_PLAY, true)

    fun setBackgroundPlay(context: Context, value: Boolean) =
        get(context).edit().putBoolean(KEY_BACKGROUND_PLAY, value).apply()

    fun mediaNotification(context: Context): Boolean =
        get(context).getBoolean(KEY_MEDIA_NOTIFICATION, true)

    fun setMediaNotification(context: Context, value: Boolean) =
        get(context).edit().putBoolean(KEY_MEDIA_NOTIFICATION, value).apply()

    fun mediaSession(context: Context): Boolean =
        get(context).getBoolean(KEY_MEDIA_SESSION, true)

    fun setMediaSession(context: Context, value: Boolean) =
        get(context).edit().putBoolean(KEY_MEDIA_SESSION, value).apply()

    fun muteHidden(context: Context): Boolean =
        get(context).getBoolean(KEY_MUTE_HIDDEN, false)

    fun setMuteHidden(context: Context, value: Boolean) =
        get(context).edit().putBoolean(KEY_MUTE_HIDDEN, value).apply()

    fun wakeLock(context: Context): Boolean =
        get(context).getBoolean(KEY_WAKE_LOCK, true)

    fun setWakeLock(context: Context, value: Boolean) =
        get(context).edit().putBoolean(KEY_WAKE_LOCK, value).apply()

    fun autoplayNext(context: Context): Boolean =
        get(context).getBoolean(KEY_AUTOPLAY_NEXT, true)

    fun setAutoplayNext(context: Context, value: Boolean) =
        get(context).edit().putBoolean(KEY_AUTOPLAY_NEXT, value).apply()

    fun desktopMode(context: Context): Boolean =
        get(context).getBoolean(KEY_DESKTOP_MODE, false)

    fun setDesktopMode(context: Context, value: Boolean) =
        get(context).edit().putBoolean(KEY_DESKTOP_MODE, value).apply()

    fun lastUrl(context: Context): String? = get(context).getString(KEY_LAST_URL, null)

    fun setLastUrl(context: Context, url: String) =
        get(context).edit().putString(KEY_LAST_URL, url).apply()

    /** Sites the user explicitly forced into audio-only mode (host names). */
    fun audioSites(context: Context): Set<String> =
        get(context).getStringSet(KEY_AUDIO_SITES, emptySet()) ?: emptySet()

    fun setAudioSite(context: Context, host: String, enabled: Boolean) {
        val current = audioSites(context).toMutableSet()
        if (enabled) current.add(host) else current.remove(host)
        get(context).edit().putStringSet(KEY_AUDIO_SITES, current).apply()
    }
}
