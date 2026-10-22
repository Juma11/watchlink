package com.vintechafrica.watchlink.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps one Bluetooth RFCOMM link to the watch alive.
 * The phone is the client: it has the bigger battery, so it does the reconnecting.
 */
class LinkService : Service() {

    companion object {
        @Volatile var instance: LinkService? = null
        @Volatile var status: String = "Stopped"
        @Volatile var watchBattery: Int = -1
        private const val NOTIF_ID = 1
        private const val CHANNEL = "link"

        fun start(c: Context) {
            c.startForegroundService(Intent(c, LinkService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, LinkService::class.java))
        }

        fun restartIfRunning(c: Context) {
            if (instance != null) {
                stop(c)
                start(c)
            }
        }
    }

    private val sendQueue = LinkedBlockingQueue<String>()
    @Volatile private var running = false
    @Volatile private var socket: BluetoothSocket? = null
    private var worker: Thread? = null
    @Volatile private var lastCall: JSONObject? = null
    private var phoneListener: PhoneStateListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        try {
            val n = buildNotif("Starting")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            L.i("Cannot start in foreground: ${e.message}. Grant permissions first.")
            instance = null
            stopSelf()
            return
        }
        registerCallListener()
        running = true
        worker = Thread({ connectLoop() }, "wl-connect").also { it.start() }
        L.i("Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        worker?.interrupt()
        phoneListener?.let {
            @Suppress("DEPRECATION")
            tm().listen(it, PhoneStateListener.LISTEN_NONE)
        }
        if (instance === this) instance = null
        status = "Stopped"
        L.i("Service stopped")
        super.onDestroy()
    }

    // ---------- connection ----------

    private fun connectLoop() {
        var backoff = 5_000L
        while (running) {
            val addr = Prefs.watchAddr(this)
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (addr == null || adapter == null || !adapter.isEnabled) {
                setStatus(if (addr == null) "No watch selected" else "Bluetooth off")
                if (!sleepQuiet(10_000)) break
                continue
            }
            var s: BluetoothSocket? = null
            try {
                setStatus("Connecting to ${Prefs.watchName(this) ?: addr}")
                val dev = adapter.getRemoteDevice(addr)
                try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
                s = dev.createRfcommSocketToServiceRecord(Proto.UUID_LINK)
                s.connect()
                socket = s
                backoff = 5_000L
                setStatus("Connected")
                runConnection(s)
            } catch (e: Exception) {
                L.i("Link error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { s?.close() } catch (_: Exception) {}
                socket = null
            }
            if (!running) break
            setStatus("Disconnected, retry in ${backoff / 1000}s")
            if (!sleepQuiet(backoff)) break
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun runConnection(s: BluetoothSocket) {
        sendQueue.clear()
        val out = BufferedWriter(OutputStreamWriter(s.outputStream, Charsets.UTF_8))
        val alive = AtomicBoolean(true)
        val writer = Thread({
            try {
                while (alive.get()) {
                    // Ping every 2 minutes so a dead link is noticed.
                    val msg = sendQueue.poll(120, TimeUnit.SECONDS)
                        ?: JSONObject().put("t", "ping").toString()
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

        send(JSONObject().put("t", "hello").put("v", 1))
        lastCall?.let { send(it) }
        NotifListener.snapshot().forEach { send(it.put("sync", true)) }

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

    fun send(json: String) {
        if (socket != null) sendQueue.offer(json)
    }

    fun send(o: JSONObject) = send(o.toString())

    private fun handle(line: String) {
        val o = try { JSONObject(line) } catch (e: Exception) {
            L.i("Bad message from watch")
            return
        }
        when (o.optString("t")) {
            "hello" -> {
                watchBattery = o.optInt("bat", -1)
                L.i("Watch connected, battery $watchBattery%")
            }
            "pong" -> watchBattery = o.optInt("bat", watchBattery)
            "ping" -> send(JSONObject().put("t", "pong"))
            "cmd" -> CallControl.run(this, o.optString("a"), o.optString("num"))
            "notif_dismiss" -> NotifListener.dismiss(o.optString("key"))
            "get_contacts" -> Thread({ sendContacts() }, "wl-contacts").start()
            else -> L.i("Unknown message: ${o.optString("t")}")
        }
    }

    // ---------- calls ----------

    private fun tm(): TelephonyManager = getSystemService(TelephonyManager::class.java)

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun registerCallListener() {
        if (!has(Manifest.permission.READ_PHONE_STATE)) {
            L.i("No phone state permission: call alerts are off")
            return
        }
        val l = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                onCall(state, phoneNumber)
            }
        }
        phoneListener = l
        try {
            tm().listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            L.i("Call listener failed: ${e.message}")
        }
    }

    private fun onCall(state: Int, number: String?) {
        val st = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
            else -> "idle"
        }
        if (st != "ringing") CallControl.restoreRinger(this)
        val num = number.orEmpty()
        val name = if (num.isNotEmpty()) lookupName(num) else ""
        val o = JSONObject().put("t", "call").put("state", st).put("num", num).put("name", name)
        lastCall = if (st == "idle") null else o
        L.i("Call $st ${name.ifEmpty { num }}")
        send(o)
    }

    private fun lookupName(num: String): String {
        if (!has(Manifest.permission.READ_CONTACTS)) return ""
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ---------- contacts ----------

    private fun sendContacts() {
        if (!has(Manifest.permission.READ_CONTACTS)) {
            L.i("No contacts permission")
            return
        }
        val arr = JSONArray()
        val seen = HashSet<String>()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                while (c.moveToNext() && arr.length() < 1500) {
                    val name = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    val key = name + "|" + num.filter { it.isDigit() || it == '+' }
                    if (seen.add(key)) arr.put(JSONObject().put("n", name).put("p", num))
                }
            }
        } catch (e: Exception) {
            L.i("Contacts read failed: ${e.message}")
        }
        send(JSONObject().put("t", "contacts").put("list", arr))
        L.i("Sent ${arr.length()} contacts")
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
        if (!running) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(s))
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Watch link", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    @SuppressLint("LaunchActivityFromNotification")
    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("WatchLink")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
