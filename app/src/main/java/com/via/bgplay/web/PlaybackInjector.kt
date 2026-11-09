package com.via.bgplay.web

import android.content.Context
import com.via.bgplay.prefs.Prefs

/**
 * Loads the injected JavaScript engine from assets and renders the per-page config.
 */
object PlaybackInjector {

    private const val ASSET = "bgp_engine.js"

    @Volatile
    private var cachedScript: String? = null

    private fun script(context: Context): String {
        cachedScript?.let { return it }
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        cachedScript = text
        return text
    }

    /**
     * Builds the script that is evaluated in the page. [appHidden] tells the page that
     * Via itself is in the background, which lets the engine take a more aggressive
     * route (clicking the site's own play button is allowed then).
     */
    fun build(context: Context, appHidden: Boolean): String {
        val config = """
            {
              "bg": ${Prefs.backgroundPlay(context)},
              "autoplay": ${Prefs.autoplayNext(context)},
              "appHidden": $appHidden,
              "muteHidden": ${Prefs.muteHidden(context)}
            }
        """.trimIndent()
        return script(context).replace("__BGP_CONFIG__", config)
    }

    /** Small script used to push a config change without re-injecting the engine. */
    fun configUpdate(context: Context, appHidden: Boolean): String {
        val config = """
            {"bg": ${Prefs.backgroundPlay(context)}, "autoplay": ${Prefs.autoplayNext(context)},
             "appHidden": $appHidden, "muteHidden": ${Prefs.muteHidden(context)}}
        """.trimIndent()
        return "(function(){try{if(window.__bgp&&window.__bgp.configure){window.__bgp.configure($config);}}catch(e){}})();"
    }
}
