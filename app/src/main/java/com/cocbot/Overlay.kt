package com.cocbot

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * İki ayrı pencere: (1) sürüklenebilir Başlat/Durdur butonu (dokunulabilir),
 * (2) tek satır log (NOT_TOUCHABLE: dokunuşları engellemez).
 * Erişilebilirlik overlay tipi kullanıldığı için ayrı overlay izni gerekmez.
 */
class Overlay(private val ctx: Context, private val onToggle: () -> Unit) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val btn = TextView(ctx)
    private val logView = TextView(ctx)
    @Volatile private var lastLog = ""

    private val btnW = dp(96)
    private val btnH = dp(36)

    private val btnLp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(220); y = dp(4) }

    private val logLp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    init {
        btn.width = btnW
        btn.height = btnH
        btn.gravity = Gravity.CENTER
        btn.setTextColor(Color.WHITE)
        btn.textSize = 13f
        paintButton(false)
        btn.text = "▶ Başlat"

        logView.setTextColor(Color.WHITE)
        logView.textSize = 12f
        logView.maxLines = 1
        logView.ellipsize = TextUtils.TruncateAt.END
        logView.maxWidth = dp(380)
        logView.setPadding(dp(8), dp(4), dp(8), dp(4))
        logView.background = GradientDrawable().apply {
            setColor(0xAA000000.toInt()); cornerRadius = dp(8).toFloat()
        }
        logView.text = "Hazır"
        placeLog()

        btn.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY; ox = btnLp.x; oy = btnLp.y; moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt()
                        val dy = (e.rawY - sy).toInt()
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                        if (moved) {
                            btnLp.x = ox + dx; btnLp.y = oy + dy
                            safeUpdate(btn, btnLp)
                            placeLog()
                            safeUpdate(logView, logLp)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onToggle()
                }
                return true
            }
        })
    }

    private fun placeLog() {
        logLp.x = btnLp.x + btnW + dp(6)
        logLp.y = btnLp.y + dp(4)
    }

    private fun safeUpdate(v: View, lp: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    private fun paintButton(running: Boolean) {
        btn.background = GradientDrawable().apply {
            setColor(if (running) 0xE6C62828.toInt() else 0xE62E7D32.toInt())
            cornerRadius = dp(18).toFloat()
        }
    }

    fun show() {
        handler.post {
            try {
                wm.addView(btn, btnLp)
                wm.addView(logView, logLp)
            } catch (_: Exception) {}
        }
    }

    fun hide() {
        handler.post {
            try { wm.removeView(btn) } catch (_: Exception) {}
            try { wm.removeView(logView) } catch (_: Exception) {}
        }
    }

    fun setRunning(running: Boolean) {
        handler.post {
            btn.text = if (running) "■ Durdur" else "▶ Başlat"
            paintButton(running)
        }
    }

    /** Her thread'den çağrılabilir; aynı metin tekrar yazılmaz. */
    fun log(s: String) {
        if (s == lastLog) return
        lastLog = s
        handler.post { logView.text = s }
    }
}
