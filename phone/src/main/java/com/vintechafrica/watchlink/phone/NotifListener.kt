package com.vintechafrica.watchlink.phone

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/** Reads every notification on the phone and forwards it to the watch. */
class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: NotifListener? = null

        fun snapshot(): List<JSONObject> {
            val l = instance ?: return emptyList()
            return try {
                l.activeNotifications.mapNotNull { l.toJson(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun dismiss(key: String) {
            try {
                instance?.cancelNotification(key)
                L.i("Dismissed from watch")
            } catch (e: Exception) {
                L.i("Dismiss failed: ${e.message}")
            }
        }
    }

    // Call screens are handled by the call channel, not as notifications.
    private val skip = setOf(
        "com.samsung.android.incallui",
        "com.android.incallui",
        "com.android.systemui",
        "android"
    )

    override fun onListenerConnected() {
        instance = this
        L.i("Notification access active")
    }

    override fun onListenerDisconnected() {
        instance = null
        L.i("Notification access lost")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        toJson(sbn)?.let { LinkService.instance?.send(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        LinkService.instance?.send(JSONObject().put("t", "notif_rm").put("key", sbn.key))
    }

    fun toJson(sbn: StatusBarNotification): JSONObject? {
        if (sbn.packageName == packageName || sbn.packageName in skip) return null
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return null
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        return JSONObject()
            .put("t", "notif")
            .put("key", sbn.key)
            .put("app", appLabel(sbn.packageName))
            .put("title", title.take(80))
            .put("text", text.take(400))
            .put("time", sbn.postTime)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
