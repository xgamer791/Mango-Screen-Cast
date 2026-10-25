package com.mangoscreencast.tv

import android.content.Context
import com.mangoscreencast.tv.display.FrameRateSetting
import com.mangoscreencast.tv.display.ResolutionSetting
import java.util.UUID

enum class LatencyMode {
    /** Show each frame the moment it is decoded. */
    FASTEST,

    /** Show frames as soon as possible, but never two in the same refresh. */
    BALANCED,

    /** Hold one frame back to even out Wi-Fi jitter. */
    SMOOTHEST,
}

enum class UpdateMode {
    /** Install new versions on its own while nothing is mirroring. */
    AUTOMATIC,

    /** Show "Update available" and wait for the user. */
    ASK,

    OFF,
}

/** App preferences. Reads and writes go straight to SharedPreferences. */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("mango", Context.MODE_PRIVATE)

    init {
        // 120 fps took over from 60 as the default. A 60 saved before then was
        // most likely passed on the way to another choice, so it goes, once.
        if (!prefs.getBoolean(KEY_FRAME_RATE_120_DEFAULT, false)) {
            val editor = prefs.edit().putBoolean(KEY_FRAME_RATE_120_DEFAULT, true)
            if (prefs.getString(KEY_FRAME_RATE, null) == FrameRateSetting.FPS_60.name) editor.remove(KEY_FRAME_RATE)
            editor.apply()
        }
        // 1080p is the default resolution, with an iPhone in landscape filling
        // the screen: every TV starts there once, whatever it had; what's
        // picked after that is kept.
        if (!prefs.getBoolean(KEY_RESOLUTION_1080_DEFAULT, false)) {
            prefs.edit().putBoolean(KEY_RESOLUTION_1080_DEFAULT, true).remove(KEY_RESOLUTION).remove(KEY_FILL_WIDE).apply()
        }
    }

    /** Stable id; the AirPlay device id is derived from it. */
    val deviceId: String
        get() = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_ID, it).apply()
        }

    /** The name Macs and iPhones show for this TV. */
    var tvName: String
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
        set(value) = prefs.edit().putString(KEY_NAME, value.trim()).apply()

    var resolution: ResolutionSetting
        get() = runCatching { ResolutionSetting.valueOf(prefs.getString(KEY_RESOLUTION, null)!!) }
            .getOrDefault(DEFAULT_RESOLUTION)
        set(value) = prefs.edit().putString(KEY_RESOLUTION, value.name).apply()

    var frameRate: FrameRateSetting
        get() = runCatching { FrameRateSetting.valueOf(prefs.getString(KEY_FRAME_RATE, null)!!) }
            .getOrDefault(FrameRateSetting.FPS_120)
        set(value) = prefs.edit().putString(KEY_FRAME_RATE, value.name).apply()

    var latencyMode: LatencyMode
        get() = runCatching { LatencyMode.valueOf(prefs.getString(KEY_LATENCY, null)!!) }
            .getOrDefault(LatencyMode.BALANCED)
        set(value) = prefs.edit().putString(KEY_LATENCY, value.name).apply()

    /**
     * Zoom pictures wider than the TV (an iPhone in landscape) to cover it,
     * cutting off the sides. On unless turned off: black bars read as a fault.
     */
    var fillWidePictures: Boolean
        get() = prefs.getBoolean(KEY_FILL_WIDE, true)
        set(value) = prefs.edit().putBoolean(KEY_FILL_WIDE, value).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    var showStats: Boolean
        get() = prefs.getBoolean(KEY_STATS, false)
        set(value) = prefs.edit().putBoolean(KEY_STATS, value).apply()

    /** Keep the TV visible to Apple devices after leaving the app (runs a foreground service). */
    var receiveInBackground: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND, false)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND, value).apply()

    var updateMode: UpdateMode
        get() = runCatching { UpdateMode.valueOf(prefs.getString(KEY_UPDATES, null)!!) }
            .getOrDefault(UpdateMode.AUTOMATIC)
        set(value) = prefs.edit().putString(KEY_UPDATES, value.name).apply()

    var lastUpdateCheckMs: Long
        get() = prefs.getLong(KEY_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_UPDATE_CHECK, value).apply()

    /** Set just before the app installs its own update, so it comes back up afterwards. */
    var reopenAfterUpdate: Boolean
        get() = prefs.getBoolean(KEY_REOPEN, false)
        set(value) {
            // Written synchronously: the installer stops the process right after.
            prefs.edit().putBoolean(KEY_REOPEN, value).commit()
        }

    private companion object {
        const val DEFAULT_NAME = "Mango Screen Cast For macOS/iOS"
        const val KEY_ID = "device_id"
        const val KEY_NAME = "tv_name"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_RESOLUTION_1080_DEFAULT = "resolution_1080_default"
        val DEFAULT_RESOLUTION = ResolutionSetting.FHD_1080
        const val KEY_FRAME_RATE = "frame_rate"
        const val KEY_FRAME_RATE_120_DEFAULT = "frame_rate_120_default"
        const val KEY_LATENCY = "latency_mode"
        const val KEY_SOUND = "sound"
        const val KEY_FILL_WIDE = "fill_wide"
        const val KEY_STATS = "show_stats"
        const val KEY_BACKGROUND = "background"
        const val KEY_UPDATES = "updates"
        const val KEY_UPDATE_CHECK = "update_check"
        const val KEY_REOPEN = "reopen_after_update"
    }
}
