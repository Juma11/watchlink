package com.vintechafrica.watchlink.phone

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager

/** Runs call commands sent from the watch. Needs ANSWER_PHONE_CALLS and CALL_PHONE. */
object CallControl {

    @Volatile private var mutedRing = false
    @Volatile private var dialedNum = ""
    @Volatile private var dialedAt = 0L

    /** Number dialed from the watch in the last 30 seconds, so the call screen can name it. */
    fun lastDialed(): String =
        if (System.currentTimeMillis() - dialedAt < 30_000) dialedNum else ""

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun run(c: Context, action: String, num: String) {
        val tm = c.getSystemService(TelecomManager::class.java)
        // Internet call ringing or active and no phone call: use the app's own buttons.
        val v = LinkService.instance?.activeVoip()
        if (v != null && action in setOf("answer", "reject", "hangup", "silence")) {
            when (action) {
                "answer" -> NotifListener.pressCallAction(v.key, answer = true)
                "reject", "hangup" -> NotifListener.pressCallAction(v.key, answer = false)
                "silence" -> silence(c, tm)
            }
            return
        }
        try {
            when (action) {
                "answer" -> {
                    tm.acceptRingingCall()
                    L.i("Answered from watch")
                }
                "reject", "hangup" -> {
                    val ok = tm.endCall()
                    L.i("End call from watch: $ok")
                }
                "silence" -> silence(c, tm)
                "dial" -> {
                    if (num.isBlank()) return
                    dialedNum = num
                    dialedAt = System.currentTimeMillis()
                    tm.placeCall(Uri.fromParts("tel", num, null), Bundle())
                    L.i("Dialing $num from watch")
                }
                else -> L.i("Unknown command: $action")
            }
        } catch (e: SecurityException) {
            L.i("Not allowed: $action (${e.message}). Grant phone permissions.")
        } catch (e: Exception) {
            L.i("Failed $action: ${e.message}")
        }
    }

    private fun silence(c: Context, tm: TelecomManager) {
        try {
            tm.silenceRinger()
            L.i("Ringer silenced")
            return
        } catch (e: SecurityException) {
            L.i("silenceRinger blocked, muting ring volume instead")
        }
        try {
            c.getSystemService(AudioManager::class.java)
                .adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            mutedRing = true
            L.i("Ring volume muted")
        } catch (e: Exception) {
            L.i("Mute failed: ${e.message}")
        }
    }

    /** Undo the ring mute once the call stops ringing. */
    fun restoreRinger(c: Context) {
        if (!mutedRing) return
        try {
            c.getSystemService(AudioManager::class.java)
                .adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            L.i("Ring volume restored")
        } catch (e: Exception) {
            L.i("Unmute failed: ${e.message}")
        }
        mutedRing = false
    }
}
