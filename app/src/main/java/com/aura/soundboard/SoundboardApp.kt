package com.aura.soundboard

import android.app.Application
import com.aura.soundboard.di.AppContainer

class SoundboardApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized) {
            container.audioEngine.onTrimMemory()
        }
    }
}
