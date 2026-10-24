package com.mangoscreencast.tv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mangoscreencast.tv.MangoApp
import com.mangoscreencast.tv.ReceiverService
import com.mangoscreencast.tv.ui.HomeActivity

/** After an update, restart the background receiver, and reopen the app if it updated itself. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ReceiverService.sync(context)
        val settings = MangoApp.settings
        if (!settings.reopenAfterUpdate) return
        settings.reopenAfterUpdate = false
        try {
            // Android may refuse to open a screen from here; then the user opens the app as usual.
            context.startActivity(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
