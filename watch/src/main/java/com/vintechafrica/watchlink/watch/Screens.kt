package com.vintechafrica.watchlink.watch

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject

private fun Activity.showOverLock() {
    if (Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    } else {
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
    }
}

private fun dismissNotif(n: Notif) {
    WatchService.instance?.send(JSONObject().put("t", "notif_dismiss").put("key", n.key))
    WatchService.instance?.cancelMirror(n.key)
    WatchState.removeNotif(n.key)
    WatchState.changed()
}

// ================= home =================

class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchService.start(this)
        body = screen {}
    }

    override fun onResume() {
        super.onResume()
        WatchState.addListener(listener)
        handler.post(tick)
    }

    override fun onPause() {
        WatchState.removeListener(listener)
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        body.removeAllViews()
        val b = Bat.read(this)

        // One quiet status line
        val (dot, label) = when {
            WatchState.connected -> C.GREEN to WatchState.phoneName.ifEmpty { "Connected" }
            WatchService.status == "Bluetooth off" -> C.RED to "Bluetooth off"
            else -> C.ORANGE to "Waiting for phone"
        }
        body.addView(hbox().apply {
            setPadding(dp(4), dp(2), dp(4), dp(10))
            addView(text("●", 12f, dot))
            addView(text("  $label", 13f, C.SUB, lines = 1).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(text("${b.pct}%", 13f, if (b.tempC >= 45f) C.RED else C.SUB))
        })

        if (b.tempC >= 45f) {
            body.addView(card().apply {
                background = rounded(C.RED)
                addView(text("Hot: ${"%.0f".format(b.tempC)}°C. Let it cool.", 13f, bold = true))
            })
        }
        if (needsSetup()) {
            body.addView(tile("!", "Finish setup", "Two permissions needed", C.ORANGE) {
                startActivity(Intent(this, SettingsActivity::class.java))
            })
        }

        val notifs = WatchState.notifList()
        body.addView(tile(
            "🔔", "Notifications",
            notifs.firstOrNull()?.let { "${notifs.size} · ${it.app}" } ?: "All caught up", C.BLUE
        ) { startActivity(Intent(this, NotifActivity::class.java)) })

        val m = WatchState.media
        body.addView(tile(if (m.playing) "▶" else "♪", "Music",
            if (m.has && m.title.isNotEmpty()) "${m.title}${if (m.artist.isNotEmpty()) " · ${m.artist}" else ""}"
            else "Nothing playing", C.PURPLE
        ) { startActivity(Intent(this, MusicActivity::class.java)) })

        body.addView(tile("👤", "Calls",
            if (WatchState.contacts.isEmpty()) "Keypad · tap to sync contacts" else "Keypad · ${WatchState.contacts.size} contacts", C.GREEN
        ) { startActivity(Intent(this, ContactsActivity::class.java)) })

        val finding = WatchState.findingPhone
        body.addView(tile("🧰", "Tools",
            if (finding) "Phone is ringing" else "Find phone · USSD · Battery ${b.pct}%",
            if (finding) C.RED else C.ORANGE) {
            startActivity(Intent(this, ToolsActivity::class.java))
        })

        body.addView(tile("⚙", "Settings", null, C.CARD2) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
    }

    private fun needsSetup(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        return !Alerts.canBanner(this) || !pm.isIgnoringBatteryOptimizations(packageName)
    }
}

// ================= settings =================

class SettingsActivity : Activity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = screen {}
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        body.removeAllViews()
        body.addView(header("Settings"))

        val banner = Alerts.canBanner(this)
        body.addView(tile("⬇", "Banners on top", if (banner) "Allowed" else "Tap to allow", if (banner) C.GREEN else C.ORANGE) {
            if (!banner) open(Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            ))
        })

        val pm = getSystemService(android.os.PowerManager::class.java)
        val bg = pm.isIgnoringBatteryOptimizations(packageName)
        body.addView(tile("🔒", "Work when locked", if (bg) "Allowed" else "Tap to allow", if (bg) C.GREEN else C.ORANGE) {
            if (!bg) {
                @android.annotation.SuppressLint("BatteryLife")
                val i = Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
                open(i)
            }
        })

        val pop = WPrefs.popups(this)
        body.addView(tile("💬", "Alerts", if (pop) "On" else "Off, list only", if (pop) C.BLUE else C.CARD2) {
            WPrefs.setPopups(this, !pop)
            render()
        })

        val muted = WPrefs.muted(this)
        if (muted.isNotEmpty()) {
            body.addView(tile("🔕", "Muted apps", muted.sorted().joinToString(", "), C.CARD2) {
                muted.forEach { WPrefs.toggleMute(this, it) }
                toast("All apps unmuted")
                render()
            })
        }

        body.addView(tile("📶", "Bluetooth test", null, C.CARD2) {
            startActivity(Intent(this, BleTestActivity::class.java))
        })
        body.addView(tile("🧾", "Log", null, C.CARD2) {
            startActivity(Intent(this, LogActivity::class.java))
        })
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { toast("Not available on this watch") }
    }
}

// ================= call =================

class CallActivity : Activity() {
    private lateinit var body: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private var answeredAt = 0L
    private var shownState = ""
    private val tick = object : Runnable {
        override fun run() {
            if (WatchState.callState == "offhook") render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLock()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeScreen(30_000)
        body = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(C.BG)
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        setContentView(body)
        WatchState.addListener(listener)
        handler.post(tick)
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        wakeScreen(30_000)
        render()
    }

    override fun onDestroy() {
        WatchState.removeListener(listener)
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun render() {
        val st = WatchState.callState
        if (st != "ringing" && st != "offhook") {
            finish()
            return
        }
        if (st == "offhook" && answeredAt == 0L) answeredAt = SystemClock.elapsedRealtime()
        val sig = st + "|" + WatchState.callerLabel() + "|" + WatchState.callNum
        if (sig == shownState && st == "ringing") return
        shownState = sig
        body.removeAllViews()

        val who = WatchState.callerLabel()
        val via = WatchState.callApp
        val ringLabel = if (via.isNotEmpty()) "Incoming $via call" else "Incoming call"
        body.addView(text(if (st == "ringing") ringLabel else elapsed(), 14f,
            if (st == "ringing") C.GREEN else C.SUB))
        body.addView(avatar(initial(who), C.avatarColor(who), 76, 32f).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
        })
        body.addView(text(who, 22f, bold = true, lines = 2).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        if (WatchState.callName.isNotEmpty() && WatchState.callNum.isNotEmpty()) {
            body.addView(text(WatchState.callNum, 14f, C.SUB))
        }
        if (st == "offhook") {
            body.addView(text("Talk on your phone or earbuds", 12f, C.SUB).apply { setPadding(0, dp(6), 0, 0) })
        }

        // Push buttons to the bottom
        body.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        })

        val row = hbox().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (st == "ringing") {
            row.addView(roundAction("✕", "Reject", C.RED) { WatchService.instance?.sendCmd("reject") })
            row.addView(roundAction("✆", "Answer", C.GREEN) { WatchService.instance?.sendCmd("answer") })
        } else {
            row.addView(roundAction("✕", "Hang up", C.RED) { WatchService.instance?.sendCmd("hangup") })
        }
        body.addView(row)
        if (st == "ringing") {
            body.addView(text("Silence", 15f, C.SUB, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(4))
                isClickable = true
                setOnClickListener {
                    WatchService.instance?.stopRinging()
                    WatchService.instance?.sendCmd("silence")
                    toast("Silenced")
                }
            })
        }
    }

    private fun elapsed(): String {
        val s = (SystemClock.elapsedRealtime() - answeredAt) / 1000
        val via = WatchState.callApp
        val label = when {
            via.isNotEmpty() -> "$via call"
            WatchState.callDir == "out" -> "Calling"
            else -> "On call"
        }
        return "$label · %d:%02d".format(s / 60, s % 60)
    }
}

// ================= notification list =================

class NotifActivity : Activity() {
    private lateinit var body: LinearLayout
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = screen {}
    }

    override fun onResume() {
        super.onResume()
        WatchState.addListener(listener)
        render()
    }

    override fun onPause() {
        WatchState.removeListener(listener)
        super.onPause()
    }

    private fun render() {
        body.removeAllViews()
        val items = WatchState.notifList()
        val clear = if (items.isEmpty()) null else pill("Clear all", C.CARD2).apply {
            isClickable = true
            setOnClickListener {
                WatchState.notifList().forEach { dismissNotif(it) }
                toast("Cleared")
            }
        }
        body.addView(header("Notifications", clear))
        if (!WatchState.connected) {
            body.addView(text("Phone offline. List may be out of date.", 12f, C.ORANGE))
        }
        if (items.isEmpty()) {
            body.addView(text("All caught up", 15f, C.SUB).apply { setPadding(0, dp(30), 0, 0); gravity = Gravity.CENTER })
            return
        }
        items.take(60).forEach { n -> body.addView(notifCard(n)) }
    }

    private fun notifCard(n: Notif): LinearLayout = card {
        startActivity(Intent(this, NotifDetailActivity::class.java).putExtra("key", n.key))
    }.apply {
        addView(hbox().apply {
            addView(text(n.app, 12f, C.avatarColor(n.app), bold = true, lines = 1).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(text(timeAgo(n.time), 12f, C.SUB))
        })
        if (n.title.isNotEmpty()) addView(text(n.title, 15f, bold = true, lines = 1))
        if (n.text.isNotEmpty()) addView(text(n.text, 13f, C.SUB, lines = 2))
    }
}

// ================= one notification =================

class NotifDetailActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra("key").orEmpty()
        val n = WatchState.notifList().firstOrNull { it.key == key }
        if (n == null) {
            finish()
            return
        }
        screen {
            addView(hbox().apply {
                addView(avatar(initial(n.app), C.avatarColor(n.app), 32, 14f))
                addView(text("  ${n.app} · ${timeAgo(n.time)}", 13f, C.SUB, lines = 1))
            })
            if (n.title.isNotEmpty()) addView(text(n.title, 18f, bold = true).apply { setPadding(0, dp(10), 0, 0) })
            addView(text(n.text, 15f).apply { setPadding(0, dp(6), 0, dp(6)) })
            if (n.canReply) {
                addView(action("Reply", C.BLUE) {
                    startActivity(Intent(this@NotifDetailActivity, ReplyActivity::class.java).putExtra("key", n.key))
                    finish()
                })
            }
            addView(action("Dismiss", C.RED) {
                dismissNotif(n)
                finish()
            })
            val muted = WPrefs.isMuted(this@NotifDetailActivity, n.app)
            addView(action(if (muted) "Unmute ${n.app}" else "Mute ${n.app} popups", C.CARD2) {
                val now = WPrefs.toggleMute(this@NotifDetailActivity, n.app)
                toast(if (now) "${n.app} muted" else "${n.app} unmuted")
                finish()
            })
            addView(action("Back", C.CARD2) { finish() })
        }
    }
}

// ================= lock-screen card for new notifications =================

class NotifPopupActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { finish() }
    private var extra = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLock()
        show(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        extra++
        if (intent != null) show(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoClose)
        super.onDestroy()
    }

    private fun show(i: Intent) {
        val key = i.getStringExtra("key").orEmpty()
        val n = WatchState.notifList().firstOrNull { it.key == key }
        if (n == null) {
            finish()
            return
        }
        wakeScreen(7_000)
        handler.removeCallbacks(autoClose)
        handler.postDelayed(autoClose, 6_000)

        val col = vbox().apply {
            setBackgroundColor(C.BG)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            isClickable = true
            setOnClickListener { finish() }
        }
        col.addView(card {
            handler.removeCallbacks(autoClose)
            startActivity(Intent(this, NotifDetailActivity::class.java).putExtra("key", n.key))
            finish()
        }.apply {
            background = rounded(C.CARD2, 22)
            addView(hbox().apply {
                addView(avatar(initial(n.app), C.avatarColor(n.app), 30, 13f))
                addView(text("  ${n.app} · now", 12f, C.SUB, lines = 1))
            })
            if (n.title.isNotEmpty()) addView(text(n.title, 16f, bold = true, lines = 1).apply { setPadding(0, dp(6), 0, 0) })
            addView(text(n.text, 14f, lines = 4))
            if (extra > 0) addView(text("+$extra more", 12f, C.BLUE).apply { setPadding(0, dp(4), 0, 0) })
        })
        col.addView(text("Tap to open · tap outside to close", 11f, C.SUB).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        setContentView(col)
    }
}

// ================= contacts =================

class ContactsActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private var selected: Contact? = null
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sync = pill("Sync", C.CARD2).apply {
            isClickable = true
            setOnClickListener {
                WatchService.instance?.send(JSONObject().put("t", "get_contacts"))
                toast("Asking phone…")
            }
        }
        val keypad = pill("Keypad", C.GREEN).apply {
            isClickable = true
            setOnClickListener { startActivity(Intent(this@ContactsActivity, DialerActivity::class.java)) }
        }
        val right = hbox().apply {
            addView(keypad)
            addView(android.view.View(this@ContactsActivity), LinearLayout.LayoutParams(dp(6), 1))
            addView(sync)
        }
        screen {
            addView(header("Calls", right))
            search = EditText(this@ContactsActivity).apply {
                hint = "Search"
                textSize = 15f
                setTextColor(C.TEXT)
                setHintTextColor(C.SUB)
                setSingleLine()
                background = rounded(C.CARD, 20)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = matchWrap(6, this@ContactsActivity)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) = render()
                })
            }
            addView(search)
            list = vbox()
            addView(list)
        }
    }

    override fun onResume() {
        super.onResume()
        WatchState.addListener(listener)
        render()
    }

    override fun onPause() {
        WatchState.removeListener(listener)
        super.onPause()
    }

    private fun render() {
        list.removeAllViews()
        if (WatchState.contacts.isEmpty()) {
            list.addView(text(
                if (WatchState.connected) "Tap Sync to load contacts" else "Connect the phone first",
                14f, C.SUB
            ).apply { setPadding(0, dp(20), 0, 0) })
            return
        }
        val q = search.text.toString().trim().lowercase()
        val shown = WatchState.contacts.filter {
            q.isEmpty() || it.name.lowercase().contains(q) || it.phone.contains(q)
        }.take(150)
        shown.forEach { c -> list.addView(contactRow(c)) }
        if (shown.size == 150) list.addView(text("Search to see more", 12f, C.SUB).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun contactRow(c: Contact): LinearLayout {
        val open = selected == c
        val box = card {
            selected = if (open) null else c
            render()
        }
        box.addView(hbox().apply {
            addView(avatar(initial(c.name), C.avatarColor(c.name), 34, 15f))
            addView(vbox().apply {
                setPadding(dp(10), 0, 0, 0)
                addView(text(c.name, 15f, bold = true, lines = 1))
                addView(text(c.phone, 12f, C.SUB, lines = 1))
            })
        })
        if (open) {
            box.addView(action("Call on phone", C.GREEN) {
                WatchService.instance?.sendCmd("dial", c.phone)
                toast("Calling ${c.name}…")
                selected = null
                render()
            })
        }
        return box
    }
}

// ================= log =================

class LogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen {
            addView(header("Log"))
            addView(action("Share log", C.BLUE) {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, L.dump())
                }
                startActivity(Intent.createChooser(i, "Share log"))
            })
            addView(text(L.dump().ifEmpty { "Log is empty" }, 10f, C.SUB).apply {
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(10), 0, 0)
            })
        }
    }
}
