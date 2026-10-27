package com.mangoscreencast.tv.ui

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference

/**
 * The TV's notifications, for the gear's panel, as the Hisense panel listed them:
 * the system's own notices (Wireless debugging, Developer options) each on a card,
 * newest first, then an All notifications row counting the rest of the ones that
 * matter (at least default importance, not a running service's notice, not one of
 * the TV's own system services'). Each opens what the notification opens.
 * Android shows them to a notification listener only, so this is one; it's allowed
 * once over adb, as the TV's settings have no screen for it:
 * `adb shell cmd notification allow_listener
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

    /** The cards, and the rest that the All notifications row counts and opens out to. */
    class Listed(val cards: List<Item>, val rest: List<Item>)

    companion object {
        private var connected: WeakReference<PanelNotifications>? = null

        /** What the panel lists now. Nothing until the listener is allowed. */
        fun current(): Listed {
            val service = connected?.get() ?: return Listed(emptyList(), emptyList())
            return try {
                val ranking = service.currentRanking
                val one = Ranking()
                list(service.activeNotifications?.toList().orEmpty()) { sbn ->
                    if (ranking.getRanking(sbn.key, one)) one.importance else NotificationManager.IMPORTANCE_DEFAULT
                }
            } catch (_: RuntimeException) {
                Listed(emptyList(), emptyList())
            }
        }

        /** The Hisense panel's choice from `active`, given each one's importance. */
        internal fun list(active: List<StatusBarNotification>, importance: (StatusBarNotification) -> Int): Listed {
            val worth = active
                .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                .filter { importance(it) >= NotificationManager.IMPORTANCE_DEFAULT }
                .filter { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE == 0 }
                .sortedByDescending { it.postTime }
            val (system, others) = worth.partition { it.packageName == "android" }
            // The TV's own system services (uid 1000: Hisense's settings and the like) keep out of the list.
            val rest = others.filter { it.uid != Process.SYSTEM_UID }
            return Listed(system.mapNotNull(::item), rest.mapNotNull(::item))
        }

        private fun item(sbn: StatusBarNotification): Item? {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            return if (title.isEmpty() && text.isEmpty()) null else Item(title, text, sbn.notification.contentIntent)
        }
    }
}
