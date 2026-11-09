package com.via.bgplay.web

import java.lang.ref.WeakReference

/**
 * Weak registry of live tabs so that the playback service, the notification and the
 * media session can reach a WebView without keeping it alive.
 */
object TabRegistry {

    interface TabHandle {
        val pageUrl: String
        val pageTitle: String
        val isForeground: Boolean
        fun runJs(script: String)
        fun reload()
        fun bringToFront()
    }

    private val tabs = LinkedHashMap<String, WeakReference<TabHandle>>()

    @Synchronized
    fun register(tabId: String, handle: TabHandle) {
        tabs[tabId] = WeakReference(handle)
    }

    @Synchronized
    fun unregister(tabId: String) {
        tabs.remove(tabId)
    }

    @Synchronized
    fun get(tabId: String?): TabHandle? {
        if (tabId == null) return null
        val handle = tabs[tabId]?.get()
        if (handle == null) tabs.remove(tabId)
        return handle
    }

    @Synchronized
    fun all(): List<Pair<String, TabHandle>> {
        val out = ArrayList<Pair<String, TabHandle>>(tabs.size)
        val iterator = tabs.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val handle = entry.value.get()
            if (handle == null) iterator.remove() else out.add(entry.key to handle)
        }
        return out
    }

    @Synchronized
    fun clear() = tabs.clear()
}
