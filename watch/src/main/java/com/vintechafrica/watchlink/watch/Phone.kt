package com.vintechafrica.watchlink.watch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Keypad for numbers that are not in contacts. The phone places the call.
 * A comma waits 2 s then sends the digits after it, for menus such as "press 1".
 */
class DialerActivity : Activity() {
    private lateinit var display: TextView
    private var number = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("num")?.let { number.append(it) }

        val root = vbox().apply {
            setBackgroundColor(C.BG)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        display = text("", 24f, bold = true, lines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        }
        root.addView(display, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("*", "0", "#")
        )
        keys.forEach { row ->
            root.addView(hbox().apply {
                row.forEach { k -> addView(key(k), LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        root.addView(hbox().apply {
            addView(small("+ ,") {
                // Long list of symbols is hard on a watch: cycle + then , (pause).
                when {
                    number.isEmpty() -> number.append("+")
                    else -> number.append(",")
                }
                show()
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            addView(text("✆", 24f).apply {
                gravity = Gravity.CENTER
                background = oval(C.GREEN)
                isClickable = true
                setOnClickListener { call() }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            addView(small("⌫") {
                if (number.isNotEmpty()) number.setLength(number.length - 1)
                show()
            }.apply {
                setOnLongClickListener {
                    number.setLength(0)
                    show()
                    true
                }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(C.BG)
            addView(root)
        })
        show()
    }

    private fun key(k: String) = text(k, 22f, bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(C.CARD, 16)
        isClickable = true
        setOnClickListener {
            if (number.length < 40) number.append(k)
            show()
        }
    }

    private fun small(label: String, onClick: () -> Unit) = text(label, 17f, C.SUB, bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(C.CARD2, 16)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun show() {
        display.text = if (number.isEmpty()) "Enter number" else number.toString()
        display.setTextColor(if (number.isEmpty()) C.SUB else C.TEXT)
    }

    private fun call() {
        val n = number.toString()
        if (n.isEmpty()) return
        if (!WatchState.connected) {
            toast("Phone not connected")
            return
        }
        if (n.startsWith("*") || (n.startsWith("#") && n.endsWith("#"))) {
            startActivity(Intent(this, UssdActivity::class.java).putExtra("code", n))
            return
        }
        WatchService.instance?.sendCmd("dial", n)
        toast("Calling on phone…")
        finish()
    }
}

/** Find phone, USSD, keypad and battery in one place, to keep the home screen short. */
class ToolsActivity : Activity() {
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
        body.addView(header("Tools"))
        val finding = WatchState.findingPhone
        body.addView(tile("📳", if (finding) "Stop ringing phone" else "Find phone",
            if (finding) "Phone is ringing" else "Ring the phone, even on silent",
            if (finding) C.RED else C.BLUE) {
            if (!WatchState.connected) {
                toast("Phone not connected")
                return@tile
            }
            WatchService.instance?.send(JSONObject().put("t", if (finding) "find_stop" else "find"))
        })
        body.addView(tile("⌨", "Keypad", "Call a number not in contacts", C.GREEN) {
            startActivity(Intent(this, DialerActivity::class.java))
        })
        body.addView(tile("#", "USSD", "Balance and info codes", C.PURPLE) {
            startActivity(Intent(this, UssdActivity::class.java))
        })
        val b = Bat.read(this)
        body.addView(tile("🔋", "Battery", "${b.pct}% · ${"%.0f".format(b.tempC)}°C", C.ORANGE) {
            startActivity(Intent(this, BatteryActivity::class.java))
        })
    }
}

/**
 * One-shot USSD on the phone's SIM 1 or SIM 2, answer shown here.
 * Menu codes only show their first screen; finish those on the phone.
 */
class UssdActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var input: EditText
    private val listener: () -> Unit = { renderResult() }
    private lateinit var resultBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchService.instance?.send(JSONObject().put("t", "get_sims"))
        WatchState.ussd = null
        body = screen {
            addView(header("USSD"))
            if (!WatchState.connected) {
                addView(text("Phone not connected", 13f, C.ORANGE))
            }

            // SIM choice
            val sims = WatchState.sims
            if (sims.size > 1) {
                addView(hbox().apply {
                    setPadding(0, dp(4), 0, dp(4))
                    sims.forEach { s ->
                        val on = WPrefs.ussdSim(this@UssdActivity) == s.slot
                        addView(pill("SIM ${s.slot} ${s.name}".trim(), if (on) C.BLUE else C.CARD2).apply {
                            isClickable = true
                            setOnClickListener {
                                WPrefs.setUssdSim(this@UssdActivity, s.slot)
                                recreate()
                            }
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { rightMargin = dp(6) })
                    }
                })
            }

            input = EditText(this@UssdActivity).apply {
                hint = "*144#"
                textSize = 18f
                setTextColor(C.TEXT)
                setHintTextColor(C.SUB)
                inputType = InputType.TYPE_CLASS_PHONE
                background = rounded(C.CARD, 20)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setText(intent.getStringExtra("code").orEmpty())
            }
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) })
            addView(action("Send", C.BLUE) { send(input.text.toString()) })

            resultBox = vbox()
            addView(resultBox)

            val hist = WPrefs.ussdHistory(this@UssdActivity)
            if (hist.isNotEmpty()) {
                addView(text("Recent", 12f, C.SUB, bold = true).apply { setPadding(0, dp(10), 0, 0) })
                hist.forEach { h -> addView(card { send(h) }.apply { addView(text(h, 15f)) }) }
            }
            addView(text("Menus like *334# show only the first screen here. Finish them on the phone or the M-PESA app.",
                11f, C.SUB).apply { setPadding(0, dp(10), 0, 0) })
        }
        intent.getStringExtra("code")?.let { if (it.isNotEmpty()) send(it) }
    }

    override fun onResume() {
        super.onResume()
        WatchState.addListener(listener)
        renderResult()
    }

    override fun onPause() {
        WatchState.removeListener(listener)
        super.onPause()
    }

    private fun send(code: String) {
        val c = code.trim()
        if (c.isEmpty()) return
        if (!WatchState.connected) {
            toast("Phone not connected")
            return
        }
        input.setText(c)
        WPrefs.addUssd(this, c)
        WatchState.ussd = null
        WatchState.ussdBusy = true
        WatchService.instance?.send(JSONObject().put("t", "ussd").put("code", c).put("sim", WPrefs.ussdSim(this)))
        renderResult()
    }

    private fun renderResult() {
        if (!::resultBox.isInitialized) return
        resultBox.removeAllViews()
        val r = WatchState.ussd
        when {
            WatchState.ussdBusy -> resultBox.addView(card().apply {
                addView(text("Waiting for the network…", 14f, C.SUB))
            })
            r != null -> resultBox.addView(card().apply {
                if (!r.ok) background = rounded(C.CARD2, 18)
                addView(text(r.code, 12f, if (r.ok) C.GREEN else C.ORANGE, bold = true))
                addView(text(r.text, 15f).apply { setPadding(0, dp(4), 0, 0) })
            })
        }
    }
}

/** Call history from the phone. Tap an entry to call back. */
class RecentsActivity : Activity() {
    private lateinit var body: LinearLayout
    private var selected: CallEntry? = null
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = screen {}
    }

    override fun onResume() {
        super.onResume()
        WatchState.addListener(listener)
        WatchService.instance?.send(JSONObject().put("t", "get_calls"))
        render()
    }

    override fun onPause() {
        WatchState.removeListener(listener)
        super.onPause()
    }

    private fun render() {
        body.removeAllViews()
        body.addView(header("Recent calls"))
        val list = WatchState.calls
        if (list.isEmpty()) {
            body.addView(text(if (WatchState.connected) "Loading…" else "Phone not connected", 14f, C.SUB)
                .apply { setPadding(0, dp(20), 0, 0) })
            return
        }
        list.forEach { c -> body.addView(row(c)) }
    }

    private fun row(c: CallEntry): LinearLayout {
        val (icon, color, label) = when (c.type) {
            "in" -> Triple("↙", C.GREEN, "Incoming")
            "out" -> Triple("↗", C.BLUE, "Outgoing")
            "missed" -> Triple("↙", C.RED, "Missed")
            "rejected" -> Triple("✕", C.ORANGE, "Rejected")
            "blocked" -> Triple("⊘", C.SUB, "Blocked")
            else -> Triple("•", C.SUB, "Call")
        }
        val who = c.name.ifEmpty { c.phone.ifEmpty { "Private number" } }
        val open = selected == c
        val box = card {
            selected = if (open) null else c
            render()
        }
        box.addView(hbox().apply {
            addView(text(icon, 18f, color, bold = true).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            })
            addView(vbox().apply {
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(text(who, 15f, if (c.type == "missed") C.RED else C.TEXT, bold = true, lines = 1))
                val dur = if (c.secs > 0) " · ${c.secs / 60}:${"%02d".format(c.secs % 60)}" else ""
                addView(text("$label$dur", 12f, C.SUB, lines = 1))
            })
            addView(text(timeAgo(c.date), 12f, C.SUB))
        })
        if (open && c.phone.isNotEmpty()) {
            if (c.name.isNotEmpty()) box.addView(text(c.phone, 12f, C.SUB).apply { setPadding(dp(34), dp(2), 0, 0) })
            box.addView(action("Call back", C.GREEN) {
                if (!WatchState.connected) {
                    toast("Phone not connected")
                    return@action
                }
                WatchService.instance?.sendCmd("dial", c.phone)
                toast("Calling on phone…")
                selected = null
                render()
            })
        }
        return box
    }
}
