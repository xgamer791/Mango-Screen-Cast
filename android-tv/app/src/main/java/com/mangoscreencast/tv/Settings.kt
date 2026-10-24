package com.mangoscreencast.tv

import android.content.Context
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
            .getOrDefault(ResolutionSetting.AUTO)
        set(value) = prefs.edit().putString(KEY_RESOLUTION, value.name).apply()

    var latencyMode: LatencyMode
        get() = runCatching { LatencyMode.valueOf(prefs.getString(KEY_LATENCY, null)!!) }
            .getOrDefault(LatencyMode.BALANCED)
        set(value) = prefs.edit().putString(KEY_LATENCY, value.name).apply()

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
        const val DEFAULT_NAME = "Mango Screen Cast"
        const val KEY_ID = "device_id"
        const val KEY_NAME = "tv_name"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_LATENCY = "latency_mode"
        const val KEY_SOUND = "sound"
        const val KEY_STATS = "show_stats"
        const val KEY_BACKGROUND = "background"
        const val KEY_UPDATES = "updates"
        const val KEY_UPDATE_CHECK = "update_check"
        const val KEY_REOPEN = "reopen_after_update"
    }
}
