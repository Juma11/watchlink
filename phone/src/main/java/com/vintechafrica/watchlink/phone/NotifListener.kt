package com.vintechafrica.watchlink.phone

import android.app.ActivityOptions
import android.app.Notification
import android.os.Build
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

        private val answerWords = listOf(
            "answer", "accept", "pick up", "jibu", "pokea", "antworten", "annehmen",
            "répondre", "repondre", "accepter", "contestar", "responder", "atender", "rispondi"
        )
        private val endWords = listOf(
            "decline", "reject", "hang up", "end", "dismiss", "kataa", "kata", "ablehnen",
            "auflegen", "refuser", "raccrocher", "rechazar", "colgar", "recusar", "rifiuta"
        )

        private fun matches(title: CharSequence?, words: List<String>): Boolean {
            val t = title?.toString()?.lowercase() ?: return false
            return words.any { t.contains(it) }
        }

        /** Presses the app's own Answer or Decline/Hang up button on its call notification. */
        fun pressCallAction(key: String, answer: Boolean) {
            val l = instance ?: run {
                L.i("No notification access: cannot control internet call")
                return
            }
            val sbn = try { l.activeNotifications.firstOrNull { it.key == key } } catch (e: Exception) { null }
            val actions = sbn?.notification?.actions
            if (actions.isNullOrEmpty()) {
                L.i("Call notification has no buttons")
                return
            }
            val words = if (answer) answerWords else endWords
            val act = actions.firstOrNull { matches(it.title, words) }
                // Fallback for unknown languages: apps put Decline first and Answer last.
                ?: if (answer && actions.size >= 2) actions.last() else if (!answer) actions.first() else null
            if (act == null) {
                L.i("No ${if (answer) "answer" else "decline"} button found")
                return
            }
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    act.actionIntent.send(l, 0, null, null, null, null, opts.toBundle())
                } else {
                    act.actionIntent.send()
                }
                L.i("Pressed '${act.title}' on ${l.appLabel(sbn.packageName)} call")
            } catch (e: Exception) {
                L.i("Button press failed: ${e.message}")
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

    // The phone line is handled by the call listener, not as notifications.
    private val dialers = setOf(
        "com.samsung.android.dialer",
        "com.google.android.dialer",
        "com.android.dialer",
        "com.android.server.telecom"
    )

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
        if (isInternetCall(sbn)) {
            LinkService.instance?.onVoip(toVoip(sbn))
            return
        }
        toJson(sbn)?.let { LinkService.instance?.send(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val v = LinkService.instance?.voip
        if (v != null && v.key == sbn.key) {
            LinkService.instance?.onVoip(null)
            return
        }
        LinkService.instance?.send(JSONObject().put("t", "notif_rm").put("key", sbn.key))
    }

    private fun isInternetCall(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName || sbn.packageName in skip || sbn.packageName in dialers) return false
        return sbn.notification.category == Notification.CATEGORY_CALL
    }

    /** WhatsApp and similar put the caller's name in the title of their call notification. */
    private fun toVoip(sbn: StatusBarNotification): VoipCall {
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val ringing = n.actions?.any { matches(it.title, answerWords) } == true ||
            (n.fullScreenIntent != null && (n.flags and Notification.FLAG_ONGOING_EVENT) == 0) ||
            text.lowercase().contains("incoming")
        return VoipCall(
            key = sbn.key,
            app = appLabel(sbn.packageName),
            name = title.ifEmpty { appLabel(sbn.packageName) },
            sub = text.take(60),
            state = if (ringing) "ringing" else "offhook"
        )
    }

    fun toJson(sbn: StatusBarNotification): JSONObject? {
        if (sbn.packageName == packageName || sbn.packageName in skip) return null
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_CALL) return null
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

    fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
