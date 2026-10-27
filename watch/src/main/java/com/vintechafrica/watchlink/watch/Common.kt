package com.vintechafrica.watchlink.watch

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Shared link constants. Must match the phone app. */
object Proto {
    val UUID_LINK: UUID = UUID.fromString("7c9a1e2e-5b7d-4f0e-9c3a-3f6b2d8e1a01")
    const val NAME = "WatchLink"
}

/** In-memory log, readable on the watch's Log screen since there is no adb. */
object L {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun i(msg: String) {
        android.util.Log.i("WatchLink", msg)
        lines.addLast(fmt.format(Date()) + " " + msg)
        while (lines.size > 400) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}

data class Notif(
    val key: String, val app: String, val title: String, val text: String, val time: Long,
    val canReply: Boolean = false
)

data class Media(
    val has: Boolean = false, val title: String = "", val artist: String = "", val app: String = "",
    val playing: Boolean = false, val vol: Int = 0, val volMax: Int = 15
)
data class Contact(val name: String, val phone: String)
data class Sim(val slot: Int, val name: String)
data class UssdResult(val ok: Boolean, val code: String, val text: String)

/** Everything the screens show. Updated by WatchService, read by activities. */
object WatchState {
    @Volatile var connected = false
    @Volatile var phoneName = ""
    @Volatile var callState = "idle"
    @Volatile var callName = ""
    @Volatile var callNum = ""
    @Volatile var callDir = ""
    @Volatile var callApp = ""   // "WhatsApp" etc. for internet calls, empty for the phone line
    @Volatile var contacts: List<Contact> = emptyList()
    @Volatile var media = Media()
    @Volatile var findingPhone = false
    @Volatile var lastReply: String? = null   // result text of the last reply, shown once
    @Volatile var sims: List<Sim> = emptyList()
    @Volatile var ussd: UssdResult? = null
    @Volatile var ussdBusy = false
    @Volatile var searchResult: String? = null
    private val notifs = LinkedHashMap<String, Notif>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { main.post { listeners.forEach { it() } } }

    @Synchronized fun notifList(): List<Notif> = notifs.values.sortedByDescending { it.time }
    @Synchronized fun notifCount(): Int = notifs.size
    @Synchronized fun putNotif(n: Notif) { notifs[n.key] = n }
    @Synchronized fun removeNotif(key: String) { notifs.remove(key) }
    @Synchronized fun clearNotifs(): List<String> {
        val keys = notifs.keys.toList()
        notifs.clear()
        return keys
    }

    fun callerLabel(): String = when {
        callName.isNotEmpty() -> callName
        callNum.isNotEmpty() -> callNum
        callDir == "out" -> "Outgoing call"
        else -> "Unknown caller"
    }
}

data class BatteryInfo(val pct: Int, val tempC: Float, val charging: Boolean)

object Bat {
    fun read(c: Context): BatteryInfo {
        val i: Intent? = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return BatteryInfo(pct, temp, charging)
    }
}
