package br.com.apois.app

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Aviso flutuante exibido por 3 segundos quando uma chamada é recusada.
 * Precisa da permissão "sobrepor outros apps" (SYSTEM_ALERT_WINDOW).
 */
class BlockOverlayService : Service() {

    private var view: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.getStringExtra("number") ?: ""
        val video = intent?.getBooleanExtra("video", false) ?: false
        show(number, video)
        Handler(Looper.getMainLooper()).postDelayed({
            remove()
            stopSelf()
        }, 3000)
        return START_NOT_STICKY
    }

    private fun show(number: String, video: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            stopSelf()
            return
        }
        remove()

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#1B212B"))
                setStroke(dp(1), Color.parseColor("#2C3543"))
            }
            elevation = dp(10).toFloat()
        }

        val icon = TextView(this).apply {
            text = if (video) "▣" else "☏"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 20f
            setPadding(0, 0, dp(12), 0)
        }

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = if (video) "Chamada de vídeo recusada" else "Chamada de voz recusada"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = if (number.isEmpty()) "Número desconhecido" else number
            setTextColor(Color.parseColor("#94A0B0"))
            textSize = 12f
        })

        card.addView(icon)
        card.addView(texts)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }

        runCatching {
            wm.addView(card, params)
            view = card
        }
    }

    private fun remove() {
        val v = view ?: return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        }
        view = null
    }

    override fun onDestroy() {
        remove()
        super.onDestroy()
    }
}
