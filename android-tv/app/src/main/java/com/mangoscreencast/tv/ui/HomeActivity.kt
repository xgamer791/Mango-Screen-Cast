package com.mangoscreencast.tv.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.mangoscreencast.tv.MangoApp
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.airplay.AirPlayReceiver
import com.mangoscreencast.tv.airplay.AirPlayReceiver.State

/** The "ready" screen: shows the name to pick on a Mac or iPhone, and what the TV can take. */
class HomeActivity : Activity(), AirPlayReceiver.Listener {
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var macSteps: TextView
    private lateinit var iosSteps: TextView
    private lateinit var capabilityView: TextView
    private lateinit var statusDot: View

    private val receiver get() = MangoApp.receiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        statusView = findViewById(R.id.status)
        detailView = findViewById(R.id.status_detail)
        macSteps = findViewById(R.id.mac_steps)
        iosSteps = findViewById(R.id.ios_steps)
        capabilityView = findViewById(R.id.capability)
        statusDot = findViewById(R.id.status_dot)
        findViewById<Button>(R.id.settings_button).apply {
            setOnClickListener { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java)) }
            requestFocus()
        }
    }

    override fun onStart() {
        super.onStart()
        receiver.acquire(this)
        receiver.addListener(this)
    }

    override fun onStop() {
        receiver.removeListener(this)
        receiver.release(this)
        super.onStop()
    }

    override fun onStatus(status: AirPlayReceiver.Status) {
        val name = status.tvName.ifBlank { MangoApp.settings.tvName }
        macSteps.text = getString(R.string.mac_steps, name)
        iosSteps.text = getString(R.string.ios_steps, name)

        val (title, detail, color) = when (status.state) {
            State.STOPPED, State.STARTING ->
                Triple(getString(R.string.status_starting), "", R.color.status_waiting)
            State.READY ->
                Triple(getString(R.string.status_ready), getString(R.string.status_ready_detail, name), R.color.status_ready)
            State.CONNECTED ->
                Triple(getString(R.string.status_connected, status.clientName ?: getString(R.string.a_device)),
                    getString(R.string.status_connected_detail), R.color.status_ready)
            State.MIRRORING ->
                Triple(getString(R.string.status_mirroring, status.clientName ?: getString(R.string.a_device)),
                    "", R.color.status_ready)
            State.FAILED ->
                Triple(getString(R.string.status_failed), status.message ?: "", R.color.status_error)
        }
        statusView.text = title
        detailView.text = status.message?.takeIf { status.state != State.FAILED } ?: detail
        detailView.visibility = if (detailView.text.isNullOrBlank()) View.GONE else View.VISIBLE
        statusDot.background.mutate().setTint(getColor(color))

        val profile = status.profile
        capabilityView.text = if (profile == null) "" else getString(
            R.string.capability, profile.sizeName, profile.width, profile.height, profile.maxFps, profile.codecs,
            profile.panel.width, profile.panel.height,
        )
    }
}
