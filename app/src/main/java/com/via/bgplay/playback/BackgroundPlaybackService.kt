package com.via.bgplay.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.content.ContextCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.via.bgplay.MainActivity
import com.via.bgplay.R
import com.via.bgplay.prefs.Prefs

/**
 * Keeps the browser process alive while media is playing and owns the rich media
 * notification + the system media session (lock screen, headset, Bluetooth, auto).
 *
 * The notification carries everything the user asked for:
 *   • video / music thumbnail
 *   • title, artist / owner, album and the site name
 *   • previous track, play / pause, next track
 *   • a seek bar with elapsed and remaining time
 *   • tap to return to the tab that is playing
 */
class BackgroundPlaybackService : Service() {

    companion object {
        private const val TAG = "BgpService"
        const val CHANNEL_ID = "via_media_playback"
        const val NOTIFICATION_ID = 4201
        private const val MEDIA_SESSION_TAG = "ViaBackgroundPlay"

        const val ACTION_START = "com.via.bgplay.START"
        const val ACTION_SYNC = "com.via.bgplay.SYNC"
        const val ACTION_STOP = "com.via.bgplay.STOP"
        const val ACTION_PLAY = "com.via.bgplay.PLAY"
        const val ACTION_PAUSE = "com.via.bgplay.PAUSE"
        const val ACTION_TOGGLE = "com.via.bgplay.TOGGLE"
        const val ACTION_NEXT = "com.via.bgplay.NEXT"
        const val ACTION_PREV = "com.via.bgplay.PREV"
        const val ACTION_STOP_PLAYBACK = "com.via.bgplay.STOP_PLAYBACK"
        const val ACTION_SEEK = "com.via.bgplay.SEEK"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, BackgroundPlaybackService::class.java)
                .setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot start service", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BackgroundPlaybackService::class.java)
                .setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                context.stopService(Intent(context, BackgroundPlaybackService::class.java))
            }
        }

        fun sync(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, BackgroundPlaybackService::class.java)
                .setAction(ACTION_SYNC)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "cannot sync service", e)
            }
        }
    }

    private var session: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var currentState: MediaState = MediaState.idle()
    private var currentArtwork: Bitmap? = null
    private var lastNotificationKey: String? = null
    private var lastMetadataKey: String? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                PlaybackController.execute(PlaybackController.ACTION_PAUSE)
            }
        }
    }

    private val stateListener: (MediaState) -> Unit = { state ->
        currentState = state
        onStateChanged(state)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        PlaybackCenter.appContext = applicationContext
        createChannel()
        createSession()
        PlaybackCenter.addListener(stateListener)
        try {
            registerReceiver(noisyReceiver, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        } catch (e: Exception) {
            Log.w(TAG, "cannot register noisy receiver", e)
        }
        currentState = PlaybackCenter.current ?: MediaState.idle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> PlaybackController.execute(PlaybackController.ACTION_PLAY)
            ACTION_PAUSE -> PlaybackController.execute(PlaybackController.ACTION_PAUSE)
            ACTION_TOGGLE -> PlaybackController.execute(PlaybackController.ACTION_TOGGLE)
            ACTION_NEXT -> PlaybackController.execute(PlaybackController.ACTION_NEXT)
            ACTION_PREV -> PlaybackController.execute(PlaybackController.ACTION_PREV)
            ACTION_STOP_PLAYBACK -> PlaybackController.execute(PlaybackController.ACTION_STOP)
            ACTION_SEEK -> {
                val seconds = intent.getLongExtra("position", 0L) / 1000.0
                PlaybackController.execute(PlaybackController.ACTION_SEEK, seconds)
            }
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_SYNC -> {
                if (!Prefs.backgroundPlay(this)) {
                    shutdown()
                    return START_NOT_STICKY
                }
                if (currentState.isActive) ensureForeground() else shutdown()
            }
            else -> ensureForeground()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the user swipes the app away: the page keeps running in
        // the WebView process as long as this service is alive.
        if (Prefs.backgroundPlay(this) && currentState.playing) {
            PlaybackController.refreshTab(PlaybackCenter.activeTab)
        } else {
            shutdown()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        PlaybackCenter.removeListener(stateListener)
        try {
            unregisterReceiver(noisyReceiver)
        } catch (e: Exception) { }
        releaseWakeLock()
        session?.isActive = false
        session?.release()
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ session

    private fun createSession() {
        val s = MediaSessionCompat(this, MEDIA_SESSION_TAG)
        s.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        s.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                PlaybackController.execute(PlaybackController.ACTION_PLAY)
            }

            override fun onPause() {
                PlaybackController.execute(PlaybackController.ACTION_PAUSE)
            }

            override fun onSkipToNext() {
                PlaybackController.execute(PlaybackController.ACTION_NEXT)
            }

            override fun onSkipToPrevious() {
                PlaybackController.execute(PlaybackController.ACTION_PREV)
            }

            override fun onStop() {
                PlaybackController.execute(PlaybackController.ACTION_STOP)
            }

            override fun onSeekTo(pos: Long) {
                PlaybackController.execute(PlaybackController.ACTION_SEEK, pos / 1000.0)
            }
        })
        s.isActive = true
        session = s
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bgp_background_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.bgp_background_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------ state

    private fun onStateChanged(state: MediaState) {
        if (state.isActive) {
            ensureForeground()
            updateSession(state)
            updateWakeLock(state.playing)
            val url = state.info?.artworkUrl
            val cached = ArtworkLoader.cached(url)
            if (cached != null) {
                currentArtwork = cached
                postNotification(state)
            } else if (url.isNullOrBlank()) {
                currentArtwork = null
                postNotification(state)
            } else {
                postNotification(state)
                ArtworkLoader.load(url) { bitmap ->
                    if (bitmap != null && bitmap != currentArtwork) {
                        currentArtwork = bitmap
                        val latest = currentState
                        if (latest.info?.artworkUrl == url) postNotification(latest)
                    }
                }
            }
        } else {
            // Nothing is playing any more: drop the foreground state and the notif.
            stopForegroundCompat()
            updateWakeLock(false)
            session?.isActive = Prefs.mediaSession(this)
            val nm = NotificationManagerCompat.from(this)
            nm.cancel(NOTIFICATION_ID)
            lastNotificationKey = null
            stopSelf()
        }
    }

    private fun ensureForeground() {
        if (foregroundStarted) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(currentState))
            return
        }
        val notification = buildNotification(currentState)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun stopForegroundCompat() {
        if (!foregroundStarted) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) { }
        foregroundStarted = false
    }

    private fun updateWakeLock(playing: Boolean) {
        val wanted = playing && Prefs.wakeLock(this) && Prefs.backgroundPlay(this)
        if (wanted) {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "via:playback").apply {
                    setReferenceCounted(false)
                    try { acquire(6 * 60 * 60 * 1000L) } catch (e: Exception) { }
                }
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { }
        wakeLock = null
    }

    // ------------------------------------------------------------------ session

    private fun updateSession(state: MediaState) {
        val s = session ?: return
        val info = state.info ?: return
        val enabled = Prefs.mediaSession(this)
        if (s.isActive != enabled) s.isActive = enabled
        if (!enabled) return

        // Metadata only changes when the track changes; the playback state (position,
        // speed) is refreshed on every update so the lock screen scrubber stays live.
        val metadataKey = (info.title + '|' + info.artist + '|' + info.durationMs + '|' +
            (currentArtwork?.hashCode() ?: 0))
        if (metadataKey != lastMetadataKey) {
            lastMetadataKey = metadataKey
            s.setMetadata(buildMetadata(info))
        }

        updatePlaybackState(state, info)
    }

    private fun buildMetadata(info: MediaInfo): MediaMetadataCompat {
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.notifTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.album)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, info.notifTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, info.notifSubtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, info.siteName)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, info.durationMs)
            .apply {
                currentArtwork?.let { art ->
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                    putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
                    putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
                }
            }
            .build()
    }

    private fun updatePlaybackState(state: MediaState, info: MediaInfo) {
        val s = session ?: return
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP
        val actionsWithSkip = actions or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actionsWithSkip)
            .setState(
                if (state.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                info.positionMs,
                if (state.playing) 1f else 0f,
                SystemClock.elapsedRealtime()
            )
            .build()
        s.setPlaybackState(playbackState)
    }

    // ------------------------------------------------------------------ notification

    private fun buildNotification(state: MediaState): Notification {
        val info = state.info
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(session?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.bgp_ic_notification)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(0xFF6C8CFF.toInt())
            .setStyle(mediaStyle)

        if (info == null) {
            builder
                .setContentTitle(getString(R.string.bgp_app_name))
                .setContentText(getString(R.string.bgp_nothing_playing))
                .setOngoing(false)
                .setContentIntent(openAppIntent(null))
                .setDeleteIntent(stopPlaybackIntent())
            return builder.build()
        }

        builder.setOngoing(state.playing)
        builder.setContentTitle(info.notifTitle)
        builder.setContentText(info.notifSubtitle)
        builder.setSubText(info.siteName)
        builder.setContentIntent(openAppIntent(info.pageUrl))
        builder.setDeleteIntent(stopPlaybackIntent())
        builder.setColorized(false)

        // Thumbnail: the video frame / album art, full bleed where the OS allows it.
        val artwork = currentArtwork
        if (artwork != null) {
            builder.setLargeIcon(artwork)
        } else {
            builder.setLargeIcon(defaultIcon())
        }

        if (info.durationMs > 0) {
            builder.setProgress(1000, progressPermille(info), false)
        }

        // Previous track
        builder.addAction(
            NotificationCompat.Action(
                R.drawable.bgp_notif_action_prev,
                getString(R.string.bgp_previous),
                servicePendingIntent(ACTION_PREV, 10)
            )
        )
        // Play / pause
        if (state.playing) {
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.bgp_notif_action_pause,
                    getString(R.string.bgp_pause),
                    servicePendingIntent(ACTION_PAUSE, 11)
                )
            )
        } else {
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.bgp_notif_action_play,
                    getString(R.string.bgp_play),
                    servicePendingIntent(ACTION_PLAY, 12)
                )
            )
        }
        // Next track
        builder.addAction(
            NotificationCompat.Action(
                R.drawable.bgp_notif_action_next,
                getString(R.string.bgp_next),
                servicePendingIntent(ACTION_NEXT, 13)
            )
        )

        // -15s / +15s for the expanded view
        if (info.durationMs > 30_000) {
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.bgp_notif_action_prev,
                    "-15s",
                    seekIntent(info.positionMs - 15_000, 14)
                )
            )
            builder.addAction(
                NotificationCompat.Action(
                    R.drawable.bgp_notif_action_next,
                    "+15s",
                    seekIntent(info.positionMs + 15_000, 15)
                )
            )
        }

        return builder.build()
    }

    /** Vector icon rendered into a bitmap so it can be used as a large icon. */
    private fun defaultIcon(): Bitmap {
        val size = (96 * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF2B3242.toInt())
        val icon = ContextCompat.getDrawable(this, R.drawable.bgp_ic_notification)
        if (icon != null) {
            val inset = (size * 0.22f).toInt()
            icon.setBounds(inset, inset, size - inset, size - inset)
            icon.setTint(Color.WHITE)
            icon.draw(canvas)
        }
        return bitmap
    }

    private fun progressPermille(info: MediaInfo): Int {
        if (info.durationMs <= 0) return 0
        return ((info.positionMs.toDouble() / info.durationMs) * 1000).toInt().coerceIn(0, 1000)
    }

    private fun postNotification(state: MediaState) {
        if (!Prefs.mediaNotification(this)) {
            // Notification disabled: the service still needs a foreground notification,
            // so publish a minimal one instead of nothing.
            val minimal = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.bgp_ic_notification)
                .setContentTitle(state.info?.notifTitle ?: getString(R.string.bgp_app_name))
                .setContentText(getString(R.string.bgp_background_play))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
            notifySafely(minimal, "minimal", state)
            return
        }
        val info = state.info
        // The state is published roughly once a second (position updates). Rebuilding
        // the notification that often would flicker and waste battery, so it is only
        // refreshed when something visible changed or every five seconds for the bar.
        val key = buildString {
            append(info?.title).append('|')
            append(info?.artist).append('|')
            append(state.playing).append('|')
            append(currentArtwork?.hashCode()).append('|')
            append(info?.siteName).append('|')
            append((info?.positionMs ?: 0L) / 5000)
        }
        if (key == lastNotificationKey && foregroundStarted) return
        lastNotificationKey = key
        notifySafely(buildNotification(state), key, state)
    }

    private fun notifySafely(notification: Notification, key: String, state: MediaState) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission missing", e)
        } catch (e: Exception) {
            Log.w(TAG, "cannot post notification", e)
        }
        if (!foregroundStarted && state.isActive) {
            try {
                startForeground(NOTIFICATION_ID, notification)
                foregroundStarted = true
            } catch (e: Exception) { }
        }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, BackgroundPlaybackService::class.java).setAction(action)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    private fun seekIntent(positionMs: Long, requestCode: Int): PendingIntent {
        val clamped = positionMs.coerceAtLeast(0)
        val intent = Intent(this, BackgroundPlaybackService::class.java)
            .setAction(ACTION_SEEK)
            .putExtra("position", clamped)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    private fun stopPlaybackIntent(): PendingIntent {
        val intent = Intent(this, BackgroundPlaybackService::class.java).setAction(ACTION_STOP_PLAYBACK)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, 16, intent, flags)
    }

    private fun openAppIntent(url: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!url.isNullOrBlank()) putExtra(MainActivity.EXTRA_OPEN_URL, url)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 17, intent, flags)
    }

    private fun shutdown() {
        updateWakeLock(false)
        stopForegroundCompat()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }
}
