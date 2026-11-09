package com.via.bgplay.playback

import com.via.bgplay.web.TabRegistry

/**
 * Translates a transport command (from the notification, lock screen, headset, the
 * mini player or the JS side) into something a WebView can execute.
 */
object PlaybackController {

    const val ACTION_TOGGLE = "toggle"
    const val ACTION_PLAY = "play"
    const val ACTION_PAUSE = "pause"
    const val ACTION_NEXT = "next"
    const val ACTION_PREV = "prev"
    const val ACTION_STOP = "stop"
    const val ACTION_SEEK = "seek"

    private fun js(action: String, value: Any? = null): String {
        val encoded = when (value) {
            null -> "null"
            is Number -> value.toString()
            else -> "\"" + value.toString().replace("\"", "\\\"") + "\""
        }
        return "(function(){try{if(window.__bgp&&window.__bgp.act){window.__bgp.act(\"$action\",$encoded);}}catch(e){}})();"
    }

    fun execute(action: String, value: Any? = null): Boolean {
        val state = PlaybackCenter.current ?: MediaState.idle()
        val activeId = PlaybackCenter.activeTab
        return when (action) {
            ACTION_PLAY, ACTION_PAUSE, ACTION_TOGGLE, ACTION_SEEK -> {
                send(activeId, action, value)
            }
            ACTION_NEXT -> {
                // Prefer the site's own "next" (playlist), fall back to the next tab
                // that is playing something so a whole queue can be walked through.
                if (state.info?.canNext == true) {
                    send(activeId, ACTION_NEXT, null)
                } else {
                    val next = PlaybackCenter.switchTab(1)
                    if (next != null) send(next, ACTION_PLAY, null) else send(activeId, ACTION_NEXT, null)
                }
                true
            }
            ACTION_PREV -> {
                if (state.info?.canPrev == true) {
                    send(activeId, ACTION_PREV, null)
                } else {
                    val prev = PlaybackCenter.switchTab(-1)
                    if (prev != null) send(prev, ACTION_PLAY, null) else send(activeId, ACTION_PREV, null)
                }
                true
            }
            ACTION_STOP -> {
                send(activeId, ACTION_STOP, null)
                true
            }
            else -> false
        }
    }

    private fun send(tabId: String?, action: String, value: Any?): Boolean {
        val handle = TabRegistry.get(tabId) ?: return false
        handle.runJs(js(action, value))
        if (action == ACTION_PLAY || action == ACTION_TOGGLE) {
            tabId?.let { PlaybackCenter.markPlaying(it) }
        }
        return true
    }

    /** Refreshes the snapshot of a single tab (used on resume). */
    fun refreshTab(tabId: String?) {
        TabRegistry.get(tabId)?.runJs(js("report"))
    }
}
