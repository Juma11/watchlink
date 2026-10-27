package com.vintechafrica.watchlink.watch

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject

private fun music(a: String) {
    WatchService.instance?.send(JSONObject().put("t", "media_cmd").put("a", a))
}

/** Now playing on the phone: title, artist, previous / play-pause / next, volume. */
class MusicActivity : Activity() {
    private lateinit var body: LinearLayout
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = screen {}
    }

    override fun onResume() {
        super.onResume()
        WatchState.addListener(listener)
        WatchService.instance?.send(JSONObject().put("t", "media_get"))
        render()
    }

    override fun onPause() {
        WatchState.removeListener(listener)
        super.onPause()
    }

    private fun render() {
        body.removeAllViews()
        val m = WatchState.media
        body.gravity = Gravity.CENTER_HORIZONTAL

        if (!WatchState.connected) {
            body.addView(header("Music"))
            body.addView(text("Phone not connected", 14f, C.SUB).apply { setPadding(0, dp(20), 0, 0) })
            return
        }

        body.addView(text(if (m.has && m.app.isNotEmpty()) m.app else "Music", 13f, C.SUB, bold = true).apply {
            setPadding(0, dp(4), 0, 0)
        })
        body.addView(avatar(if (m.playing) "♪" else "❚❚", C.PURPLE, 64, 24f).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        })
        body.addView(text(
            if (m.has && m.title.isNotEmpty()) m.title else "Nothing playing",
            18f, bold = true, lines = 2
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        if (m.artist.isNotEmpty()) {
            body.addView(text(m.artist, 14f, C.SUB, lines = 1).apply { gravity = Gravity.CENTER })
        }
        if (!m.has) {
            body.addView(text("Start Spotify, YouTube Music or Boomplay on the phone. Play still tries the last app.",
                12f, C.SUB).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
        }

        body.addView(hbox().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) }
            addView(smallRound("⏮") { music("prev") })
            addView(roundAction(if (m.playing) "❚❚" else "▶", if (m.playing) "Pause" else "Play", C.PURPLE) {
                music("toggle")
            })
            addView(smallRound("⏭") { music("next") })
        })

        body.addView(hbox().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
            gravity = Gravity.CENTER
            addView(pill("  −  ", C.CARD2).apply {
                textSize = 18f
                isClickable = true
                setOnClickListener { music("vol_down") }
            })
            addView(text("  Volume ${m.vol}/${m.volMax}  ", 13f, C.SUB))
            addView(pill("  +  ", C.CARD2).apply {
                textSize = 18f
                isClickable = true
                setOnClickListener { music("vol_up") }
            })
        })
    }

    private fun Context.smallRound(icon: String, onClick: () -> Unit) = vbox().apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(text(icon, 20f).apply {
            gravity = Gravity.CENTER
            background = oval(C.CARD2)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            isClickable = true
            setOnClickListener { onClick() }
        })
    }
}

/** Quick replies or a typed answer, sent through the app's own Reply button on the phone. */
class ReplyActivity : Activity() {
    private var key = ""
    private lateinit var body: LinearLayout
    private val listener: () -> Unit = { showResult() }

    private val quick = listOf(
        "OK 👍", "On my way", "I'll call you back", "Busy now, talk later",
        "Yes", "No", "Thanks!", "Niko njiani", "Nitakupigia baadaye"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = intent.getStringExtra("key").orEmpty()
        val n = WatchState.notifList().firstOrNull { it.key == key }
        if (n == null) {
            finish()
            return
        }
        WatchState.lastReply = null
        body = screen {
            addView(text("Reply to ${n.title.ifEmpty { n.app }}", 16f, bold = true, lines = 1))
            addView(text(n.text, 12f, C.SUB, lines = 2).apply { setPadding(0, dp(2), 0, dp(6)) })

            val input = EditText(this@ReplyActivity).apply {
                hint = "Type a reply"
                textSize = 15f
                setTextColor(C.TEXT)
                setHintTextColor(C.SUB)
                background = rounded(C.CARD, 20)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                imeOptions = EditorInfo.IME_ACTION_SEND
                setSingleLine()
                setOnEditorActionListener { v, _, _ ->
                    send(v.text.toString())
                    true
                }
            }
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(action("Send", C.BLUE) { send(input.text.toString()) })

            addView(text("Quick replies", 12f, C.SUB, bold = true).apply { setPadding(0, dp(10), 0, 0) })
            quick.forEach { q ->
                addView(card { send(q) }.apply {
                    addView(text(q, 15f))
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WatchState.addListener(listener)
    }

    override fun onPause() {
        WatchState.removeListener(listener)
        super.onPause()
    }

    private fun send(t: String) {
        val msg = t.trim()
        if (msg.isEmpty()) return
        if (!WatchState.connected) {
            toast("Phone not connected")
            return
        }
        WatchService.instance?.send(JSONObject().put("t", "reply").put("key", key).put("text", msg))
        toast("Sending…")
    }

    private fun showResult() {
        val r = WatchState.lastReply ?: return
        WatchState.lastReply = null
        toast(r)
        if (r == "Sent") finish()
    }
}
