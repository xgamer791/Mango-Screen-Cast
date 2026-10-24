package com.mangoscreencast.tv

import android.content.Context
import android.os.Build
import android.provider.Settings as SystemSettings
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

/** App preferences. Reads and writes go straight to SharedPreferences. */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("mango", Context.MODE_PRIVATE)

    /** Stable id; the AirPlay device id is derived from it. */
    val deviceId: String
        get() = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_ID, it).apply()
        }

    /** The name Macs and iPhones show for this TV. Defaults to the TV's own device name. */
    var tvName: String
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: systemName()
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

    private fun systemName(): String {
        val name = runCatching {
            SystemSettings.Global.getString(context.contentResolver, SystemSettings.Global.DEVICE_NAME)
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android TV"
    }

    private companion object {
        const val KEY_ID = "device_id"
        const val KEY_NAME = "tv_name"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_LATENCY = "latency_mode"
        const val KEY_SOUND = "sound"
        const val KEY_STATS = "show_stats"
        const val KEY_BACKGROUND = "background"
    }
}
