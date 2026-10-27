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
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Dark palette shared with the watch app. */
private object P {
    const val BG = Color.BLACK
    val CARD = Color.rgb(28, 28, 30)
    val CARD2 = Color.rgb(44, 44, 46)
    const val TEXT = Color.WHITE
    val SUB = Color.rgb(142, 142, 147)
    val BLUE = Color.rgb(10, 132, 255)
    val GREEN = Color.rgb(48, 209, 88)
    val RED = Color.rgb(255, 69, 58)
    val ORANGE = Color.rgb(255, 159, 10)
    val PURPLE = Color.rgb(191, 90, 242)
}

class MainActivity : Activity() {

    private lateinit var body: LinearLayout
    private var showLog = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastSig = ""
    private val tick = object : Runnable {
        override fun run() {
            render(force = false)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = P.BG
        window.navigationBarColor = P.BG
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(P.BG)
            isVerticalScrollBarEnabled = false
            addView(body)
        })
    }

    override fun onResume() {
        super.onResume()
        // Restart the link if it should be running (for example right after an update).
        if (Prefs.enabled(this) && Prefs.watchAddr(this) != null && LinkService.instance == null && missingPerms().isEmpty()) {
            LinkService.start(this)
        }
        render(force = true)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // ---------------------------------------------------------------- screen

    private fun render(force: Boolean) {
        val sig = listOf(
            LinkService.status, LinkService.watchBattery, Prefs.watchName(this), Prefs.enabled(this),
            notifOn(), missingPerms().size, batteryOk(), showLog,
            if (showLog) L.dump().length else 0
        ).joinToString("|")
        if (!force && sig == lastSig) return
        lastSig = sig
        body.removeAllViews()

        // Header
        body.addView(text("WatchLink", 26f, bold = true))
        body.addView(text("Calls, messages and music on your watch", 13f, P.SUB).apply {
            setPadding(0, dp(2), 0, dp(14))
        })

        // Status card
        val running = LinkService.instance != null
        val connected = LinkService.status == "Connected"
        val (stateText, stateColor) = when {
            connected -> "Connected" to P.GREEN
            running -> friendlyStatus() to P.ORANGE
            else -> "Off" to P.SUB
        }
        body.addView(card().apply {
            addView(hbox().apply {
                addView(dot(stateColor))
                addView(text("  $stateText", 15f, stateColor, bold = true))
            })
            addView(text(Prefs.watchName(this@MainActivity) ?: "No watch picked", 22f, bold = true, lines = 1).apply {
                setPadding(0, dp(8), 0, 0)
            })
            val sub = buildString {
                if (connected && LinkService.watchBattery >= 0) append("Watch battery ${LinkService.watchBattery}%")
                else if (!running) append("Turn on to send calls and notifications to the watch")
                else append("Keep Bluetooth on and the watch app open once")
            }
            addView(text(sub, 13f, P.SUB).apply { setPadding(0, dp(2), 0, dp(12)) })
            addView(button(
                if (running) "Turn off" else "Turn on",
                if (running) P.CARD2 else P.BLUE
            ) {
                if (running) {
                    Prefs.setEnabled(this@MainActivity, false)
                    LinkService.stop(this@MainActivity)
                } else {
                    when {
                        missingPerms().isNotEmpty() -> requestPerms()
                        Prefs.watchAddr(this@MainActivity) == null -> pickWatch()
                        else -> {
                            Prefs.setEnabled(this@MainActivity, true)
                            LinkService.start(this@MainActivity)
                        }
                    }
                }
                handler.postDelayed({ render(true) }, 300)
            })
        })

        // Setup: only what is still missing
        val steps = listOf(
            Step("Phone permissions", "Calls, contacts, Bluetooth", missingPerms().isEmpty()) { requestPerms() },
            Step("Notification access", "Messages and music", notifOn()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            Step("Run in background", "Stops Samsung killing the link", batteryOk()) { askBattery() },
            Step("Watch", Prefs.watchName(this) ?: "Pick your paired watch", Prefs.watchAddr(this) != null) { pickWatch() }
        )
        val todo = steps.count { !it.done }
        body.addView(section(if (todo == 0) "Setup complete" else "Setup · $todo left"))
        body.addView(card().apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            steps.forEachIndexed { i, s ->
                if (i > 0) addView(divider())
                addView(stepRow(s))
            }
        })
        if (todo == 0) {
            body.addView(text(
                "Samsung: also add WatchLink to Settings › Battery › Background usage limits › Never sleeping apps.",
                12f, P.SUB
            ).apply { setPadding(dp(4), dp(8), dp(4), 0) })
        }

        // More
        body.addView(section("More"))
        body.addView(card().apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(linkRow("Change watch") { pickWatch() })
            addView(divider())
            addView(linkRow(if (showLog) "Hide log" else "Show log") {
                showLog = !showLog
                render(true)
            })
            addView(divider())
            addView(linkRow("Share log") { shareLog() })
        })

        if (showLog) {
            body.addView(card().apply {
                addView(text(L.dump().lines().takeLast(80).joinToString("\n").ifEmpty { "Log is empty" }, 11f, P.SUB).apply {
                    typeface = Typeface.MONOSPACE
                })
            })
        }
    }

    private fun friendlyStatus(): String {
        val s = LinkService.status
        return when {
            s.startsWith("Connecting") -> "Connecting…"
            s.startsWith("Disconnected") -> "Watch out of reach, retrying"
            s == "Bluetooth off" -> "Bluetooth is off"
            s == "No watch selected" -> "Pick a watch"
            else -> s
        }
    }

    // ---------------------------------------------------------------- setup state

    private fun notifOn() = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        ?.contains(packageName) == true

    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

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
        render(true)
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            toast("Open Settings › Apps › WatchLink › Battery › Unrestricted")
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
            requestPerms()
            return
        }
        val devs = try { adapter.bondedDevices.toList() } catch (e: Exception) { emptyList() }
        if (devs.isEmpty()) {
            toast("Pair the watch in Bluetooth settings first")
            try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {}
            return
        }
        val labels = devs.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Pick your watch")
            .setItems(labels) { _, i ->
                val d = devs[i]
                Prefs.setWatch(this, d.address, d.name ?: d.address)
                LinkService.restartIfRunning(this)
                render(true)
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

    // ---------------------------------------------------------------- UI helpers

    private class Step(val title: String, val sub: String, val done: Boolean, val fix: () -> Unit)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int = 18) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat()
        setColor(color)
    }

    private fun text(t: CharSequence, size: Float, color: Int = P.TEXT, bold: Boolean = false, lines: Int = 0) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            if (lines > 0) {
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
            }
        }

    private fun hbox() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(P.CARD, 20)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    private fun section(t: String) = text(t.uppercase(), 12f, P.SUB, bold = true).apply {
        setPadding(dp(6), dp(22), 0, dp(2))
        letterSpacing = 0.06f
    }

    private fun dot(color: Int) = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(P.CARD2)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            leftMargin = dp(52)
        }
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = text(label, 16f, P.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(color, 26)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun stepRow(s: Step) = hbox().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(text(if (s.done) "✓" else "!", 15f, P.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (s.done) P.GREEN else P.ORANGE)
            }
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(s.title, 15f, bold = true))
            addView(text(s.sub, 12f, P.SUB, lines = 1))
        })
        if (!s.done) addView(text("Fix", 14f, P.BLUE, bold = true))
        isClickable = true
        setOnClickListener { s.fix() }
    }

    private fun linkRow(label: String, onClick: () -> Unit) = hbox().apply {
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(text(label, 15f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(text("›", 18f, P.SUB))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/** Starts the link after a reboot, and after an app update replaces the running copy. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.enabled(c) && Prefs.watchAddr(c) != null) {
            try {
                LinkService.start(c)
            } catch (e: Exception) {
                L.i("Auto start failed: ${e.message}")
            }
        }
    }
}
