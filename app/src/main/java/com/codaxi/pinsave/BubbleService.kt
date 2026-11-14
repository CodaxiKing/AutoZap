package com.codaxi.pinsave

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs

/**
 * Botão flutuante sobre os outros apps. Copiou o link no Pinterest, toca na
 * bolinha e o download começa. Dá para arrastar para qualquer canto.
 */
class BubbleService : Service() {
    private var bubble: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = Shortcut.notification(this)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Shortcut.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Shortcut.ID, n)
            }
        } catch (e: Exception) {
            // sem permissão para ficar em primeiro plano: a bolinha some se o sistema encerrar o app
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
        } else if (bubble == null) {
            addBubble()
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val wm = getSystemService(WindowManager::class.java) ?: return
        val density = resources.displayMetrics.density
        val size = (52 * density).toInt()
        val prefs = getSharedPreferences("pinsave", Context.MODE_PRIVATE)

        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_stat)
            val pad = (13 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF5A5F"))
                setStroke((2 * density).toInt(), Color.WHITE)
            }
            alpha = 0.9f
            contentDescription = "Baixar link copiado"
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubble_x", resources.displayMetrics.widthPixels - size - (8 * density).toInt())
            y = prefs.getInt("bubble_y", resources.displayMetrics.heightPixels / 3)
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    v.alpha = 0.6f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        val dm = resources.displayMetrics
                        lp.x = (startX + dx).coerceIn(0, maxOf(0, dm.widthPixels - size))
                        lp.y = (startY + dy).coerceIn(0, maxOf(0, dm.heightPixels - size))
                        try {
                            wm.updateViewLayout(v, lp)
                        } catch (ex: Exception) {
                            // a janela já foi removida
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 0.9f
                    if (moved) {
                        prefs.edit().putInt("bubble_x", lp.x).putInt("bubble_y", lp.y).apply()
                    } else {
                        openPopup()
                    }
                }
                MotionEvent.ACTION_CANCEL -> v.alpha = 0.9f
            }
            true
        }
        try {
            wm.addView(view, lp)
            bubble = view
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun openPopup() {
        try {
            startActivity(
                Intent(this, PopupActivity::class.java)
                    .setAction(PopupActivity.ACTION_CHECK)
                    .putExtra(PopupActivity.EXTRA_DIRECT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // o sistema não deixou abrir a tela agora
        }
    }

    override fun onDestroy() {
        bubble?.let {
            try {
                getSystemService(WindowManager::class.java)?.removeView(it)
            } catch (e: Exception) {
                // já removida
            }
        }
        bubble = null
        // a notificação do atalho é a mesma do serviço; recoloca se o atalho segue ligado
        val app = applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ if (Shortcut.isOn(app)) Shortcut.show(app) }, 500)
        super.onDestroy()
    }

    companion object {
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("pinsave", Context.MODE_PRIVATE)

        fun isOn(ctx: Context) = prefs(ctx).getBoolean("bubble", false) && Settings.canDrawOverlays(ctx)

        fun setOn(ctx: Context, on: Boolean) {
            prefs(ctx).edit().putBoolean("bubble", on).apply()
            if (on) start(ctx) else ctx.stopService(Intent(ctx, BubbleService::class.java))
        }

        /** Mostra a bolinha se ela está ligada e a permissão foi dada. */
        fun start(ctx: Context) {
            if (!isOn(ctx)) return
            try {
                ctx.startForegroundService(Intent(ctx, BubbleService::class.java))
            } catch (e: Exception) {
                // o sistema não deixou iniciar agora; volta quando o app for aberto
            }
        }
    }
}
