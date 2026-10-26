package com.mangoscreencast.tv.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import android.util.AttributeSet
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Date
import java.util.Locale

/**
 * The time in the TV's own time zone ("3:24 pm", or "15:24" on a TV set to
 * 24-hour time), moving on every minute and when the TV's time or zone changes.
 */
class ClockView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextView(context, attrs) {
    private val ticks = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = show()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ContextCompat.registerReceiver(context, ticks, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        show()
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(ticks)
        super.onDetachedFromWindow()
    }

    fun show(now: Date = Date()) {
        text = format(now, DateFormat.is24HourFormat(context))
    }

    companion object {
        /** "3:24 pm" (lower case, as on the Roku), or "15:24". */
        fun format(now: Date, twentyFourHour: Boolean, locale: Locale = Locale.getDefault()): String {
            val pattern = if (twentyFourHour) "H:mm" else "h:mm a"
            return java.text.SimpleDateFormat(pattern, locale).format(now).lowercase(locale)
        }
    }
}
