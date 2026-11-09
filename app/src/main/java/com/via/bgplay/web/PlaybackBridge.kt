package com.via.bgplay.web

import android.webkit.JavascriptInterface
import com.via.bgplay.playback.PlaybackCenter

/**
 * JavaScript interface exposed to every page as `window.BgpBridge`.
 * Only a single, tiny and side-effect free method is exposed so that a hostile page
 * cannot reach anything else.
 */
class PlaybackBridge(private val tabId: String) {

    @JavascriptInterface
    fun postMessage(payload: String?) {
        if (payload.isNullOrBlank()) return
        PlaybackCenter.onPageMessage(tabId, payload)
    }

    @JavascriptInterface
    fun ping(): String = "pong"
}
