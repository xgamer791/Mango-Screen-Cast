package com.mangoscreencast.tv

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.util.Log
import com.mangoscreencast.tv.airplay.AirPlayReceiver
import com.mangoscreencast.tv.media.SoundWake
import com.mangoscreencast.tv.ui.FocusSounds
import com.mangoscreencast.tv.ui.MirrorActivity
import com.mangoscreencast.tv.update.AppUpdater

class MangoApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var receiver: AirPlayReceiver
        private set
    lateinit var updater: AppUpdater
        private set

    private var startedActivities = 0

    /** Screens up that click as the remote moves, and what keeps their clicks prompt. */
    private var clickingScreens = 0
    private val soundWake = SoundWake()

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        receiver = AirPlayReceiver(this, settings)
        receiver.onMirroringStarted = ::showPlayer
        updater = AppUpdater(this, settings, receiver::log)
        // Install on our own only while the app is on screen and nothing is
        // using the TV, so an update never cuts off mirroring.
        updater.isIdle = {
            startedActivities > 0 && receiver.status.state !in setOf(
                AirPlayReceiver.State.CONNECTED, AirPlayReceiver.State.MIRRORING,
            )
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                // The player plays the sender's own sound; every other screen clicks as the remote moves.
                if (activity !is MirrorActivity && clickingScreens++ == 0) soundWake.start()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (activity !is MirrorActivity && --clickingScreens == 0) soundWake.stop()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
                FocusSounds.install(activity.window)
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Brings up the full-screen player when a device starts mirroring. */
    private fun showPlayer() {
        if (startedActivities == 0 && !canOpenFromBackground()) {
            // Android drops this start silently; say why in Diagnostics.
            receiver.log("mirroring started while the app was closed, but Android only lets the player " +
                "open by itself with \"Display over other apps\" allowed")
        }
        try {
            startActivity(
                Intent(this, MirrorActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Exception) {
            Log.w("MangoApp", "couldn't open the player", e)
        }
    }

    /** From the background, Android only lets an app open a screen if it may draw over other apps. */
    fun canOpenFromBackground(): Boolean = Build.VERSION.SDK_INT < 29 || SystemSettings.canDrawOverlays(this)

    companion object {
        lateinit var instance: MangoApp
            private set

        val receiver: AirPlayReceiver get() = instance.receiver
        val settings: Settings get() = instance.settings
    }
}
