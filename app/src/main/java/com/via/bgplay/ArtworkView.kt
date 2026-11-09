package com.via.bgplay

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.via.bgplay.playback.ArtworkLoader

/**
 * Small helper that binds a (possibly remote) artwork URL to an ImageView and hides the
 * fallback icon as soon as a bitmap is available.
 */
object ArtworkView {

    private val main = Handler(Looper.getMainLooper())

    fun set(url: String?, primary: ImageView?, fallback: android.view.View?) {
        val imageView = primary ?: return
        if (url.isNullOrBlank()) {
            imageView.setImageDrawable(null)
            imageView.tag = null
            fallback?.visibility = android.view.View.VISIBLE
            return
        }
        val cached = ArtworkLoader.cached(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            imageView.tag = url
            fallback?.visibility = android.view.View.GONE
            return
        }
        imageView.setImageDrawable(null)
        fallback?.visibility = android.view.View.VISIBLE
        imageView.tag = url
        ArtworkLoader.load(url) { bitmap: Bitmap? ->
            main.post {
                if (imageView.tag == url && bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    fallback?.visibility = android.view.View.GONE
                }
            }
        }
    }
}
