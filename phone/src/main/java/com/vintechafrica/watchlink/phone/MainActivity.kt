package com.vintechafrica.watchlink.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup screen on the Samsung. Work through the numbered buttons once. */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        root.addView(TextView(this).apply {
            text = "WatchLink"
            textSize = 24f
        })
        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 16, 0, 16)
        }
        root.addView(statusView)
        root.addView(button("1. Grant permissions") { requestPerms() })
        root.addView(button("2. Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("3. Ignore battery optimization") { askBattery() })
        root.addView(button("4. Pick watch") { pickWatch() })
        root.addView(button("5. Start link") {
            Prefs.setEnabled(this, true)
            LinkService.start(this)
        })
        root.addView(button("Stop link") {
            Prefs.setEnabled(this, false)
            LinkService.stop(this)
        })
        root.addView(button("Share log") { shareLog() })
        root.addView(TextView(this).apply {
            text = "Samsung tip: also add WatchLink to Settings > Battery > " +
                "Background usage limits > Never sleeping apps, or One UI will kill the link."
            setPadding(0, 16, 0, 16)
        })
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val notifOn = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        statusView.text = buildString {
            appendLine("Link: ${LinkService.status}")
            appendLine("Watch: ${Prefs.watchName(this@MainActivity) ?: "not picked"}")
            appendLine("Notification access: ${if (notifOn) "on" else "OFF"}")
            appendLine("Phone permissions: ${if (missingPerms().isEmpty()) "all granted" else "missing ${missingPerms().size}"}")
            if (LinkService.watchBattery >= 0) appendLine("Watch battery: ${LinkService.watchBattery}%")
        }
        logView.text = L.dump().lines().takeLast(60).joinToString("\n")
    }

    private fun wantedPerms(): List<String> {
        val p = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 31) p += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p
    }

    private fun missingPerms() = wantedPerms().filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() {
        val missing = missingPerms()
        if (missing.isEmpty()) toast("All permissions granted") else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The call listener only registers at service start, so restart it.
        LinkService.restartIfRunning(this)
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            toast("Open Settings > Apps > WatchLink > Battery > Unrestricted")
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickWatch() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            toast("No Bluetooth on this phone")
            return
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Tap 1. Grant permissions first")
            return
        }
        val devs = try { adapter.bondedDevices.toList() } catch (e: Exception) { emptyList() }
        if (devs.isEmpty()) {
            toast("Pair the watch in Bluetooth settings first")
            return
        }
        val labels = devs.map { (it.name ?: "Unknown") + "\n" + it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pick your watch")
            .setItems(labels) { _, i ->
                val d = devs[i]
                Prefs.setWatch(this, d.address, d.name ?: d.address)
                toast("Watch set: ${d.name}")
                LinkService.restartIfRunning(this)
            }
            .show()
    }

    private fun shareLog() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, L.dump())
        }
        startActivity(Intent.createChooser(i, "Share WatchLink log"))
    }

    private fun button(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { f() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.enabled(c) && Prefs.watchAddr(c) != null) {
            try {
                LinkService.start(c)
            } catch (e: Exception) {
                L.i("Boot start failed: ${e.message}")
            }
        }
    }
}
