package com.codaxi.pinsave

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * O Android 10+ não deixa apps em segundo plano lerem a área de transferência.
 * Este serviço só percebe o gesto de "Copiar link" dentro do Pinterest e abre
 * o PopupActivity, que (estando em foco) consegue ler o link copiado.
 */
class CopyWatchService : AccessibilityService() {
    private var lastPinterestEvent = 0L
    private var lastTrigger = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        val now = SystemClock.elapsedRealtime()
        val type = event.eventType

        val hit = when (pkg) {
            PINTEREST -> {
                lastPinterestEvent = now
                type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && COPY.containsMatchIn(textOf(event))
            }
            // Android 13+: aviso do sistema "copiado" logo após copiar no Pinterest.
            SYSTEM_UI -> now - lastPinterestEvent < 4000 &&
                (event.className?.toString()?.contains("clipboard", ignoreCase = true) == true ||
                    COPY.containsMatchIn(textOf(event)))
            else -> false
        }
        if (!hit || now - lastTrigger < 1500) return
        lastTrigger = now
        try {
            startActivity(
                Intent(this, PopupActivity::class.java)
                    .setAction(PopupActivity.ACTION_CHECK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
            // sem permissão para abrir a tela agora; o usuário ainda pode abrir o app
        }
    }

    private fun textOf(e: AccessibilityEvent): String =
        e.text.joinToString(" ") + " " + (e.contentDescription ?: "")

    override fun onInterrupt() {}

    companion object {
        private const val PINTEREST = "com.pinterest"
        private const val SYSTEM_UI = "com.android.systemui"
        private val COPY = Regex("cop(y|ied|iar|iado|iada)", RegexOption.IGNORE_CASE)

        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(ctx, CopyWatchService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
