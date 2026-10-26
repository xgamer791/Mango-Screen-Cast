package com.mangoscreencast.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.mangoscreencast.tv.BuildConfig
import com.mangoscreencast.tv.LatencyMode
import com.mangoscreencast.tv.MangoApp
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.ReceiverService
import com.mangoscreencast.tv.UpdateMode
import com.mangoscreencast.tv.WidePictures
import com.mangoscreencast.tv.display.DisplayCapabilities
import com.mangoscreencast.tv.display.FrameRateSetting
import com.mangoscreencast.tv.display.ResolutionSetting
import com.mangoscreencast.tv.update.AppUpdater

/** A short list of rows; OK on a row changes it. */
class SettingsActivity : Activity(), AppUpdater.Listener {
    private lateinit var list: LinearLayout
    private val settings get() = MangoApp.settings
    private val receiver get() = MangoApp.receiver
    private val updater get() = MangoApp.instance.updater
    private var focusedRow = 0
    /** The "Check for updates now" row's second line, which follows the updater. */
    private var updateHint: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Drawn at 1440p, whatever size the TV draws its screens at.
        Canvas1440Layout.setContentView(this, R.layout.activity_settings)
        list = findViewById(R.id.settings_list)
        findViewById<TextView>(R.id.about).text = getString(R.string.about, BuildConfig.VERSION_NAME)
    }

    override fun onStart() {
        super.onStart()
        receiver.acquire(this)
        build()
        updater.addListener(this)
    }

    // In front: the gear closes it (GearKeyService).
    override fun onResume() {
        super.onResume()
        FrontSettings.shown(this)
    }

    override fun onPause() {
        FrontSettings.hidden(this)
        super.onPause()
    }

    override fun onStop() {
        updater.removeListener(this)
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
        row(R.string.setting_frame_rate, getString(R.string.fps_value, settings.frameRate.fps), frameRateHint()) {
            val values = FrameRateSetting.entries
            settings.frameRate = values[(values.indexOf(settings.frameRate) + 1) % values.size]
            receiver.restart()
        }
        row(R.string.setting_pacing, pacingLabel(), pacingHint()) {
            val values = listOf(LatencyMode.BALANCED, LatencyMode.FASTEST, LatencyMode.SMOOTHEST)
            settings.latencyMode = values[(values.indexOf(settings.latencyMode) + 1) % values.size]
            receiver.video.latencyMode = settings.latencyMode
        }
        row(R.string.setting_fill, getString(wideLabel(settings.widePictures)), R.string.setting_fill_hint) {
            val values = WidePictures.entries
            settings.widePictures = values[(values.indexOf(settings.widePictures) + 1) % values.size]
        }
        row(R.string.setting_sound, onOff(settings.soundEnabled), R.string.setting_sound_hint) {
            settings.soundEnabled = !settings.soundEnabled
            receiver.audio.enabled = settings.soundEnabled
        }
        row(R.string.setting_stats, onOff(settings.showStats), R.string.setting_stats_hint) {
            settings.showStats = !settings.showStats
        }
        row(R.string.setting_background, onOff(settings.receiveInBackground), backgroundHint()) {
            settings.receiveInBackground = !settings.receiveInBackground
            ReceiverService.sync(this)
            if (settings.receiveInBackground) askForOverlayPermission()
        }
        row(R.string.setting_updates, updateModeLabel(), R.string.setting_updates_hint) {
            val values = listOf(UpdateMode.AUTOMATIC, UpdateMode.ASK, UpdateMode.OFF)
            settings.updateMode = values[(values.indexOf(settings.updateMode) + 1) % values.size]
        }
        updateHint = row(R.string.setting_check_updates, BuildConfig.VERSION_NAME, updateHintText(updater.state)) {
            when (updater.state) {
                is AppUpdater.State.Available, is AppUpdater.State.NeedsPermission -> updater.install()
                is AppUpdater.State.Checking, is AppUpdater.State.Downloading, is AppUpdater.State.Installing -> Unit
                else -> updater.checkNow()
            }
        }.findViewById(R.id.setting_hint)
        row(R.string.setting_diagnostics, getString(R.string.view), R.string.setting_diagnostics_hint) {
            showDiagnostics()
        }
        // Last, so the gear can open this screen without losing the TV's own settings.
        row(R.string.setting_google_tv, getString(R.string.setting_google_tv_open), R.string.setting_google_tv_hint) {
            openGoogleTvSettings()
        }
        list.getChildAt(focusedRow.coerceIn(0, list.childCount - 1))?.requestFocus()
    }

    private fun openGoogleTvSettings() {
        try {
            startActivity(GoogleTvSettings.intent(this))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.setting_google_tv_missing, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.setting_google_tv_missing, Toast.LENGTH_LONG).show()
        }
    }

    private fun showDiagnostics() {
        val text = TextView(this).apply {
            text = receiver.diagnostics()
            setTextIsSelectable(true)
            typeface = ResourcesCompat.getFont(this@SettingsActivity, R.font.geist_mono_medium)
            textSize = 11.2f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            isFocusable = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_diagnostics)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onUpdateState(state: AppUpdater.State) {
        updateHint?.apply {
            text = updateHintText(state)
            visibility = View.VISIBLE
        }
    }

    private fun updateHintText(state: AppUpdater.State): String = when (state) {
        AppUpdater.State.Idle -> getString(R.string.setting_check_updates_hint)
        AppUpdater.State.Checking -> getString(R.string.checking_updates)
        AppUpdater.State.UpToDate -> getString(R.string.update_up_to_date)
        is AppUpdater.State.Available ->
            getString(R.string.update_press_ok, getString(R.string.update_available, state.release.versionName) + ".")
        is AppUpdater.State.NeedsPermission -> getString(R.string.update_needs_permission)
        is AppUpdater.State.Downloading -> getString(R.string.update_downloading, state.percent)
        is AppUpdater.State.Installing -> getString(R.string.update_installing)
        is AppUpdater.State.Failed -> getString(R.string.update_press_ok_retry, state.message)
    }

    private fun row(title: Int, value: String, hint: Int, onClick: () -> Unit) =
        row(title, value, getString(hint), onClick)

    private fun row(title: Int, value: String, hint: String, onClick: () -> Unit): View {
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
        return view
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

    /**
     * From the background, the player can only open itself with "display over
     * other apps". Google TV has no screen for it, so also give the adb command.
     */
    private fun askForOverlayPermission() {
        if (MangoApp.instance.canOpenFromBackground()) return
        val intent = Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.overlay_title)
            .setMessage(getString(R.string.overlay_message, overlayCommand()))
        if (intent.resolveActivity(packageManager) != null) {
            dialog.setPositiveButton(R.string.overlay_open) { _, _ ->
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
            dialog.setNegativeButton(R.string.overlay_later, null)
        } else {
            dialog.setPositiveButton(android.R.string.ok, null)
        }
        dialog.show()
    }

    private fun backgroundHint(): String =
        if (settings.receiveInBackground && !MangoApp.instance.canOpenFromBackground()) {
            getString(R.string.setting_background_needs_overlay, overlayCommand())
        } else {
            getString(R.string.setting_background_hint)
        }

    private fun overlayCommand() = "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"

    /** What Auto offers is worked out now: the server still offers the pick before until it restarts. */
    private fun resolutionLabel(): String = ResolutionLabel.of(this, settings.resolution) {
        DisplayCapabilities.profile(this, ResolutionSetting.AUTO, settings.frameRate).sizeName
    }

    /** What this TV will actually offer at the chosen rate, worked out now rather than after the restart. */
    private fun frameRateHint(): String {
        val note = if (settings.frameRate.fps > 60) {
            DisplayCapabilities.profile(this, settings.resolution, settings.frameRate).note
        } else null
        return note ?: getString(R.string.setting_frame_rate_hint)
    }

    private fun wideLabel(mode: WidePictures): Int = when (mode) {
        WidePictures.FIT -> R.string.fit_value
        WidePictures.FILL -> R.string.fill_value
        WidePictures.STRETCH -> R.string.stretch_value
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

    private fun updateModeLabel() = getString(
        when (settings.updateMode) {
            UpdateMode.AUTOMATIC -> R.string.updates_automatic
            UpdateMode.ASK -> R.string.updates_ask
            UpdateMode.OFF -> R.string.off
        }
    )
}
