package com.via.bgplay.web

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Keeps the browser chrome and the playback engine in sync with page navigations.
 */
class ViaWebViewClient(
    private val callbacks: Callbacks
) : WebViewClient() {

    interface Callbacks {
        fun onPageStarted(url: String?)
        fun onPageFinished(url: String?, title: String?)
        fun onUrlChanged(url: String?)
        fun shouldOverride(url: String, isMainFrame: Boolean): Boolean
        /** Called after every finished load so the engine can be (re)installed. */
        fun injectEngine()
        /** Called as soon as a navigation starts so stale media info is dropped. */
        fun onNavigating()
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        callbacks.onNavigating()
        callbacks.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        callbacks.injectEngine()
        callbacks.onPageFinished(url, view?.title)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        callbacks.onUrlChanged(url)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        return callbacks.shouldOverride(url, request.isForMainFrame)
    }

    @Suppress("OverridingDeprecatedMember", "DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        val target = url ?: return false
        return callbacks.shouldOverride(target, true)
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
    }
}
