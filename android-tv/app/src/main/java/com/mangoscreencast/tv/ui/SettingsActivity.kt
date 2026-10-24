package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.mangoscreencast.tv.BuildConfig
import com.mangoscreencast.tv.LatencyMode
import com.mangoscreencast.tv.MangoApp
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.ReceiverService
import com.mangoscreencast.tv.display.ResolutionSetting

/** A short list of rows; OK on a row changes it. */
class SettingsActivity : Activity() {
    private lateinit var list: LinearLayout
    private val settings get() = MangoApp.settings
    private val receiver get() = MangoApp.receiver
    private var focusedRow = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        list = findViewById(R.id.settings_list)
        findViewById<TextView>(R.id.about).text = getString(R.string.about, BuildConfig.VERSION_NAME)
    }

    override fun onStart() {
        super.onStart()
        receiver.acquire(this)
        build()
    }

    override fun onStop() {
        receiver.release(this)
        super.onStop()
    }

    private fun build() {
        list.removeAllViews()
        row(R.string.setting_name, settings.tvName, R.string.setting_name_hint) { editName() }
        row(R.string.setting_resolution, resolutionLabel(), R.string.setting_resolution_hint) {
            val values = ResolutionSetting.entries
            settings.resolution = values[(values.indexOf(settings.resolution) + 1) % values.size]
            receiver.restart()
        }
        row(R.string.setting_pacing, pacingLabel(), pacingHint()) {
            val values = listOf(LatencyMode.BALANCED, LatencyMode.FASTEST, LatencyMode.SMOOTHEST)
            settings.latencyMode = values[(values.indexOf(settings.latencyMode) + 1) % values.size]
            receiver.video.latencyMode = settings.latencyMode
        }
        row(R.string.setting_sound, onOff(settings.soundEnabled), R.string.setting_sound_hint) {
            settings.soundEnabled = !settings.soundEnabled
            receiver.audio.enabled = settings.soundEnabled
        }
        row(R.string.setting_stats, onOff(settings.showStats), R.string.setting_stats_hint) {
            settings.showStats = !settings.showStats
        }
        row(R.string.setting_background, onOff(settings.receiveInBackground), R.string.setting_background_hint) {
            settings.receiveInBackground = !settings.receiveInBackground
            ReceiverService.sync(this)
            if (settings.receiveInBackground) askForOverlayPermission()
        }
        list.getChildAt(focusedRow.coerceIn(0, list.childCount - 1))?.requestFocus()
    }

    private fun row(title: Int, value: String, hint: Int, onClick: () -> Unit) =
        row(title, value, getString(hint), onClick)

    private fun row(title: Int, value: String, hint: String, onClick: () -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_setting, list, false)
        view.findViewById<TextView>(R.id.setting_title).setText(title)
        view.findViewById<TextView>(R.id.setting_value).text = value
        view.findViewById<TextView>(R.id.setting_hint).apply {
            text = hint
            visibility = if (hint.isBlank()) View.GONE else View.VISIBLE
        }
        val index = list.childCount
        view.setOnClickListener {
            focusedRow = index
            onClick()
            build()
        }
        list.addView(view)
    }

    private fun editName() {
        val input = EditText(this).apply {
            setText(settings.tvName)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_name)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != settings.tvName) {
                    settings.tvName = name
                    receiver.restart()
                    build()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** From the background, the player can only open itself with "display over other apps". */
    private fun askForOverlayPermission() {
        if (Build.VERSION.SDK_INT < 29 || SystemSettings.canDrawOverlays(this)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_title)
            .setMessage(R.string.overlay_message)
            .setPositiveButton(R.string.overlay_open) { _, _ ->
                try {
                    startActivity(
                        Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    // Some TV builds hide this screen; the setting is under Apps > Special app access.
                }
            }
            .setNegativeButton(R.string.overlay_later, null)
            .show()
    }

    private fun resolutionLabel(): String {
        val auto = receiver.status.profile?.takeIf { settings.resolution == ResolutionSetting.AUTO }
        return when (settings.resolution) {
            ResolutionSetting.AUTO -> if (auto != null) getString(R.string.resolution_auto_value, auto.sizeName)
                else getString(R.string.resolution_auto)
            ResolutionSetting.UHD_2160 -> "4K (3840 × 2160)"
            ResolutionSetting.QHD_1440 -> "1440p (2560 × 1440)"
            ResolutionSetting.FHD_1080 -> "1080p (1920 × 1080)"
        }
    }

    private fun pacingLabel() = getString(
        when (settings.latencyMode) {
            LatencyMode.BALANCED -> R.string.pacing_balanced
            LatencyMode.FASTEST -> R.string.pacing_fastest
            LatencyMode.SMOOTHEST -> R.string.pacing_smoothest
        }
    )

    private fun pacingHint() = getString(
        when (settings.latencyMode) {
            LatencyMode.BALANCED -> R.string.pacing_balanced_hint
            LatencyMode.FASTEST -> R.string.pacing_fastest_hint
            LatencyMode.SMOOTHEST -> R.string.pacing_smoothest_hint
        }
    )

    private fun onOff(value: Boolean) = getString(if (value) R.string.on else R.string.off)
}
