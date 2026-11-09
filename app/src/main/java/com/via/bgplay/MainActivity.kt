package com.via.bgplay

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.via.bgplay.playback.BackgroundPlaybackService
import com.via.bgplay.playback.MediaState
import com.via.bgplay.playback.PlaybackCenter
import com.via.bgplay.playback.PlaybackController
import com.via.bgplay.prefs.Prefs
import com.via.bgplay.web.BrowserTab
import com.via.bgplay.web.PlaybackInjector
import com.via.bgplay.web.ViaWebChromeClient
import com.via.bgplay.web.ViaWebViewClient
import com.via.bgplay.widget.PillSwitch
import java.util.Locale


class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_URL = "open_url"
        private const val HOME_URL = "about:home"
        private const val SEARCH_URL = "https://www.google.com/search?q="
        private const val STATE_TABS = "tabs"
        private const val STATE_SELECTED = "selected"

        private val QUICK_LINKS = listOf(
            QuickLink("YouTube", "https://m.youtube.com", R.drawable.ic_play, true),
            QuickLink("YouTube Music", "https://music.youtube.com", R.drawable.ic_music_note, true),
            QuickLink("SoundCloud", "https://m.soundcloud.com", R.drawable.ic_headphones, true),
            QuickLink("Spotify", "https://open.spotify.com", R.drawable.ic_music_note, true),
            QuickLink("Bandcamp", "https://bandcamp.com", R.drawable.ic_headphones, true)
        )

        data class QuickLink(
            val title: String,
            val url: String,
            val icon: Int,
            val audioFriendly: Boolean
        )
    }

    private lateinit var webContainer: FrameLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var browserChrome: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var addressBar: TextView
    private lateinit var pageProgress: View
    private lateinit var miniPlayer: LinearLayout
    private lateinit var playerSheet: LinearLayout
    private lateinit var tabCount: TextView

    private val tabs = LinkedHashMap<String, BrowserTab>()
    private var selectedTabId: String? = null
    private var homeView: View? = null

    private var playerSheetOpen = false
    private var seekBarDragging = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.bgp_notification_blocked, Toast.LENGTH_LONG).show()
            }
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.bgp_activity_main)
        bindViews()
        wireChrome()

        PlaybackCenter.addListener(stateListener)

        if (savedInstanceState != null) {
            restoreTabs(savedInstanceState)
        }
        if (tabs.isEmpty()) {
            val url = intentUrl() ?: Prefs.lastUrl(this)?.takeIf { it != HOME_URL } ?: HOME_URL
            createTab(url)
        }
        showHomeIfNeeded()

        handleIntent(intent)
        askForNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val requested = intent?.getStringExtra(EXTRA_OPEN_URL) ?: return
        if (requested.isBlank()) return
        val existing = tabs[selectedTabId]
        if (existing != null) existing.load(requested) else createTab(requested)
    }

    private fun intentUrl(): String? {
        val data: Uri? = intent?.data
        if (data != null && (data.scheme == "http" || data.scheme == "https")) return data.toString()
        return null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val urls = tabs.values.map { it.pageUrl }
        outState.putStringArrayList(STATE_TABS, ArrayList(urls))
        outState.putInt(STATE_SELECTED, tabs.keys.indexOf(selectedTabId))
    }

    private fun restoreTabs(state: Bundle) {
        val urls = state.getStringArrayList(STATE_TABS) ?: return
        val selected = state.getInt(STATE_SELECTED, 0)
        urls.forEachIndexed { index, url ->
            val tab = createTabInternal(url)
            if (index == selected) selectTab(tab.id)
        }
    }

    override fun onResume() {
        super.onResume()
        // The OS may have detached our views while we were away; make sure the selected
        // tab (and only it) is attached again before playback state is pushed.
        val current = selectedTabId
        if (current != null) selectTab(current) else showHomeIfNeeded()
        tabs[selectedTabId]?.onResume()
        pushConfig(appHidden = false)
        PlaybackController.refreshTab(PlaybackCenter.activeTab)
        renderState(PlaybackCenter.current ?: MediaState.idle())
    }

    override fun onPause() {
        super.onPause()
        // Background play needs the surface kept alive; FLAG_KEEP_SCREEN_ON is not what
        // we want, but the WebView must keep rendering off-screen, which Android allows
        // as long as the activity is not destroyed.
        if (Prefs.backgroundPlay(this)) {
            // Background play: do *not* pause the WebView. Tell the injected engine that
            // the app is hidden so it may take stronger measures to keep audio alive.
            pushConfig(appHidden = true)
            val state = PlaybackCenter.current
            if (state != null && state.isActive) {
                BackgroundPlaybackService.start(this)
            }
        } else {
            tabs[selectedTabId]?.onPause()
        }
    }

    private val stateListener: (MediaState) -> Unit = { state ->
        runOnUiThread { renderState(state) }
    }

    override fun onDestroy() {
        PlaybackCenter.removeListener(stateListener)
        if (isFinishing) {
            tabs.values.forEach { it.destroy() }
            tabs.clear()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ views

    private fun bindViews() {
        webContainer = findViewById(R.id.web_container)
        contentContainer = findViewById(R.id.content_container)
        browserChrome = findViewById(R.id.browser_chrome)
        bottomBar = findViewById(R.id.bottom_bar)
        addressBar = findViewById(R.id.address_bar)
        pageProgress = findViewById(R.id.page_progress)
        miniPlayer = findViewById(R.id.mini_player)
        playerSheet = findViewById(R.id.player_sheet)
        tabCount = findViewById(R.id.tab_count)
    }

    private fun wireChrome() {
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            if (!(tabs[selectedTabId]?.goBack() ?: false)) finish()
        }
        findViewById<ImageButton>(R.id.btn_forward).setOnClickListener {
            tabs[selectedTabId]?.goForward()
        }
        findViewById<ImageButton>(R.id.btn_tabs).setOnClickListener { showTabsPanel() }
        findViewById<ImageButton>(R.id.btn_desktop).setOnClickListener { toggleDesktop() }
        findViewById<ImageButton>(R.id.btn_audio).setOnClickListener {
            val state = PlaybackCenter.current
            if (state != null && state.isActive) {
                openPlayer()
            } else {
                Toast.makeText(this, R.string.bgp_nothing_playing, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<ImageButton>(R.id.btn_background_play).setOnClickListener {
            // One tap: jump straight into the player when something plays, otherwise
            // flip the master switch.
            val state = PlaybackCenter.current
            if (state != null && state.isActive && !state.stale) {
                openPlayer()
            } else {
                setBackgroundPlay(!Prefs.backgroundPlay(this))
            }
        }
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { showOverflowMenu() }
        findViewById<ImageButton>(R.id.btn_home).setOnClickListener {
            val tab = tabs[selectedTabId]
            if (tab != null) tab.load(HOME_URL) else createTab(HOME_URL)
            showHomeIfNeeded()
        }
        addressBar.setOnClickListener { showAddressDialog() }

        // Mini player
        miniPlayer.setOnClickListener { openPlayer() }
        findViewById<ImageButton>(R.id.mini_play).setOnClickListener {
            PlaybackController.execute(PlaybackController.ACTION_TOGGLE)
        }
        findViewById<ImageButton>(R.id.mini_prev).setOnClickListener {
            PlaybackController.execute(PlaybackController.ACTION_PREV)
        }
        findViewById<ImageButton>(R.id.mini_next).setOnClickListener {
            PlaybackController.execute(PlaybackController.ACTION_NEXT)
        }
        findViewById<ImageButton>(R.id.mini_close).setOnClickListener {
            PlaybackController.execute(PlaybackController.ACTION_STOP)
            miniPlayer.isVisible = false
        }

        // Full player
        findViewById<ImageButton>(R.id.player_collapse).setOnClickListener { closePlayer() }
        findViewById<ImageButton>(R.id.player_play).setOnClickListener {
            PlaybackController.execute(PlaybackController.ACTION_TOGGLE)
        }
        findViewById<ImageButton>(R.id.player_prev).setOnClickListener {
            PlaybackController.execute(PlaybackController.ACTION_PREV)
        }
        findViewById<ImageButton>(R.id.player_next).setOnClickListener {
            PlaybackController.execute(PlaybackController.ACTION_NEXT)
        }
        val seek = findViewById<SeekBar>(R.id.player_seek)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val state = PlaybackCenter.current ?: return
                val duration = state.info?.durationMs ?: 0L
                if (duration > 0) {
                    findViewById<TextView>(R.id.player_position).text =
                        formatTime(duration * progress / 1000)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                seekBarDragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                seekBarDragging = false
                val state = PlaybackCenter.current ?: return
                val duration = state.info?.durationMs ?: 0L
                if (duration <= 0) return
                val target = (duration * (bar?.progress ?: 0) / 1000) / 1000.0
                PlaybackController.execute(PlaybackController.ACTION_SEEK, target)
            }
        })

        val flingDetector = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (velocityY > 400) {
                        closePlayer()
                        return true
                    }
                    return false
                }
            }
        )
        playerSheet.setOnTouchListener { _, event -> flingDetector.onTouchEvent(event) }
    }

    // ------------------------------------------------------------------ home

    private fun showHomeIfNeeded() {
        val tab = tabs[selectedTabId]
        val url = tab?.pageUrl ?: ""
        val isHome = url.isBlank() || url == HOME_URL || url == "about:blank"
        if (isHome) {
            showHome()
        } else {
            showWeb()
        }
        miniPlayer.isVisible = PlaybackCenter.current?.isActive == true
    }

    private fun showHome() {
        if (homeView == null) {
            homeView = LayoutInflater.from(this).inflate(R.layout.bgp_view_home, contentContainer, false)
            findViewById<View>(R.id.toolbar_settings).setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            homeView?.findViewById<PillSwitch>(R.id.home_bg_switch)?.apply {
                setCheckedSilently(Prefs.backgroundPlay(this@MainActivity))
                listener = { checked ->
                    Prefs.setBackgroundPlay(this@MainActivity, checked)
                    pushConfig(appHidden = false)
                    if (checked) BackgroundPlaybackService.sync(this@MainActivity)
                    else BackgroundPlaybackService.stop(this@MainActivity)
                    updateHomeCards()
                }
            }
            buildQuickLinks()
            buildRecentLinks()
        }
        val slot = findViewById<FrameLayout>(R.id.content_slot)
        val target = homeView
        if (target != null && target.parent !== slot) {
            (target.parent as? ViewGroup)?.removeView(target)
            slot.removeAllViews()
            slot.addView(target)
        }
        contentContainer.isVisible = true
        webContainer.isVisible = false
        addressBar.text = ""
        updateHomeCards()
        updateTabCount()
    }

    private fun showWeb() {
        contentContainer.isVisible = false
        webContainer.isVisible = true
        val tab = tabs[selectedTabId]
        addressBar.text = if (tab == null || tab.pageUrl == HOME_URL) "" else tab.pageUrl
        updateTabCount()
        // The selected tab must be the attached, visible one after the swap.
        tab?.attach()
    }

    private fun updateHomeCards() {
        val root = homeView ?: return
        val enabled = Prefs.backgroundPlay(this)
        root.findViewById<TextView>(R.id.home_bg_title)?.text =
            getString(if (enabled) R.string.bgp_home_background_on else R.string.bgp_home_background_off)
        root.findViewById<TextView>(R.id.home_bg_summary)?.text =
            getString(
                if (enabled) R.string.bgp_home_background_summary_on
                else R.string.bgp_home_background_summary_off
            )
        root.findViewById<PillSwitch>(R.id.home_bg_switch)?.setCheckedSilently(enabled)
    }

    private fun buildQuickLinks() {
        val container = homeView?.findViewById<LinearLayout>(R.id.home_shortcut_container) ?: return
        container.removeAllViews()
        for (link in QUICK_LINKS) {
            val row = LayoutInflater.from(this)
                .inflate(R.layout.bgp_item_shortcut, container, false)
            row.findViewById<TextView>(R.id.shortcut_title).text = link.title
            row.findViewById<TextView>(R.id.shortcut_subtitle).text =
                Uri.parse(link.url).host ?: link.url
            row.findViewById<ImageView>(R.id.shortcut_icon).setImageResource(link.icon)
            row.findViewById<View>(R.id.shortcut_badge).isVisible = link.audioFriendly
            row.setOnClickListener { openInNewTab(link.url) }
            container.addView(row)
        }
    }

    private fun buildRecentLinks() {
        val container = homeView?.findViewById<LinearLayout>(R.id.home_recent_container) ?: return
        val label = homeView?.findViewById<TextView>(R.id.home_recent_label) ?: return
        container.removeAllViews()
        val recent = RecentSites.list(this)
        label.isVisible = recent.isNotEmpty()
        for (site in recent.take(6)) {
            val row = LayoutInflater.from(this)
                .inflate(R.layout.bgp_item_shortcut, container, false)
            row.findViewById<TextView>(R.id.shortcut_title).text = site.second
            row.findViewById<TextView>(R.id.shortcut_subtitle).text = site.first
            row.findViewById<View>(R.id.shortcut_badge).isVisible = false
            row.setOnClickListener { openInNewTab(site.first) }
            container.addView(row)
        }
    }

    // ------------------------------------------------------------------ tabs

    private fun createTab(url: String): BrowserTab {
        val tab = createTabInternal(url)
        selectTab(tab.id)
        return tab
    }

    private fun createTabInternal(url: String): BrowserTab {
        val tab = BrowserTab(this, webContainer, url)
        tabs[tab.id] = tab
        tab.webView.webViewClient = ViaWebViewClient(object : ViaWebViewClient.Callbacks {
            override fun onPageStarted(url: String?) {
                showWebChromeProgress(5)
                if (tab.id == selectedTabId && url != null) addressBar.text = url
            }

            override fun onPageFinished(url: String?, title: String?) {
                showWebChromeProgress(100)
                tab.updateUrl(url)
                tab.updateTitle(title)
                if (tab.id == selectedTabId) addressBar.text = tab.pageUrl
                updateTabCount()
                if (url != null && url != HOME_URL && !url.startsWith("about:")) {
                    RecentSites.remember(this@MainActivity, tab.pageUrl, title ?: tab.pageUrl)
                }
            }

            override fun onUrlChanged(url: String?) {
                tab.updateUrl(url)
                if (tab.id == selectedTabId && url != null && url != HOME_URL) {
                    addressBar.text = url
                    if (contentContainer.isVisible) showWeb()
                }
            }

            override fun shouldOverride(url: String, isMainFrame: Boolean): Boolean {
                if (url.startsWith("intent:") || url.startsWith("market:")) {
                    try { startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME)) } catch (e: Exception) { }
                    return true
                }
                return if (isMainFrame) {
                    tab.updateUrl(url)
                    false
                } else {
                    false
                }
            }

            override fun injectEngine() {
                injectEngineInto(tab)
            }

            override fun onNavigating() {
                PlaybackCenter.onTabNavigating(tab.id)
            }
        })
        tab.webView.webChromeClient = ViaWebChromeClient(object : ViaWebChromeClient.Callbacks {
            override fun onProgress(progress: Int) = showWebChromeProgress(progress)
            override fun onTitle(title: String?) {
                tab.updateTitle(title)
                updateTabCount()
            }

            override fun onIcon(icon: Bitmap?) { }
        })
        tab.attach()
        tab.load(url)
        return tab
    }

    private fun injectEngineInto(tab: BrowserTab) {
        val script = PlaybackInjector.build(this, appHidden = !isActivityResumed())
        tab.webView.evaluateJavascript(script, null)
    }

    private fun selectTab(tabId: String) {
        val tab = tabs[tabId] ?: return
        selectedTabId = tabId
        for ((id, other) in tabs) {
            other.setSelected(id == tabId)
            other.webView.isVisible = id == tabId
        }
        tab.attach()
        addressBar.text = if (tab.pageUrl == HOME_URL) "" else tab.pageUrl
        showHomeIfNeeded()
        updateTabCount()
    }

    private fun openInNewTab(url: String) {
        selectTab(createTabInternal(url).also { showWeb() }.id)
    }

    private fun closeTab(tabId: String) {
        val tab = tabs.remove(tabId) ?: return
        PlaybackCenter.onTabClosed(tabId)
        tab.destroy()
        if (selectedTabId == tabId) {
            val next = tabs.keys.lastOrNull()
            if (next != null) {
                selectTab(next)
            } else {
                selectedTabId = null
                createTab(HOME_URL)
            }
        }
        updateTabCount()
    }

    private fun updateTabCount() {
        tabCount.text = tabs.size.toString()
        tabCount.isVisible = tabs.size > 0
    }

    private fun showTabsPanel() {
        val entries = tabs.values.map { tab ->
            val title = tab.pageTitle.ifBlank { Uri.parse(tab.pageUrl).host ?: tab.pageUrl }
            title
        }.toTypedArray()
        val builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.bgp_tabs)
            .setItems(entries) { _, which ->
                val id = tabs.keys.elementAtOrNull(which) ?: return@setItems
                selectTab(id)
            }
            .setPositiveButton(R.string.bgp_add_tab) { _, _ -> createTab(HOME_URL) }
            .setNegativeButton(R.string.bgp_close) { _, _ -> }
        if (tabs.size > 1) {
            builder.setNeutralButton(R.string.bgp_close_tab) { _, _ ->
                selectedTabId?.let { closeTab(it) }
            }
        }
        builder.show()
    }

    // ------------------------------------------------------------------ chrome

    private fun showWebChromeProgress(progress: Int) {
        if (progress in 1..99) {
            pageProgress.isVisible = true
            (pageProgress as android.widget.ProgressBar).progress = progress
        } else {
            pageProgress.isVisible = false
        }
    }

    private fun showAddressDialog() {
        val input = EditText(this).apply {
            setText(if (tabs[selectedTabId]?.pageUrl == HOME_URL) "" else tabs[selectedTabId]?.pageUrl ?: "")
            setSelection(text.length)
            setSingleLine()
            hint = getString(R.string.bgp_search_hint)
            setTextColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigate(input.text.toString())
                dialog.dismiss()
                true
            } else false
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                navigate(input.text.toString())
                dialog.dismiss()
            }
            input.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun navigate(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val url = normalize(text)
        val tab = tabs[selectedTabId]
        if (tab != null) {
            tab.load(url)
        } else {
            createTab(url)
        }
        if (url == HOME_URL) showHome() else showWeb()
        Prefs.setLastUrl(this, url)
    }

    private fun normalize(text: String): String {
        if (text == HOME_URL) return text
        if (text.startsWith("http://") || text.startsWith("https://") || text.startsWith("file:") ||
            text.startsWith("about:")
        ) {
            return text
        }
        val looksLikeHost = !text.contains(' ') &&
            (text.contains('.') || text == "localhost" || text.startsWith("localhost:"))
        return if (looksLikeHost) "https://$text" else SEARCH_URL + Uri.encode(text)
    }

    private fun toggleDesktop() {
        val next = !Prefs.desktopMode(this)
        Prefs.setDesktopMode(this, next)
        tabs[selectedTabId]?.setDesktopMode(next)
        toast(if (next) R.string.bgp_desktop_mode else R.string.bgp_menu_ua_android)
    }

    private fun showOverflowMenu() {
        val items = arrayOf(
            getString(if (Prefs.backgroundPlay(this)) R.string.bgp_background_play_off else R.string.bgp_background_play_on),
            getString(R.string.bgp_settings),
            getString(R.string.bgp_menu_open_player),
            getString(R.string.bgp_desktop_mode),
            getString(R.string.bgp_menu_share),
            getString(R.string.bgp_reload)
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setBackgroundPlay(!Prefs.backgroundPlay(this))
                    1 -> startActivity(Intent(this, SettingsActivity::class.java))
                    2 -> openPlayer()
                    3 -> toggleDesktop()
                    4 -> shareCurrent()
                    5 -> tabs[selectedTabId]?.reload()
                }
            }
            .show()
    }

    private fun shareCurrent() {
        val tab = tabs[selectedTabId] ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, tab.pageUrl)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.bgp_menu_share)))
    }

    private fun pushConfig(appHidden: Boolean) {
        val script = PlaybackInjector.configUpdate(this, appHidden)
        for (tab in tabs.values) {
            tab.runJs(script)
        }
    }

    // ------------------------------------------------------------------ player

    private fun openPlayer() {
        val state = PlaybackCenter.current
        if (state == null || !state.isActive) {
            toast(R.string.bgp_nothing_playing)
            return
        }
        playerSheetOpen = true
        playerSheet.isVisible = true
        playerSheet.alpha = 0f
        playerSheet.animate().alpha(1f).setDuration(160).start()
        renderState(state)
    }

    private fun closePlayer() {
        playerSheetOpen = false
        playerSheet.animate().alpha(0f).setDuration(120).withEndAction {
            playerSheet.isVisible = false
        }.start()
    }

    override fun onBackPressed() {
        when {
            playerSheetOpen -> closePlayer()
            contentContainer.isVisible -> finish()
            tabs[selectedTabId]?.goBack() == true -> Unit
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun renderState(state: MediaState) {
        val info = state.info
        val miniVisible = info != null && !playerSheetOpen
        miniPlayer.isVisible = miniVisible
        if (info == null) {
            if (playerSheetOpen) closePlayer()
            return
        }

        val subtitle = buildString {
            if (info.artist.isNotBlank()) append(info.artist)
            if (info.siteName.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(info.siteName)
            }
        }

        findViewById<TextView>(R.id.mini_title).text = info.notifTitle
        findViewById<TextView>(R.id.mini_subtitle).text = subtitle
        findViewById<ImageButton>(R.id.mini_play).setImageResource(
            if (state.playing) R.drawable.ic_pause else R.drawable.ic_play
        )

        if (playerSheetOpen) {
            findViewById<TextView>(R.id.player_title).text = info.notifTitle
            findViewById<TextView>(R.id.player_subtitle).text =
                info.artist.ifBlank { getString(R.string.bgp_unknown_artist) }
            findViewById<TextView>(R.id.player_site).text =
                listOf(info.album, info.siteName).filter { it.isNotBlank() }.joinToString(" · ")
            findViewById<ImageButton>(R.id.player_play).setImageResource(
                if (state.playing) R.drawable.ic_pause else R.drawable.ic_play
            )
            findViewById<TextView>(R.id.player_duration).text = formatTime(info.durationMs)
            if (!seekBarDragging) {
                val seek = findViewById<SeekBar>(R.id.player_seek)
                val max = if (info.durationMs > 0) 1000 else 0
                seek.max = max.coerceAtLeast(1)
                seek.progress = if (info.durationMs > 0) {
                    ((info.positionMs.toDouble() / info.durationMs) * 1000).toInt().coerceIn(0, 1000)
                } else 0
                findViewById<TextView>(R.id.player_position).text = formatTime(info.positionMs)
            }
            findViewById<TextView>(R.id.player_state).text = buildString {
                append(getString(if (state.playing) R.string.bgp_playing else R.string.bgp_paused))
                append(" · ")
                append(
                    getString(
                        if (Prefs.backgroundPlay(this@MainActivity)) R.string.bgp_home_background_on
                        else R.string.bgp_home_background_off
                    )
                )
            }
        }

        ArtworkView.set(info.artworkUrl, findViewById<ImageView>(R.id.mini_art), findViewById(R.id.mini_art_fallback))
        if (playerSheetOpen) {
            ArtworkView.set(info.artworkUrl, findViewById<ImageView>(R.id.player_art), null)
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    // ------------------------------------------------------------------ permissions

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun isActivityResumed(): Boolean =
        lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    private fun setBackgroundPlay(enabled: Boolean) {
        Prefs.setBackgroundPlay(this, enabled)
        pushConfig(appHidden = !isActivityResumed())
        if (enabled) {
            BackgroundPlaybackService.sync(this)
        } else {
            BackgroundPlaybackService.stop(this)
        }
        toast(if (enabled) R.string.bgp_background_play_on else R.string.bgp_background_play_off)
        updateHomeCards()
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) renderState(PlaybackCenter.current ?: MediaState.idle())
    }
}
