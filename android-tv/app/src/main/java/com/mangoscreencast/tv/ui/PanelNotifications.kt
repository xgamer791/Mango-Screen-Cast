package com.mangoscreencast.tv.ui

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference

/**
 * The TV's notifications, for the gear's panel, as the Hisense panel listed them:
 * newest first, each with its title and its text, opening what the notification
 * opens. Android shows them to a notification listener only, so
 * this is one; it's allowed once over adb, as the TV's settings have no screen
 * for it: `adb shell cmd notification allow_listener
 * com.mangoscreencast.tv/com.mangoscreencast.tv.ui.PanelNotifications`
 */
class PanelNotifications : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = WeakReference(this)
    }

    override fun onListenerDisconnected() {
        if (connected?.get() === this) connected = null
    }

    class Item(val title: String, val text: String, private val intent: PendingIntent?) {
        /** Opens what the notification opens; false when it opens nothing. */
        fun open(): Boolean = try {
            intent?.send()
            intent != null
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    companion object {
        private var connected: WeakReference<PanelNotifications>? = null

        /** What the panel lists: the notifications the TV shows now, newest first. Nothing until the listener is allowed. */
        fun current(): List<Item> {
            val service = connected?.get() ?: return emptyList()
            val active = try {
                service.activeNotifications ?: return emptyList()
            } catch (_: RuntimeException) {
                return emptyList()
            }
            return items(active.toList())
        }

        /** The ones worth listing (a title or text, not a group's summary), newest first. */
        internal fun items(active: List<StatusBarNotification>): List<Item> =
            active
                .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                .sortedByDescending { it.postTime }
                .mapNotNull { sbn ->
                    val extras = sbn.notification.extras
                    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                    if (title.isEmpty() && text.isEmpty()) null else Item(title, text, sbn.notification.contentIntent)
                }
    }
}
