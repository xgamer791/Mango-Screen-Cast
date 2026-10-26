package com.mangoscreencast.tv.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mangoscreencast.tv.MangoApp
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.ReceiverService
import com.mangoscreencast.tv.airplay.AirPlayReceiver
import com.mangoscreencast.tv.airplay.AirPlayReceiver.State
import com.mangoscreencast.tv.apps.AppLauncher
import com.mangoscreencast.tv.apps.StreamingApp
import com.mangoscreencast.tv.update.AppUpdater

/** The "Ready to Cast" screen: the name to pick on a Mac or iPhone, the three steps, and the streaming apps. */
class HomeActivity : Activity(), AirPlayReceiver.Listener, AppUpdater.Listener {
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button

    private val receiver get() = MangoApp.receiver
    private val updater get() = MangoApp.instance.updater
    private val launcher by lazy { AppLauncher(packageManager, AppLauncher.isFireTv(this)) }
    private val handler = Handler(Looper.getMainLooper())

    /** A TV left on this screen still picks up a new release within minutes. */
    private val recheckUpdates = object : Runnable {
        override fun run() {
            updater.checkIfDue()
            handler.postDelayed(this, AppUpdater.RECHECK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Drawn at 1440p, whatever size the TV draws its screens at.
        Canvas1440Layout.setContentView(this, R.layout.activity_home)
        updateStatus = findViewById(R.id.update_status)
        updateButton = findViewById(R.id.update_button)
        updateButton.setOnClickListener {
            when (updater.state) {
                is AppUpdater.State.Failed -> updater.checkNow()
                else -> updater.install()
            }
        }
        val apps = findViewById<LinearLayout>(R.id.apps_row)
        AppTiles.fill(apps, ::openApp)
        // The first app has the focus: the gear opens the menu, so there's no Settings button.
        apps.getChildAt(0)?.requestFocus()
        ReceiverService.sync(this)
    }

    /**
     * The remote's gear opens the menu from anywhere (GearKeyService). Without
     * that service, a remote that sends KEYCODE_SETTINGS opens it from here.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SETTINGS && event.repeatCount == 0) {
            SettingsMenuActivity.open(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // As the TV's Home app there is nowhere to go back to: Back must not close this screen.
    override fun onBackPressed() {}

    /** Starts the app; one the TV doesn't have opens its app store page, and says so. */
    private fun openApp(app: StreamingApp) {
        val entry = launcher.entry(app)
        val note = when {
            !launcher.open(this, entry) -> getString(R.string.app_open_failed, app.name)
            entry is AppLauncher.Entry.Missing -> getString(R.string.app_not_installed, app.name)
            else -> return
        }
        Toast.makeText(this, note, Toast.LENGTH_LONG).show()
    }

    override fun onStart() {
        super.onStart()
        receiver.acquire(this)
        receiver.addListener(this)
        updater.addListener(this)
        updater.checkIfDue()
        handler.postDelayed(recheckUpdates, AppUpdater.RECHECK_INTERVAL_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(recheckUpdates)
        updater.removeListener(this)
        receiver.removeListener(this)
        receiver.release(this)
        super.onStop()
    }

    override fun onStatus(status: AirPlayReceiver.Status) {
        // Something that waited for the TV to be free can go ahead now.
        if (status.state == State.READY) updater.checkIfDue()
    }

    override fun onUpdateState(state: AppUpdater.State) {
        val (text, button) = when (state) {
            AppUpdater.State.Idle, AppUpdater.State.Checking ->
                getString(R.string.version_label, com.mangoscreencast.tv.BuildConfig.VERSION_NAME) to null
            AppUpdater.State.UpToDate ->
                getString(R.string.version_up_to_date, com.mangoscreencast.tv.BuildConfig.VERSION_NAME) to null
            is AppUpdater.State.Available -> getString(R.string.update_available, state.release.versionName) to
                getString(R.string.update_now)
            is AppUpdater.State.NeedsPermission -> getString(R.string.update_needs_permission) to
                getString(R.string.update_allow)
            is AppUpdater.State.Downloading -> getString(R.string.update_downloading, state.percent) to null
            is AppUpdater.State.Installing -> getString(R.string.update_installing) to null
            is AppUpdater.State.Failed -> state.message to getString(R.string.update_retry)
        }
        updateStatus.text = text
        updateButton.visibility = if (button == null) View.GONE else View.VISIBLE
        button?.let { updateButton.text = it }
    }
}
