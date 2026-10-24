package com.mangoscreencast.tv

import android.content.Context
import android.os.Build
import android.provider.Settings as SystemSettings
import java.util.UUID

enum class LatencyMode {
    /** Show each frame the moment it is decoded. */
    FASTEST,

    /** Show frames as soon as possible, but never two in the same refresh. */
    BALANCED,

    /** Hold one frame back to even out Wi-Fi jitter. */
    SMOOTHEST,
}

/** App preferences. Reads and writes go straight to SharedPreferences. */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("mango", Context.MODE_PRIVATE)

    /** Stable id announced to other devices. */
    val deviceId: String
        get() = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_ID, it).apply()
        }

    /** The name sources show for this TV. Defaults to the TV's own device name. */
    var tvName: String
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: systemName()
        set(value) = prefs.edit().putString(KEY_NAME, value.trim()).apply()

    var latencyMode: LatencyMode
        get() = runCatching { LatencyMode.valueOf(prefs.getString(KEY_LATENCY, null)!!) }
            .getOrDefault(LatencyMode.BALANCED)
        set(value) = prefs.edit().putString(KEY_LATENCY, value.name).apply()

    /** Reconnect to the last source as soon as it shows up. */
    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    var lastSourceId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    var showStats: Boolean
        get() = prefs.getBoolean(KEY_STATS, false)
        set(value) = prefs.edit().putBoolean(KEY_STATS, value).apply()

    /** Offer 120 Hz modes to sources when the TV and decoder support them. */
    var allow120Hz: Boolean
        get() = prefs.getBoolean(KEY_120, true)
        set(value) = prefs.edit().putBoolean(KEY_120, value).apply()

    var airPlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_AIRPLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_AIRPLAY, value).apply()

    var dlnaEnabled: Boolean
        get() = prefs.getBoolean(KEY_DLNA, true)
        set(value) = prefs.edit().putBoolean(KEY_DLNA, value).apply()

    /** Keep listening for casts after leaving the app (runs a foreground service). */
    var receiveInBackground: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND, false)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND, value).apply()

    private fun systemName(): String {
        val name = runCatching {
            SystemSettings.Global.getString(context.contentResolver, SystemSettings.Global.DEVICE_NAME)
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android TV"
    }

    private companion object {
        const val KEY_ID = "device_id"
        const val KEY_NAME = "tv_name"
        const val KEY_LATENCY = "latency_mode"
        const val KEY_AUTO = "auto_connect"
        const val KEY_LAST = "last_source"
        const val KEY_STATS = "show_stats"
        const val KEY_120 = "allow_120hz"
        const val KEY_AIRPLAY = "airplay"
        const val KEY_DLNA = "dlna"
        const val KEY_BACKGROUND = "background"
    }
}
