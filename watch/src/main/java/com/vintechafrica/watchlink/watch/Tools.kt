package com.vintechafrica.watchlink.watch

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView

/**
 * Battery tools that work without root or adb.
 * The 4G radio, Wi-Fi and screen are the big drains on this watch, not Bluetooth.
 */
class BatteryActivity : Activity() {
    private lateinit var info: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = column()
        col.addView(label("Battery saver", 18f, bold = true))
        info = label("")
        info.setPadding(0, dp(6), 0, dp(6))
        col.addView(info)
        col.addView(bigButton("Wi-Fi on/off") { toggleWifi() })
        col.addView(bigButton("Dim screen + 10s timeout") { dimScreen() })
        col.addView(bigButton("Battery saver mode") { batterySaver() })
        col.addView(bigButton("Airplane mode settings") {
            open(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            toast("Turn airplane on, then switch Bluetooth back on")
        })
        col.addView(label(
            "Best saving: airplane mode + Bluetooth on. That kills the 4G radio, " +
                "the biggest drain. You lose the watch SIM while it is on.", 12f
        ).apply { setPadding(0, dp(10), 0, 0) })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun wifi(): WifiManager = applicationContext.getSystemService(WifiManager::class.java)

    private fun render() {
        val b = Bat.read(this)
        val bright = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (e: Exception) { -1 }
        val timeout = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) / 1000 } catch (e: Exception) { -1 }
        info.text = "Battery ${b.pct}%  ${"%.1f".format(b.tempC)}°C${if (b.charging) "  charging" else ""}\n" +
            "Wi-Fi: ${if (wifi().isWifiEnabled) "on" else "off"}\n" +
            "Brightness: $bright/255  Timeout: ${timeout}s" +
            if (b.tempC >= 42f) "\nHOT: stop charging and let it cool" else ""
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        try {
            val w = wifi()
            val wasOn = w.isWifiEnabled
            w.isWifiEnabled = !wasOn
            toast(if (wasOn) "Wi-Fi turning off" else "Wi-Fi turning on")
        } catch (e: Exception) {
            toast("Wi-Fi change blocked")
            open(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        handler.postDelayed({ render() }, 1500)
    }

    private fun dimScreen() {
        if (!Settings.System.canWrite(this)) {
            toast("Allow WatchLink to modify settings, then tap again")
            open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        try {
            val cr = contentResolver
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 40)
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 10_000)
            toast("Screen dimmed, 10s timeout")
            L.i("Brightness 40, timeout 10s")
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
        render()
    }

    @SuppressLint("NewApi")
    private fun batterySaver() {
        // Works only if WRITE_SECURE_SETTINGS was granted over adb. Falls back to the settings screen.
        try {
            Settings.Global.putInt(contentResolver, "low_power", 1)
            toast("Battery saver on")
            L.i("Battery saver on")
        } catch (e: SecurityException) {
            open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }
    }

    private fun open(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("This watch has no such settings screen")
        }
    }
}

/** Shows what this watch's Bluetooth chip supports. Send a photo of this screen. */
class BleTestActivity : Activity() {
    @SuppressLint("MissingPermission", "HardwareIds")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = column()
        col.addView(label("Bluetooth test", 18f, bold = true))
        val a = getSystemService(BluetoothManager::class.java)?.adapter
        val text = if (a == null) {
            "No Bluetooth adapter"
        } else buildString {
            appendLine("Enabled: ${a.isEnabled}")
            appendLine("Name: ${a.name}")
            appendLine("BLE advertising: ${a.isMultipleAdvertisementSupported}")
            appendLine("BLE scan filters: ${a.isOffloadedFilteringSupported}")
            appendLine("BLE batching: ${a.isOffloadedScanBatchingSupported}")
            if (Build.VERSION.SDK_INT >= 26) {
                appendLine("BLE 2M PHY: ${a.isLe2MPhySupported}")
                appendLine("BLE ext adv: ${a.isLeExtendedAdvertisingSupported}")
            }
            appendLine()
            appendLine("Paired devices:")
            try {
                a.bondedDevices.forEach { appendLine("• ${it.name}") }
            } catch (e: Exception) {
                appendLine("(cannot read)")
            }
        }
        col.addView(label(text, 13f).apply { setPadding(0, dp(8), 0, 0) })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
    }
}
