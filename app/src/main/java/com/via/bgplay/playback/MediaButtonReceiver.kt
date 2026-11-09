package com.via.bgplay.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.via.bgplay.prefs.Prefs

/**
 * Headset / Bluetooth / car stereo media buttons, resolved straight to a transport
 * command even when the service is not in the foreground yet.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return

        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> PlaybackController.execute(PlaybackController.ACTION_PLAY)
            KeyEvent.KEYCODE_MEDIA_PAUSE -> PlaybackController.execute(PlaybackController.ACTION_PAUSE)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> PlaybackController.execute(PlaybackController.ACTION_TOGGLE)
            KeyEvent.KEYCODE_MEDIA_NEXT -> PlaybackController.execute(PlaybackController.ACTION_NEXT)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlaybackController.execute(PlaybackController.ACTION_PREV)
            KeyEvent.KEYCODE_MEDIA_STOP -> PlaybackController.execute(PlaybackController.ACTION_STOP)
        }

        if (Prefs.backgroundPlay(context) && !BackgroundPlaybackService.isRunning) {
            BackgroundPlaybackService.start(context)
        }
    }
}
