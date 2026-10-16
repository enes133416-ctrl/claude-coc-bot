package com.cocbot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        status = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER }
        toggle = Button(this).apply {
            textSize = 18f
            setOnClickListener {
                val s = BotService.instance
                if (s == null) {
                    Toast.makeText(this@MainActivity, "Önce erişilebilirlik servisini aç", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    s.toggle()
                    refresh()
                }
            }
        }
        val settings = Button(this).apply {
            text = "Erişilebilirlik Ayarları"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(status)
        root.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        root.addView(settings, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val s = BotService.instance
        status.text = when {
            s == null -> "Servis kapalı: Erişilebilirlik ayarlarından \"CoC Bot\" servisini aç."
            s.running -> "Bot çalışıyor"
            else -> "Servis açık, bot durdu"
        }
        toggle.text = if (s != null && s.running) "Durdur" else "Başlat"
    }
}
