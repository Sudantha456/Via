package com.via.bgplay.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import com.via.bgplay.prefs.Prefs
import java.util.UUID

/**
 * One browser tab: an isolated WebView plus its metadata.
 *
 * Background playback is mostly a matter of *not* destroying anything: a WebView that
 * stays in the view hierarchy keeps its media pipeline alive when the activity is
 * stopped, so each tab keeps its own WebView for its entire life.
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserTab(
    private val context: Context,
    val parent: ViewGroup,
    initialUrl: String
) : TabRegistry.TabHandle {

    val id: String = UUID.randomUUID().toString()

    val webView: WebView = WebView(context)

    @Volatile
    private var lastUrl: String = initialUrl

    @Volatile
    private var lastTitle: String = ""

    var isSelected: Boolean = false
        private set

    var isAudioOnly: Boolean = false
        private set

    /** Progress of the current load, 0..100; used to reload after the engine boots. */
    var progress: Int = 0

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString
                .replace("; wv)", ")")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.settings.safeBrowsingEnabled = true
        }

        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.setBackgroundColor(0xFF12141A.toInt())
        webView.addJavascriptInterface(PlaybackBridge(id), "BgpBridge")

        // Foreground media must be allowed to autoplay when it comes back from the
        // background, which Android otherwise blocks without a gesture.
        webView.settings.mediaPlaybackRequiresUserGesture = false

        TabRegistry.register(id, this)
    }

    fun attach() {
        if (webView.parent == null) {
            parent.addView(webView)
        }
        if (webView.visibility != android.view.View.VISIBLE) {
            webView.visibility = android.view.View.VISIBLE
        }
    }

    fun hide() {
        webView.visibility = android.view.View.INVISIBLE
    }

    fun detachFromView() {
        webView.visibility = android.view.View.INVISIBLE
        if (!Prefs.backgroundPlay(context) && (webView.parent != null)) {
            // Background play off: unload the page like a normal browser would.
            webView.onPause()
        }
    }

    fun destroy() {
        TabRegistry.unregister(id)
        try {
            webView.onPause()
            parent.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeJavascriptInterface("BgpBridge")
            webView.destroy()
        } catch (e: Exception) {
        }
    }

    fun load(url: String) {
        lastUrl = url
        // "about:home" is the in-app start page; WebView itself cannot render it.
        webView.loadUrl(if (url == HOME_URL) "about:blank" else url)
    }

    fun setSelected(selected: Boolean) {
        isSelected = selected
    }

    fun setAudioOnly(audioOnly: Boolean) {
        isAudioOnly = audioOnly
        applyAudioOnly()
    }

    /** Mutes the picture but keeps the audio, or restores normal playback. */
    private fun applyAudioOnly() {
        val script = if (isAudioOnly) {
            "(function(){try{var v=document.querySelectorAll('video');" +
                "for(var i=0;i<v.length;i++){v[i].style.opacity='0';}}" +
                "catch(e){}})();"
        } else {
            "(function(){try{var v=document.querySelectorAll('video');" +
                "for(var i=0;i<v.length;i++){v[i].style.opacity='';}}" +
                "catch(e){}})();"
        }
        webView.evaluateJavascript(script, null)
    }

    fun onResume() {
        webView.onResume()
        webView.resumeTimers()
    }

    fun onPause() {
        if (!Prefs.backgroundPlay(context)) {
            webView.onPause()
            webView.pauseTimers()
        }
    }

    fun setDesktopMode(desktop: Boolean) {
        val ua = webView.settings.userAgentString
        webView.settings.userAgentString = if (desktop) {
            DESKTOP_UA
        } else {
            if (ua.contains("Mobile")) ua.replace(Regex("Mobile.*?(Safari/[\\d.]+)"), "$1") else ua
        }
        webView.reload()
    }

    fun goBack(): Boolean {
        if (webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    fun goForward(): Boolean {
        if (webView.canGoForward()) {
            webView.goForward()
            return true
        }
        return false
    }

    override fun reload() = webView.reload()

    // ------------------------------------------------------------ TabHandle

    override val pageUrl: String
        get() = webView.url ?: lastUrl

    override val pageTitle: String
        get() = lastTitle.ifBlank { webView.title ?: "" }

    override val isForeground: Boolean
        get() = isSelected

    override fun runJs(script: String) {
        try {
            webView.evaluateJavascript(script, null)
        } catch (e: Exception) { }
    }

    override fun bringToFront() {
        // Handled by MainActivity; this only marks the intent.
        isSelected = true
    }

    fun updateTitle(title: String?) {
        if (title != null) lastTitle = title
    }

    fun updateUrl(url: String?) {
        if (!url.isNullOrBlank()) lastUrl = url
    }

    fun setContentHeight(height: Int) {
        webView.layoutParams?.height = height
        webView.requestLayout()
    }

    companion object {
        const val HOME_URL = "about:home"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }
}
