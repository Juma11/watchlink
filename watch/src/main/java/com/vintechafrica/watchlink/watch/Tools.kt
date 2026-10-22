package com.vintechafrica.watchlink.watch

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout

/**
 * Battery tools that work without root or adb.
 * The 4G radio, Wi-Fi and screen are the big drains on this watch, not Bluetooth.
 */
class BatteryActivity : Activity() {
    private lateinit var body: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = screen {}
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
        body.removeAllViews()
        val b = Bat.read(this)
        val bright = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) { -1 }
        val timeout = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) / 1000
        } catch (e: Exception) { -1 }
        val wifiOn = try { wifi().isWifiEnabled } catch (e: Exception) { false }

        body.addView(header("Battery"))
        body.addView(hbox().apply {
            addView(text("${b.pct}%", 34f, bold = true))
            addView(vbox().apply {
                setPadding(dp(12), 0, 0, 0)
                addView(text("${"%.1f".format(b.tempC)}°C", 15f, if (b.tempC >= 42f) C.ORANGE else C.SUB, bold = true))
                addView(text(if (b.charging) "Charging" else "On battery", 12f, C.SUB))
            })
        })
        if (b.tempC >= 45f) {
            body.addView(card().apply {
                background = rounded(C.RED)
                addView(text("Too hot. Unplug and rest it.", 14f, bold = true))
            })
        }

        body.addView(tile("📶", "Wi-Fi: ${if (wifiOn) "On" else "Off"}", "Tap to switch", if (wifiOn) C.BLUE else C.CARD2) {
            toggleWifi()
        })
        body.addView(tile("🔅", "Dim screen", "Now: $bright/255, ${timeout}s timeout", C.ORANGE) { dimScreen() })
        body.addView(tile("🍃", "Battery saver mode", "System power saving", C.GREEN) { batterySaver() })
        body.addView(tile("✈", "Airplane mode", "Biggest saving. Turn Bluetooth back on after.", C.PURPLE) {
            open(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
        })
        body.addView(text(
            "Airplane + Bluetooth on kills the 4G radio, the main drain. " +
                "Calls still reach you through your phone.", 12f, C.SUB
        ).apply { setPadding(0, dp(10), 0, 0) })
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        try {
            val w = wifi()
            val wasOn = w.isWifiEnabled
            w.isWifiEnabled = !wasOn
            toast(if (wasOn) "Wi-Fi turning off" else "Wi-Fi turning on")
        } catch (e: Exception) {
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
            toast("Dimmed, 10s timeout")
            L.i("Brightness 40, timeout 10s")
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
        render()
    }

    private fun batterySaver() {
        // Works only if WRITE_SECURE_SETTINGS was granted over adb. Falls back to the settings screen.
        try {
            Settings.Global.putInt(contentResolver, "low_power", 1)
            toast("Battery saver on")
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

/** Shows what this watch's Bluetooth chip supports. */
class BleTestActivity : Activity() {
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = getSystemService(BluetoothManager::class.java)?.adapter
        screen {
            addView(header("Bluetooth test"))
            if (a == null) {
                addView(text("No Bluetooth adapter", 15f, C.RED))
                return@screen
            }
            fun row(k: String, v: Boolean) = addView(hbox().apply {
                setPadding(0, dp(4), 0, dp(4))
                addView(text(k, 14f).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(pill(if (v) "Yes" else "No", if (v) C.GREEN else C.CARD2))
            })
            addView(text(a.name ?: "", 13f, C.SUB))
            row("Enabled", a.isEnabled)
            row("BLE advertising", a.isMultipleAdvertisementSupported)
            row("BLE scan filters", a.isOffloadedFilteringSupported)
            row("BLE batching", a.isOffloadedScanBatchingSupported)
            if (Build.VERSION.SDK_INT >= 26) {
                row("BLE 2M PHY", a.isLe2MPhySupported)
                row("BLE ext adv", a.isLeExtendedAdvertisingSupported)
            }
            addView(text("Paired", 13f, C.SUB, bold = true).apply { setPadding(0, dp(10), 0, 0) })
            try {
                a.bondedDevices.forEach { addView(text("• ${it.name}", 14f)) }
            } catch (e: Exception) {
                addView(text("Cannot read", 14f, C.SUB))
            }
        }
    }
}
