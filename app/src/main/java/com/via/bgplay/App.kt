package com.via.bgplay

import android.app.Application
import com.via.bgplay.playback.PlaybackCenter

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        PlaybackCenter.appContext = this
    }
}
