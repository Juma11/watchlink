package com.vintechafrica.watchlink.watch

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * Phone-style heads-up banner: slides in at the top over whatever is on screen,
 * hides after 5 seconds, swipe up to close, tap to open.
 * Needs "Display over other apps". When the watch is locked or the screen is off,
 * a lock-screen card is shown instead, because banners cannot draw over the lock screen.
 */
object Alerts {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val hide = Runnable { remove(animated = true) }

    fun canBanner(c: Context) = Settings.canDrawOverlays(c)

    fun show(c: Context, n: Notif) {
        main.post {
            val km = c.getSystemService(KeyguardManager::class.java)
            val pm = c.getSystemService(PowerManager::class.java)
            val lockedOrOff = km.isKeyguardLocked || !pm.isInteractive
            if (!lockedOrOff && canBanner(c)) {
                banner(c.applicationContext, n)
            } else {
                c.startActivity(
                    Intent(c, NotifPopupActivity::class.java)
                        .putExtra("key", n.key)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun banner(c: Context, n: Notif) {
        remove(animated = false)
        val wm = c.getSystemService(WindowManager::class.java)

        val card = c.hbox().apply {
            background = c.rounded(C.CARD2, 22)
            setPadding(c.dp(12), c.dp(10), c.dp(14), c.dp(10))
            elevation = c.dp(6).toFloat()
        }
        card.addView(c.avatar(initial(n.app), C.avatarColor(n.app), 34, 15f))
        card.addView(c.vbox().apply {
            setPadding(c.dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(c.text("${n.app} · now", 11f, C.SUB, lines = 1))
            if (n.title.isNotEmpty()) addView(c.text(n.title, 14f, bold = true, lines = 1))
            addView(c.text(n.text, 13f, C.TEXT, lines = 2))
        })

        val frame = LinearLayout(c).apply {
            setPadding(c.dp(8), c.dp(6), c.dp(8), 0)
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        var downY = 0f
        var moved = false
        frame.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    moved = false
                    main.removeCallbacks(hide)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (dy < -8) {
                        moved = true
                        v.translationY = dy
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved && e.rawY - downY < -40) {
                        remove(animated = true)
                    } else if (!moved) {
                        remove(animated = false)
                        c.startActivity(
                            Intent(c, NotifDetailActivity::class.java)
                                .putExtra("key", n.key)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } else {
                        v.animate().translationY(0f).setDuration(150).start()
                        main.postDelayed(hide, 3_000)
                    }
                }
            }
            true
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }

        try {
            wm.addView(frame, lp)
            view = frame
            frame.translationY = -c.dp(120).toFloat()
            frame.animate().translationY(0f).setDuration(220).start()
            main.postDelayed(hide, 5_000)
        } catch (e: Exception) {
            L.i("Banner failed: ${e.message}")
        }
    }

    fun remove(animated: Boolean) {
        main.removeCallbacks(hide)
        val v = view ?: return
        view = null
        val wm = v.context.getSystemService(WindowManager::class.java)
        val drop = { try { wm.removeView(v) } catch (_: Exception) {} }
        if (animated) {
            v.animate().translationY(-v.height.toFloat() - 20f).setDuration(180).withEndAction { drop() }.start()
        } else {
            drop()
        }
    }
}
