package com.via.bgplay.playback

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.via.bgplay.prefs.Prefs
import org.json.JSONObject

/**
 * Single source of truth for "what is playing right now".
 *
 * Every tab reports its own snapshot; the centre picks the playing tab (or, when
 * several tabs play at once, the most recently started one), keeps a small history so
 * that previous/next can hop between tabs, and fans out a single MediaState to the
 * service, the notification and the UI.
 */
object PlaybackCenter {

    private const val TAG = "BgpCenter"
    private const val STALE_AFTER_MS = 12_000L

    private val main = Handler(Looper.getMainLooper())

    private val tabs = HashMap<String, TabSnapshot>()
    private val history = ArrayList<String>()

    private var activeTabId: String? = null
    private var lastState: MediaState? = null

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(MediaState) -> Unit>()

    /** Application context, installed by [com.via.bgplay.App] so the service can be started. */
    @Volatile
    var appContext: android.content.Context? = null

    /** Called with the newest state, immediately and then on every change. */
    fun addListener(listener: (MediaState) -> Unit) {
        listeners.add(listener)
        lastState?.let { listener(it) }
    }

    fun removeListener(listener: (MediaState) -> Unit) {
        listeners.remove(listener)
    }

    private data class TabSnapshot(
        val info: MediaInfo,
        var receivedAt: Long,
        var lastPlayingAt: Long
    )

    val current: MediaState?
        get() = lastState

    val activeTab: String?
        get() = activeTabId

    // ------------------------------------------------------------------ input

    fun onPageMessage(tabId: String, payload: String) {
        val json = try {
            JSONObject(payload)
        } catch (e: Exception) {
            Log.w(TAG, "bad payload from $tabId", e)
            return
        }
        if (json.optInt("v", 0) != 1) return
        val info = MediaInfo.fromJson(json)
        main.post { handle(tabId, info) }
    }

    private fun handle(tabId: String, info: MediaInfo?) {
        val now = System.currentTimeMillis()
        if (info == null) {
            tabs.remove(tabId)
        } else {
            val previous = tabs[tabId]
            val stateChanged = previous == null ||
                previous.info.title != info.title ||
                previous.info.artist != info.artist ||
                previous.info.playing != info.playing ||
                previous.info.artworkUrl != info.artworkUrl
            val lastPlayingAt = if (info.playing && (previous == null || !previous.info.playing || stateChanged)) {
                now
            } else {
                previous?.lastPlayingAt ?: 0L
            }
            tabs[tabId] = TabSnapshot(info, now, lastPlayingAt)
            if (stateChanged) pushHistory(tabId)
        }
        electActive(now)
        publish()
    }

    /** The tab was closed or navigated away. */
    fun onTabClosed(tabId: String) {
        main.post {
            tabs.remove(tabId)
            history.remove(tabId)
            if (activeTabId == tabId) activeTabId = null
            electActive(System.currentTimeMillis())
            publish()
        }
    }

    fun onTabNavigating(tabId: String) {
        main.post {
            tabs.remove(tabId)
            if (activeTabId == tabId) electActive(System.currentTimeMillis())
            publish()
        }
    }

    private fun pushHistory(tabId: String) {
        history.remove(tabId)
        history.add(0, tabId)
        while (history.size > 20) history.removeAt(history.size - 1)
    }

    private fun electActive(now: Long) {
        // Keep the current tab while it still has fresh media — otherwise every tab
        // that merely reports "paused" would steal the notification.
        val active = activeTabId
        if (active != null) {
            val snap = tabs[active]
            if (snap != null && now - snap.receivedAt < STALE_AFTER_MS) return
        }
        var best: String? = null
        var bestPlayingAt = -1L
        var bestPlaying = false
        for ((id, snap) in tabs) {
            val fresh = now - snap.receivedAt < STALE_AFTER_MS
            val playing = snap.info.playing && fresh
            if (playing) {
                if (!bestPlaying || snap.lastPlayingAt > bestPlayingAt) {
                    best = id
                    bestPlayingAt = snap.lastPlayingAt
                    bestPlaying = true
                }
            } else if (!bestPlaying && fresh) {
                if (id == activeTabId) {
                    best = id
                } else if (best == null) {
                    best = id
                }
            }
        }
        activeTabId = best
    }

    // ----------------------------------------------------------------- output

    private fun publish() {
        val now = System.currentTimeMillis()
        val tabId = activeTabId
        val snap = tabId?.let { tabs[it] }
        val state = if (tabId == null || snap == null) {
            MediaState.idle()
        } else {
            MediaState(
                tabId = tabId,
                info = snap.info,
                playing = snap.info.playing,
                hasPrevTab = hasAdjacent(tabId, -1),
                hasNextTab = hasAdjacent(tabId, 1),
                stale = now - snap.receivedAt > STALE_AFTER_MS
            )
        }
        if (state != lastState) {
            lastState = state
            for (listener in listeners) {
                try {
                    listener(state)
                } catch (e: Exception) {
                    Log.w(TAG, "listener failed", e)
                }
            }
            maybeStartService(state)
        }
    }

    /**
     * Keep the foreground service (and therefore the notification) alive whenever media
     * is playing, even if the activity has already gone to the background.
     */
    private fun maybeStartService(state: MediaState) {
        val context = appContext ?: return
        if (!state.isActive) return
        if (!Prefs.backgroundPlay(context)) return
        if (BackgroundPlaybackService.isRunning) return
        try {
            BackgroundPlaybackService.start(context)
        } catch (e: Exception) {
            Log.w(TAG, "cannot start playback service", e)
        }
    }

    private fun hasAdjacent(tabId: String, direction: Int): Boolean {
        val index = history.indexOf(tabId)
        if (index < 0) return history.isNotEmpty()
        val next = index + direction * -1
        return next in history.indices
    }

    /**
     * Cycles the active tab inside the playback history. Returns the id of the tab that
     * was picked, or null when there is nothing else to switch to.
     */
    fun switchTab(direction: Int): String? {
        val current = activeTabId ?: return null
        val index = history.indexOf(current)
        if (index < 0) return null
        val target = index - direction
        if (target !in history.indices) return null
        val chosen = history[target]
        if (chosen == current) return null
        activeTabId = chosen
        publish()
        return chosen
    }

    fun markPlaying(tabId: String) {
        main.post {
            val snap = tabs[tabId] ?: return@post
            snap.lastPlayingAt = System.currentTimeMillis()
            electActive(System.currentTimeMillis())
            publish()
        }
    }

    fun forceRefresh() {
        main.post { publish() }
    }

    fun clear() {
        tabs.clear()
        history.clear()
        activeTabId = null
        lastState = null
    }

    // ------------------------------------------------------------- preferences

    fun isEnabled(context: android.content.Context): Boolean = Prefs.backgroundPlay(context)
}

/**
 * Immutable state handed to the notification and to the UI.
 */
data class MediaState(
    val tabId: String?,
    val info: MediaInfo?,
    val playing: Boolean,
    val hasPrevTab: Boolean,
    val hasNextTab: Boolean,
    val stale: Boolean
) {
    val isActive: Boolean get() = info != null

    val canPrev: Boolean get() = (info?.canPrev == true) || hasPrevTab
    val canNext: Boolean get() = (info?.canNext == true) || hasNextTab

    companion object {
        fun idle() = MediaState(null, null, false, false, false, false)
    }
}
