package com.mangoscreencast.tv.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
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
    private lateinit var statusView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var detailView: TextView
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
        statusView = findViewById(R.id.status)
        subtitleView = findViewById(R.id.subtitle)
        detailView = findViewById(R.id.status_detail)
        updateStatus = findViewById(R.id.update_status)
        updateButton = findViewById(R.id.update_button)
        updateButton.setOnClickListener {
            when (updater.state) {
                is AppUpdater.State.Failed -> updater.checkNow()
                else -> updater.install()
            }
        }
        findViewById<Button>(R.id.settings_button).apply {
            setOnClickListener { openSettings() }
            requestFocus()
        }
        AppTiles.fill(findViewById(R.id.apps_row), ::openApp)
        ReceiverService.sync(this)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    /**
     * The Hisense gear doesn't reach apps as a key: the system opens the
     * MAIN + LEANBACK_SETTINGS handler, [SettingsActivity] (the manifest says
     * how). A remote or TV that delivers KEYCODE_SETTINGS opens the same
     * screen from here.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SETTINGS && event.repeatCount == 0) {
            openSettings()
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
        val name = status.tvName.ifBlank { MangoApp.settings.tvName }
        val client = status.clientName ?: getString(R.string.a_device)

        // Headline, the big line under it, and the small line under that (blank unless something needs saying).
        val (title, subtitle, detail) = when (status.state) {
            State.STOPPED, State.STARTING -> Triple(getString(R.string.status_starting), "", "")
            State.READY -> Triple(getString(R.string.status_ready), getString(R.string.ready_subtitle), "")
            State.CONNECTED -> Triple(
                getString(R.string.status_connected, client), getString(R.string.status_connected_detail), "",
            )
            State.MIRRORING -> Triple(getString(R.string.status_mirroring, client), "", "")
            // The reason is a sentence or two: it goes in the small line, where it fits.
            State.FAILED -> Triple(getString(R.string.status_failed), "", status.message ?: "")
        }
        statusView.text = title
        subtitleView.text = subtitle
        subtitleView.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        detailView.text = status.message?.takeIf { status.state != State.FAILED } ?: detail
        detailView.visibility = if (detailView.text.isNullOrBlank()) View.GONE else View.VISIBLE

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
