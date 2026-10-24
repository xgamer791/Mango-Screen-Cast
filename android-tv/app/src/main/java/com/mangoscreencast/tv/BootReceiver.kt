package com.mangoscreencast.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the background receiver after the TV boots, when "Stay visible" is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReceiverService.sync(context)
    }
}
