package com.vintechafrica.watchlink.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import org.json.JSONObject

/**
 * Follows whatever is playing on the phone (Spotify, YouTube Music, Boomplay...)
 * and controls it. Needs notification access, which WatchLink already has.
 */
object Media {
    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var lastSent = ""

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onSessionDestroyed() {
            controller = null
            refresh()
        }
    }

    /** Called once notification access is live. */
    fun start(c: Context) {
        main.post {
            if (msm != null) return@post
            ctx = c.applicationContext
            val m = c.getSystemService(MediaSessionManager::class.java)
            val comp = ComponentName(c, NotifListener::class.java)
            try {
                m.addOnActiveSessionsChangedListener(sessionsListener, comp)
                msm = m
                pick(m.getActiveSessions(comp))
                L.i("Media control ready")
            } catch (e: SecurityException) {
                L.i("Media control needs notification access")
            }
        }
    }

    fun stop() {
        main.post {
            try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
            controller?.unregisterCallback(callback)
            controller = null
            msm = null
        }
    }

    private fun refresh() {
        val c = ctx ?: return
        val m = msm ?: return
        try {
            pick(m.getActiveSessions(ComponentName(c, NotifListener::class.java)))
        } catch (_: Exception) {}
    }

    /** Prefer the session that is playing; otherwise the most recent one. */
    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        push()
    }

    /** Current state as a message for the watch. */
    fun state(): JSONObject {
        val c = controller
        val md = c?.metadata
        val st = c?.playbackState?.state ?: PlaybackState.STATE_NONE
        val app = c?.packageName?.let { pkg -> appLabel(pkg) } ?: ""
        val am = ctx?.getSystemService(AudioManager::class.java)
        return JSONObject()
            .put("t", "media")
            .put("has", c != null)
            .put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().take(80))
            .put("artist", (md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty().take(60))
            .put("app", app)
            .put("playing", st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING)
            .put("vol", am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0)
            .put("volMax", am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15)
    }

    private fun push() {
        val o = state()
        val s = o.toString()
        if (s == lastSent) return
        lastSent = s
        LinkService.instance?.send(o)
    }

    /** Resend on reconnect, even if nothing changed. */
    fun resend() {
        lastSent = ""
        main.post { push() }
    }

    fun command(a: String) {
        main.post {
            val c = ctx ?: return@post
            val t = controller?.transportControls
            val am = c.getSystemService(AudioManager::class.java)
            when (a) {
                "toggle" -> {
                    val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
                    if (t != null) { if (playing) t.pause() else t.play() }
                    else key(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                }
                "next" -> t?.skipToNext() ?: key(am, KeyEvent.KEYCODE_MEDIA_NEXT)
                "prev" -> t?.skipToPrevious() ?: key(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "vol_up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                "vol_down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            }
            L.i("Music: $a")
            main.postDelayed({ push() }, 300)
        }
    }

    /**
     * Plays a song, artist or playlist by name in the current (or last used) music app.
     * Spotify, YouTube Music and most players support this through their media session.
     */
    fun search(query: String) {
        main.post {
            val c = ctx ?: return@post
            val q = query.trim()
            fun result(ok: Boolean, text: String) {
                LinkService.instance?.send(JSONObject().put("t", "search_result").put("ok", ok).put("text", text))
                L.i("Music search: $text")
            }
            if (q.isEmpty()) return@post
            val ctl = controller
            val app = ctl?.packageName?.let { appLabel(it) } ?: ""
            val canSearch = ctl != null &&
                ((ctl.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0L
            if (ctl != null && canSearch) {
                ctl.transportControls.playFromSearch(q, Bundle())
                result(true, "Playing \"$q\" on $app")
                main.postDelayed({ push() }, 1500)
                return@post
            }
            // No session that supports search: ask the system to find a player.
            try {
                val i = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(android.app.SearchManager.QUERY, q)
                    .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctl?.packageName?.let { i.setPackage(it) }
                c.startActivity(i)
                result(true, if (ctl != null) "$app has no search, sent to the phone" else "Sent to the phone's music app")
            } catch (e: Exception) {
                result(false, if (ctl != null) "$app does not support search. Try Spotify or YouTube Music."
                    else "Open a music app on the phone once, then search again.")
            }
        }
    }

    /** Fallback when no app has a media session: the last player still gets the key. */
    private fun key(am: AudioManager, code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun appLabel(pkg: String): String = try {
        val pm = ctx!!.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}

/** Sends a typed or quick reply through the app's own Reply button (WhatsApp, SMS, Telegram...). */
object Reply {
    fun send(c: Context, key: String, text: String) {
        val l = NotifListener.instance ?: run {
            L.i("Reply failed: no notification access")
            return
        }
        val sbn = try { l.activeNotifications.firstOrNull { it.key == key } } catch (e: Exception) { null }
        if (sbn == null) {
            L.i("Reply failed: message no longer on the phone")
            LinkService.instance?.send(JSONObject().put("t", "reply_result").put("ok", false)
                .put("why", "Message already cleared on phone"))
            return
        }
        val action = findReply(sbn.notification)
        val input = action?.remoteInputs?.firstOrNull { it.allowFreeFormInput }
        if (action == null || input == null) {
            LinkService.instance?.send(JSONObject().put("t", "reply_result").put("ok", false)
                .put("why", "This app has no reply button"))
            return
        }
        try {
            val intent = Intent()
            val results = Bundle().apply { putCharSequence(input.resultKey, text) }
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
            action.actionIntent.send(c, 0, intent)
            L.i("Replied to ${sbn.packageName}")
            LinkService.instance?.send(JSONObject().put("t", "reply_result").put("ok", true))
        } catch (e: Exception) {
            L.i("Reply failed: ${e.message}")
            LinkService.instance?.send(JSONObject().put("t", "reply_result").put("ok", false)
                .put("why", "Phone refused the reply"))
        }
    }

    fun findReply(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
}

/** Makes the phone ring loudly, even on silent, until stopped or 30 seconds pass. */
object FindPhone {
    private const val CHANNEL = "find"
    private const val NOTIF_ID = 77
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private val autoStop = Runnable { stopRing() }

    fun start(c: Context) {
        main.post {
            if (player != null) return@post
            val ctx = c.applicationContext
            val am = ctx.getSystemService(AudioManager::class.java)
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            // Throws if Do Not Disturb blocks volume changes; the alarm still plays at its current volume.
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (e: SecurityException) {
                savedVolume = -1
            }
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(ctx, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                showStopNotification(ctx)
                main.postDelayed(autoStop, 30_000)
                L.i("Find phone: ringing")
            } catch (e: Exception) {
                L.i("Find phone failed: ${e.message}")
                stopRing()
            }
            LinkService.instance?.send(JSONObject().put("t", "find_state").put("on", player != null))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun stop(c: Context) {
        main.post { stopRing() }
    }

    private fun stopRing() {
        main.removeCallbacks(autoStop)
        val p = player ?: return
        player = null
        try { p.stop() } catch (_: Exception) {}
        p.release()
        val ctx = LinkService.instance ?: return
        if (savedVolume >= 0) {
            try {
                ctx.getSystemService(AudioManager::class.java)
                    .setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (_: SecurityException) {}
        }
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        LinkService.instance?.send(JSONObject().put("t", "find_state").put("on", false))
        L.i("Find phone: stopped")
    }

    private fun showStopNotification(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Find my phone", NotificationManager.IMPORTANCE_HIGH))
        val stop = PendingIntent.getBroadcast(
            c, 0, Intent(c, FindStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Your watch is looking for this phone")
            .setContentText("Tap to stop ringing")
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }
}

class FindStopReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = FindPhone.stop(c)
}

/**
 * One-shot USSD (balance checks and similar) on either SIM. Android lets apps read the first
 * reply only; multi-step menus such as M-Pesa cannot be continued from an app, by design.
 */
object Ussd {
    private val main = Handler(Looper.getMainLooper())

    /** SIMs in the phone, so the watch can offer SIM 1 / SIM 2. */
    @android.annotation.SuppressLint("MissingPermission")
    fun sims(c: Context): JSONObject {
        val arr = org.json.JSONArray()
        try {
            val sm = c.getSystemService(android.telephony.SubscriptionManager::class.java)
            sm.activeSubscriptionInfoList?.sortedBy { it.simSlotIndex }?.forEach {
                arr.put(JSONObject().put("slot", it.simSlotIndex + 1).put("name", it.carrierName?.toString().orEmpty()))
            }
        } catch (e: Exception) {
            L.i("Cannot read SIMs: ${e.message}")
        }
        return JSONObject().put("t", "sims").put("list", arr)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun run(c: Context, code: String, slot: Int) {
        fun reply(ok: Boolean, text: String) {
            LinkService.instance?.send(JSONObject().put("t", "ussd_result").put("ok", ok).put("code", code).put("text", text))
        }
        val clean = code.trim()
        if (!Regex("^[*#][0-9*#]{1,40}#$").matches(clean)) {
            reply(false, "Not a USSD code. It must start with * and end with #.")
            return
        }
        if (c.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            reply(false, "Phone permission missing on the phone app.")
            return
        }
        var tm = c.getSystemService(android.telephony.TelephonyManager::class.java)
        try {
            val sm = c.getSystemService(android.telephony.SubscriptionManager::class.java)
            val sub = sm.activeSubscriptionInfoList?.firstOrNull { it.simSlotIndex == slot - 1 }
            if (sub != null) tm = tm.createForSubscriptionId(sub.subscriptionId)
        } catch (e: Exception) {
            L.i("SIM pick failed, using default: ${e.message}")
        }

        var done = false
        val timeout = Runnable {
            if (!done) {
                done = true
                reply(false, "No answer from the network after 30 s.")
            }
        }
        main.postDelayed(timeout, 30_000)
        try {
            tm.sendUssdRequest(clean, object : android.telephony.TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(t: android.telephony.TelephonyManager, req: String, resp: CharSequence) {
                    if (done) return
                    done = true
                    main.removeCallbacks(timeout)
                    reply(true, resp.toString())
                    L.i("USSD $req answered")
                }

                override fun onReceiveUssdResponseFailed(t: android.telephony.TelephonyManager, req: String, failureCode: Int) {
                    if (done) return
                    done = true
                    main.removeCallbacks(timeout)
                    reply(false, "Network refused the code (error $failureCode). Menu codes like *334# only work on the phone.")
                    L.i("USSD $req failed: $failureCode")
                }
            }, main)
            L.i("USSD $clean sent on SIM $slot")
        } catch (e: Exception) {
            done = true
            main.removeCallbacks(timeout)
            reply(false, "Phone could not send it: ${e.message}")
        }
    }
}
