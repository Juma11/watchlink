package com.vintechafrica.watchlink.watch

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

// ---------- small UI helpers, sized for a 368x448 screen ----------

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.bigButton(label: String, color: Int = Color.parseColor("#333333"), onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(6)
        }
        setOnClickListener { onClick() }
    }

fun Context.label(t: String, size: Float = 14f, bold: Boolean = false): TextView = TextView(this).apply {
    text = t
    textSize = size
    setTextColor(Color.WHITE)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

fun Activity.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(14), dp(14), dp(14), dp(24))
    setBackgroundColor(Color.BLACK)
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

val GREEN = Color.parseColor("#1E8E3E")
val RED = Color.parseColor("#C5221F")

// ---------- home ----------

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var notifBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchService.start(this)
        val col = column()
        col.addView(label("WatchLink", 20f, bold = true))
        status = label("")
        status.setPadding(0, dp(6), 0, dp(6))
        col.addView(status)
        notifBtn = bigButton("Notifications") { startActivity(Intent(this, NotifActivity::class.java)) }
        col.addView(notifBtn)
        col.addView(bigButton("Contacts") { startActivity(Intent(this, ContactsActivity::class.java)) })
        col.addView(bigButton("Battery saver") { startActivity(Intent(this, BatteryActivity::class.java)) })
        col.addView(bigButton("Log") { startActivity(Intent(this, LogActivity::class.java)) })
        col.addView(bigButton("Bluetooth test") { startActivity(Intent(this, BleTestActivity::class.java)) })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
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
        val b = Bat.read(this)
        status.text = "${WatchService.status}\nBattery ${b.pct}%  ${"%.1f".format(b.tempC)}°C" +
            (if (b.charging) "  charging" else "") +
            (if (b.tempC >= 42f) "\nHOT: let it cool" else "")
        notifBtn.text = "Notifications (${WatchState.notifCount()})"
    }
}

// ---------- incoming / ongoing call ----------

class CallActivity : Activity() {
    private lateinit var who: TextView
    private lateinit var state: TextView
    private lateinit var answer: Button
    private lateinit var reject: Button
    private lateinit var silence: Button
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val col = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        state = label("Incoming call", 14f)
        who = label("", 24f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(14))
        }
        answer = bigButton("Answer", GREEN) { WatchService.instance?.sendCmd("answer") }
        reject = bigButton("Reject", RED) {
            WatchService.instance?.sendCmd(if (WatchState.callState == "offhook") "hangup" else "reject")
        }
        silence = bigButton("Silence") {
            WatchService.instance?.stopRinging()
            WatchService.instance?.sendCmd("silence")
        }
        col.addView(state)
        col.addView(who)
        col.addView(answer)
        col.addView(reject)
        col.addView(silence)
        setContentView(col)
        WatchState.addListener(listener)
        render()
    }

    override fun onDestroy() {
        WatchState.removeListener(listener)
        super.onDestroy()
    }

    private fun render() {
        who.text = WatchState.callerLabel()
        when (WatchState.callState) {
            "ringing" -> {
                state.text = "Incoming call"
                answer.visibility = View.VISIBLE
                silence.visibility = View.VISIBLE
                reject.text = "Reject"
            }
            "offhook" -> {
                state.text = "On call · talk on your phone or earbuds"
                answer.visibility = View.GONE
                silence.visibility = View.GONE
                reject.text = "Hang up"
            }
            else -> finish()
        }
    }
}

// ---------- notifications ----------

class NotifActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var items: List<Notif> = emptyList()
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = column()
        col.addView(label("Notifications", 18f, bold = true))
        empty = label("No notifications").apply { setPadding(0, dp(10), 0, 0) }
        col.addView(empty)
        list = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnItemClickListener { _, _, pos, _ -> open(items[pos]) }
        }
        col.addView(list)
        setContentView(col)
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
        items = WatchState.notifList()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val rows = items.map { "${it.app} · ${it.title}\n${it.text.take(70)}" }
        list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE)
                    textSize = 13f
                }
        }
    }

    private fun open(n: Notif) {
        AlertDialog.Builder(this)
            .setTitle("${n.app}: ${n.title}")
            .setMessage(n.text)
            .setPositiveButton("Dismiss") { _, _ ->
                WatchService.instance?.send(JSONObject().put("t", "notif_dismiss").put("key", n.key))
                WatchService.instance?.cancelMirror(n.key)
                WatchState.removeNotif(n.key)
                render()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}

// ---------- contacts ----------

class ContactsActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var search: EditText
    private var shown: List<Contact> = emptyList()
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = column()
        col.addView(label("Contacts", 18f, bold = true))
        search = EditText(this).apply {
            hint = "Search"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        col.addView(search)
        col.addView(bigButton("Refresh from phone") {
            WatchService.instance?.send(JSONObject().put("t", "get_contacts"))
            toast("Asking phone…")
        })
        list = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnItemClickListener { _, _, pos, _ -> confirmCall(shown[pos]) }
        }
        col.addView(list)
        setContentView(col)
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
        val q = search.text.toString().trim().lowercase()
        shown = WatchState.contacts.filter {
            q.isEmpty() || it.name.lowercase().contains(q) || it.phone.contains(q)
        }
        val rows = shown.map { "${it.name}\n${it.phone}" }
        list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE)
                    textSize = 14f
                }
        }
    }

    private fun confirmCall(c: Contact) {
        AlertDialog.Builder(this)
            .setTitle("Call ${c.name}?")
            .setMessage("Your phone places the call. Talk on the phone or earbuds.")
            .setPositiveButton("Call") { _, _ ->
                WatchService.instance?.sendCmd("dial", c.phone)
                toast("Calling on phone…")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

// ---------- log ----------

class LogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = column()
        col.addView(bigButton("Share log") {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, L.dump())
            }
            startActivity(Intent.createChooser(i, "Share log"))
        })
        col.addView(label(L.dump().ifEmpty { "Log is empty" }, 11f).apply { typeface = Typeface.MONOSPACE })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
    }
}
