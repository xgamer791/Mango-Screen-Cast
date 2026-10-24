package com.mangoscreencast.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.mangoscreencast.tv.ui.HomeActivity

/**
 * Keeps the receivers running while the app is in the background, when the
 * user turned that on. Without it the TV only receives while the app is open.
 */
class ReceiverService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.background_notice, MangoApp.settings.tvName))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        MangoApp.receiver.acquire(this)
    }

    override fun onDestroy() {
        MangoApp.receiver.release(this)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "receiver"
        private const val NOTIFICATION_ID = 1

        fun sync(context: Context) {
            val intent = Intent(context, ReceiverService::class.java)
            if (MangoApp.settings.receiveInBackground) {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
