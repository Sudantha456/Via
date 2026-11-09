package com.via.bgplay.web

import android.graphics.Bitmap
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Chrome client that forwards page title/progress changes and grants the media
 * permissions a web player needs (e.g. encrypted media for Spotify or Audible).
 */
class ViaWebChromeClient(
    private val callbacks: Callbacks
) : WebChromeClient() {

    interface Callbacks {
        fun onProgress(progress: Int)
        fun onTitle(title: String?)
        fun onIcon(icon: Bitmap?)
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        callbacks.onProgress(newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        callbacks.onTitle(title)
    }

    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        callbacks.onIcon(icon)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        val r = request ?: return
        try {
            // Web players legitimately need protected media playback.
            val granted = r.resources.filter {
                it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
            }
            if (granted.isNotEmpty()) r.grant(granted.toTypedArray()) else r.deny()
        } catch (e: Exception) {
            try { r.deny() } catch (ignored: Exception) { }
        }
    }

    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean = true
}
