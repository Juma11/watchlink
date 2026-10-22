package com.vintechafrica.watchlink.phone

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Shared link constants. Must match the watch app. */
object Proto {
    val UUID_LINK: UUID = UUID.fromString("7c9a1e2e-5b7d-4f0e-9c3a-3f6b2d8e1a01")
    const val NAME = "WatchLink"
}

/** In-memory log so you can read and share it without adb. */
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

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("watchlink", Context.MODE_PRIVATE)

    fun watchAddr(c: Context): String? = p(c).getString("watch_addr", null)
    fun watchName(c: Context): String? = p(c).getString("watch_name", null)
    fun setWatch(c: Context, addr: String, name: String) {
        p(c).edit().putString("watch_addr", addr).putString("watch_name", name).apply()
    }

    fun enabled(c: Context): Boolean = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) {
        p(c).edit().putBoolean("enabled", on).apply()
    }
}
