package com.vintechafrica.watchlink.watch

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Dark watch palette. Black background saves power and hides the square corners. */
object C {
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

    private val avatarColors by lazy { listOf(BLUE, GREEN, ORANGE, PURPLE, RED, Color.rgb(100, 210, 255)) }
    fun avatarColor(s: String) = avatarColors[(s.hashCode() and 0x7fffffff) % avatarColors.size]
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: Int, radiusDp: Int = 18) = GradientDrawable().apply {
    cornerRadius = dp(radiusDp).toFloat()
    setColor(color)
}

fun Context.oval(color: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun Context.text(
    t: CharSequence, size: Float = 14f, color: Int = C.TEXT,
    bold: Boolean = false, lines: Int = 0
): TextView = TextView(this).apply {
    text = t
    textSize = size
    setTextColor(color)
    if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
}

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun matchWrap(topDp: Int = 0, ctx: Context) = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
).apply { topMargin = ctx.dp(topDp) }

/** Rounded dark card with optional click. */
fun Context.card(onClick: (() -> Unit)? = null): LinearLayout = vbox().apply {
    background = rounded(C.CARD)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    layoutParams = matchWrap(8, this@card)
    if (onClick != null) {
        isClickable = true
        setOnClickListener { onClick() }
    }
}

/** Colored circle with a letter or emoji inside. */
fun Context.avatar(label: String, color: Int, sizeDp: Int = 40, textSp: Float = 18f): TextView =
    text(label, textSp, C.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        background = oval(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

fun initial(s: String): String = s.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

/** Big menu row: icon circle, title, subtitle. */
fun Context.tile(icon: String, title: String, sub: String?, accent: Int, onClick: () -> Unit): LinearLayout {
    val row = hbox().apply {
        background = rounded(C.CARD)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = matchWrap(8, this@tile)
        isClickable = true
        setOnClickListener { onClick() }
    }
    row.addView(avatar(icon, accent, 38, 17f))
    val col = vbox().apply {
        setPadding(dp(12), 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    col.addView(text(title, 16f, bold = true, lines = 1))
    if (sub != null) col.addView(text(sub, 12f, C.SUB, lines = 1))
    row.addView(col)
    return row
}

/** Small colored label. */
fun Context.pill(t: String, color: Int): TextView = text(t, 12f, C.TEXT, bold = true).apply {
    background = rounded(color, 12)
    setPadding(dp(10), dp(4), dp(10), dp(4))
}

/** Full-width rounded action button. */
fun Context.action(label: String, color: Int, onClick: () -> Unit): TextView =
    text(label, 16f, C.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(color, 24)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(8)
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

/** Round call button with a label under it. */
fun Context.roundAction(icon: String, label: String, color: Int, onClick: () -> Unit): LinearLayout {
    val col = vbox().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    col.addView(text(icon, 28f).apply {
        gravity = Gravity.CENTER
        background = oval(color)
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        isClickable = true
        setOnClickListener { onClick() }
    })
    col.addView(text(label, 12f, C.SUB).apply { setPadding(0, dp(4), 0, 0) })
    return col
}

/** Standard screen: black, padded for the rounded display, scrollable. */
fun Activity.screen(build: LinearLayout.() -> Unit): LinearLayout {
    val col = vbox().apply {
        setPadding(dp(16), dp(10), dp(16), dp(28))
        build()
    }
    setContentView(ScrollView(this).apply {
        setBackgroundColor(C.BG)
        isVerticalScrollBarEnabled = false
        addView(col)
    })
    return col
}

fun Context.header(title: String, right: View? = null): LinearLayout = hbox().apply {
    setPadding(0, dp(4), 0, dp(4))
    addView(text(title, 20f, bold = true).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    if (right != null) addView(right)
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

fun timeAgo(ms: Long): String {
    val d = (System.currentTimeMillis() - ms) / 1000
    return when {
        d < 60 -> "now"
        d < 3600 -> "${d / 60}m"
        d < 86_400 -> "${d / 3600}h"
        else -> "${d / 86_400}d"
    }
}

/** Light the screen for a popup or call, even from standby. */
@Suppress("DEPRECATION")
fun Context.wakeScreen(ms: Long = 10_000) {
    try {
        val pm = getSystemService(PowerManager::class.java)
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "watchlink:wake"
        ).acquire(ms)
    } catch (e: Exception) {
        L.i("Wake failed: ${e.message}")
    }
}

/** Watch-side settings. */
object WPrefs {
    private fun p(c: Context) = c.getSharedPreferences("watch", Context.MODE_PRIVATE)
    fun popups(c: Context) = p(c).getBoolean("popups", true)
    fun setPopups(c: Context, on: Boolean) = p(c).edit().putBoolean("popups", on).apply()
    fun muted(c: Context): Set<String> = p(c).getStringSet("muted", emptySet())?.toSet() ?: emptySet()
    fun isMuted(c: Context, app: String) = app in muted(c)
    fun toggleMute(c: Context, app: String): Boolean {
        val s = muted(c).toMutableSet()
        val nowMuted = if (app in s) { s.remove(app); false } else { s.add(app); true }
        p(c).edit().putStringSet("muted", s).apply()
        return nowMuted
    }
}
