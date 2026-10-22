package com.vintechafrica.watchlink.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Waits for the phone over Bluetooth RFCOMM and handles everything it sends.
 * The watch is the server: it only listens, the phone does the reconnecting.
 */
class WatchService : Service() {

    companion object {
        @Volatile var instance: WatchService? = null
        @Volatile var status: String = "Stopped"
        private const val NOTIF_ID = 1
        const val CH_SERVICE = "service"
        const val CH_ALERT = "alert"
        const val CH_QUIET = "quiet"

        fun start(c: Context) {
            c.startForegroundService(Intent(c, WatchService::class.java))
        }
    }

    private val sendQueue = LinkedBlockingQueue<String>()
    @Volatile private var running = false
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    private var worker: Thread? = null
    private var vibrator: Vibrator? = null
    @Volatile private var ringing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        vibrator = getSystemService(Vibrator::class.java)
        createChannels()
        val n = buildNotif("Starting")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        running = true
        worker = Thread({ serverLoop() }, "wl-server").also { it.start() }
        L.i("Watch service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        worker?.interrupt()
        stopRinging()
        if (instance === this) instance = null
        status = "Stopped"
        super.onDestroy()
    }

    // ---------- connection ----------

    private fun serverLoop() {
        while (running) {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                setStatus("Bluetooth off")
                if (!sleepQuiet(10_000)) break
                continue
            }
            var s: BluetoothSocket? = null
            try {
                setStatus("Waiting for phone")
                val ss = adapter.listenUsingRfcommWithServiceRecord(Proto.NAME, Proto.UUID_LINK)
                server = ss
                val accepted: BluetoothSocket = try {
                    ss.accept()
                } finally {
                    try { ss.close() } catch (_: Exception) {}
                    server = null
                }
                s = accepted
                socket = accepted
                WatchState.connected = true
                WatchState.phoneName = accepted.remoteDevice?.name ?: "phone"
                setStatus("Connected to ${WatchState.phoneName}")
                WatchState.changed()
                runConnection(accepted)
            } catch (e: Exception) {
                if (running) L.i("Link error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { s?.close() } catch (_: Exception) {}
                socket = null
                WatchState.connected = false
                if (WatchState.callState != "idle") {
                    WatchState.callState = "idle"
                    stopRinging()
                }
                WatchState.changed()
            }
            if (!running) break
            setStatus("Disconnected")
            if (!sleepQuiet(2_000)) break
        }
    }

    private fun runConnection(s: BluetoothSocket) {
        sendQueue.clear()
        // The phone resends every active notification on connect.
        WatchState.clearNotifs().forEach { cancelMirror(it) }
        val out = BufferedWriter(OutputStreamWriter(s.outputStream, Charsets.UTF_8))
        val alive = AtomicBoolean(true)
        val writer = Thread({
            try {
                while (alive.get()) {
                    val msg = sendQueue.take()
                    out.write(msg)
                    out.write("\n")
                    out.flush()
                }
            } catch (e: InterruptedException) {
                // normal shutdown
            } catch (e: Exception) {
                L.i("Write ended: ${e.message}")
            }
            alive.set(false)
            try { s.close() } catch (_: Exception) {}
        }, "wl-writer")
        writer.start()

        send(JSONObject().put("t", "hello").put("bat", Bat.read(this).pct).put("model", Build.MODEL))
        if (WatchState.contacts.isEmpty()) send(JSONObject().put("t", "get_contacts"))

        try {
            val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            while (alive.get()) {
                val line = reader.readLine() ?: break
                handle(line)
            }
        } catch (e: Exception) {
            L.i("Read ended: ${e.message}")
        }
        alive.set(false)
        writer.interrupt()
    }

    fun send(o: JSONObject) {
        if (socket == null) {
            L.i("Not connected, dropped ${o.optString("t")}")
            return
        }
        sendQueue.offer(o.toString())
    }

    fun sendCmd(action: String, num: String = "") {
        send(JSONObject().put("t", "cmd").put("a", action).put("num", num))
        L.i("Sent command: $action")
    }

    private fun handle(line: String) {
        val o = try { JSONObject(line) } catch (e: Exception) {
            L.i("Bad message from phone")
            return
        }
        when (o.optString("t")) {
            "hello" -> L.i("Phone says hello")
            "ping" -> send(JSONObject().put("t", "pong").put("bat", Bat.read(this).pct))
            "pong" -> {}
            "call" -> onCall(o)
            "notif" -> {
                val n = Notif(
                    o.optString("key"), o.optString("app"), o.optString("title"),
                    o.optString("text"), o.optLong("time", System.currentTimeMillis())
                )
                val old = WatchState.notifList().firstOrNull { it.key == n.key }
                val isNew = old == null || old.text != n.text || old.title != n.title
                val sync = o.optBoolean("sync", false)
                val muted = WPrefs.isMuted(this, n.app)
                WatchState.putNotif(n)
                showMirror(n, quiet = sync || muted || !isNew)
                WatchState.changed()
                if (isNew && !sync && !muted && WPrefs.popups(this) && WatchState.callState == "idle") {
                    startActivity(
                        Intent(this, NotifPopupActivity::class.java)
                            .putExtra("key", n.key)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                }
            }
            "notif_rm" -> {
                val key = o.optString("key")
                WatchState.removeNotif(key)
                cancelMirror(key)
                WatchState.changed()
            }
            "contacts" -> {
                val arr = o.optJSONArray("list")
                val list = ArrayList<Contact>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        list.add(Contact(c.optString("n"), c.optString("p")))
                    }
                }
                WatchState.contacts = list
                L.i("Got ${list.size} contacts")
                WatchState.changed()
            }
            else -> L.i("Unknown message: ${o.optString("t")}")
        }
    }

    // ---------- calls ----------

    private fun onCall(o: JSONObject) {
        val st = o.optString("state", "idle")
        WatchState.callState = st
        WatchState.callName = o.optString("name")
        WatchState.callNum = o.optString("num")
        L.i("Call $st: ${WatchState.callerLabel()}")
        when (st) {
            "ringing" -> {
                startRinging()
                // Allowed on Android 8.1: services may open activities.
                startActivity(
                    Intent(this, CallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            else -> stopRinging()
        }
        WatchState.changed()
    }

    fun startRinging() {
        if (ringing) return
        ringing = true
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
        } catch (e: Exception) {
            L.i("Vibrate failed: ${e.message}")
        }
    }

    fun stopRinging() {
        ringing = false
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    // ---------- mirrored notifications on the watch ----------

    private fun mirrorId(key: String) = 1000 + (key.hashCode() and 0x0fffffff)

    private fun showMirror(n: Notif, quiet: Boolean) {
        val open = PendingIntent.getActivity(
            this, mirrorId(n.key),
            Intent(this, NotifDetailActivity::class.java).putExtra("key", n.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val del = PendingIntent.getBroadcast(
            this, mirrorId(n.key),
            Intent(this, DismissReceiver::class.java).putExtra("key", n.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(this, if (quiet) CH_QUIET else CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (n.title.isNotEmpty()) n.title else n.app)
            .setContentText(n.text)
            .setStyle(Notification.BigTextStyle().bigText(n.text))
            .setSubText(n.app)
            .setWhen(n.time)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setDeleteIntent(del)
            .build()
        getSystemService(NotificationManager::class.java).notify(mirrorId(n.key), notif)
    }

    fun cancelMirror(key: String) {
        getSystemService(NotificationManager::class.java).cancel(mirrorId(key))
    }

    // ---------- helpers ----------

    private fun sleepQuiet(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (e: InterruptedException) {
        false
    }

    private fun setStatus(s: String) {
        status = s
        L.i("Status: $s")
        WatchState.changed()
        if (!running) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(s))
        } catch (_: Exception) {}
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Link status", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Phone notifications", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 100, 150)
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_QUIET, "Synced notifications", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH_SERVICE)
            .setContentTitle("WatchLink")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}

/** Swiping a mirrored notification away on the watch also clears it on the phone. */
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val key = i.getStringExtra("key") ?: return
        WatchState.removeNotif(key)
        WatchService.instance?.send(JSONObject().put("t", "notif_dismiss").put("key", key))
        WatchState.changed()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) {
            try { WatchService.start(c) } catch (e: Exception) { L.i("Boot start failed: ${e.message}") }
        }
    }
}
