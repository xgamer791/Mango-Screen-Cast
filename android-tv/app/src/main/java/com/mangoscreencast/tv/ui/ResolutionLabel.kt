package com.mangoscreencast.tv.ui

import android.content.Context
import com.mangoscreencast.tv.R
import com.mangoscreencast.tv.display.ResolutionSetting

/** Settings → Resolution's value. */
object ResolutionLabel {
    /**
     * A pick by its size; Auto with the size it offers now (`autoSize`: "4K",
     * "1440p"…), worked out at the moment, not taken from the server, which
     * is still offering the pick before until it restarts. Just "Auto" when
     * that isn't known.
     */
    fun of(context: Context, setting: ResolutionSetting, autoSize: () -> String?): String = when (setting) {
        ResolutionSetting.AUTO -> autoSize()?.let { context.getString(R.string.resolution_auto_value, it) }
            ?: context.getString(R.string.resolution_auto)
        ResolutionSetting.UHD_2160 -> "4K (3840 × 2160)"
        ResolutionSetting.QHD_1440 -> "2K / 1440p (2560 × 1440)"
        ResolutionSetting.FHD_1080 -> "1080p (1920 × 1080)"
    }
}
