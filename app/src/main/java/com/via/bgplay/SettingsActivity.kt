package com.via.bgplay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.via.bgplay.playback.BackgroundPlaybackService
import com.via.bgplay.prefs.Prefs
import com.via.bgplay.widget.PillSwitch

class SettingsActivity : AppCompatActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateRows() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_ViaBg_Settings)
        setContentView(R.layout.bgp_activity_settings)

        findViewById<android.view.View>(R.id.settings_back).setOnClickListener { finish() }

        bind(Prefs.KEY_BACKGROUND_PLAY, R.id.switch_background_play) { checked ->
            Prefs.setBackgroundPlay(this, checked)
            if (checked) BackgroundPlaybackService.sync(this) else BackgroundPlaybackService.stop(this)
        }
        bind(Prefs.KEY_MEDIA_NOTIFICATION, R.id.switch_media_notification) {
            Prefs.setMediaNotification(this, it)
            BackgroundPlaybackService.sync(this)
        }
        bind(Prefs.KEY_MEDIA_SESSION, R.id.switch_media_session) {
            Prefs.setMediaSession(this, it)
            BackgroundPlaybackService.sync(this)
        }
        bind(Prefs.KEY_MUTE_HIDDEN, R.id.switch_mute_hidden, default = false) { Prefs.setMuteHidden(this, it) }
        bind(Prefs.KEY_WAKE_LOCK, R.id.switch_wake_lock) {
            Prefs.setWakeLock(this, it)
            BackgroundPlaybackService.sync(this)
        }

        findViewById<android.view.View>(R.id.row_notification_permission).setOnClickListener {
            requestNotifications()
        }
        findViewById<android.view.View>(R.id.row_battery).setOnClickListener {
            requestIgnoreBattery()
        }

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
        findViewById<TextView>(R.id.about_version).text = getString(R.string.bgp_version_fmt, version)
    }

    override fun onResume() {
        super.onResume()
        updateRows()
    }

    private fun bind(key: String, viewId: Int, default: Boolean = true, onChange: (Boolean) -> Unit) {
        val switch = findViewById<PillSwitch>(viewId)
        val stored = Prefs.get(this).getBoolean(key, default)
        switch.setCheckedSilently(stored)
        switch.listener = { checked -> onChange(checked) }
    }

    private fun updateRows() {
        val notificationState = findViewById<TextView>(R.id.notification_permission_state)
        val granted = NotificationManagerCompat.from(this).areNotificationsEnabled()
        notificationState.text = getString(
            if (granted) R.string.bgp_permission_granted else R.string.bgp_permission_denied
        )
        notificationState.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.bgp_accent else R.color.bgp_recording)
        )

        val batteryState = findViewById<TextView>(R.id.battery_state)
        val ignoring = isIgnoringBatteryOptimisations()
        batteryState.text = getString(
            if (ignoring) R.string.bgp_battery_ignored else R.string.bgp_battery_not_ignored
        )
        batteryState.setTextColor(
            ContextCompat.getColor(this, if (ignoring) R.color.bgp_accent else R.color.bgp_recording)
        )
    }

    private fun isIgnoringBatteryOptimisations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // Already granted (or pre-13): send the user to the system screen so they can
        // change the channel behaviour (e.g. pin it on the lock screen).
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isIgnoringBatteryOptimisations()) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
