package com.mangoscreencast.tv

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.util.Log
import com.mangoscreencast.tv.airplay.AirPlayReceiver
import com.mangoscreencast.tv.ui.MirrorActivity

class MangoApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var receiver: AirPlayReceiver
        private set

    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        receiver = AirPlayReceiver(this, settings)
        receiver.onMirroringStarted = ::showPlayer
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        ReceiverService.sync(this)
    }

    /** Brings up the full-screen player when a device starts mirroring. */
    private fun showPlayer() {
        // From the background, Android only lets apps open a screen if they may
        // draw over other apps (Settings > Apps > Special access).
        val allowed = startedActivities > 0 || Build.VERSION.SDK_INT < 29 ||
            SystemSettings.canDrawOverlays(this)
        if (!allowed) {
            Log.w("MangoApp", "mirroring started in the background but the player can't be opened")
            return
        }
        startActivity(
            Intent(this, MirrorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    companion object {
        lateinit var instance: MangoApp
            private set

        val receiver: AirPlayReceiver get() = instance.receiver
        val settings: Settings get() = instance.settings
    }
}
