package com.mangoscreencast.tv

import android.app.Application
import com.mangoscreencast.tv.cast.ReceiverHub

class MangoApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var hub: ReceiverHub
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        hub = ReceiverHub(this, settings)
    }

    companion object {
        lateinit var instance: MangoApp
            private set

        val hub: ReceiverHub get() = instance.hub
        val settings: Settings get() = instance.settings
    }
}
