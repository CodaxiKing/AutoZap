package com.codaxi.pinsave

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * O Android 10+ não deixa apps em segundo plano lerem a área de transferência,
 * então o pop-up é aberto por um toque: uma notificação fixa ou um bloco nas
 * Configurações rápidas. Os dois abrem o PopupActivity, que lê o link copiado.
 */
object Shortcut {
    private const val CHANNEL = "atalho"
    const val ID = 1

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pinsave", Context.MODE_PRIVATE)

    fun isOn(ctx: Context) = prefs(ctx).getBoolean("shortcut", false)

    fun setOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("shortcut", on).apply()
        if (on) show(ctx) else ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    fun popupIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, PopupActivity::class.java)
            .setAction(PopupActivity.ACTION_CHECK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun show(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Atalho para baixar", NotificationManager.IMPORTANCE_LOW)
        )
        nm.notify(ID, notification(ctx))
    }

    fun notification(ctx: Context): Notification {
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Atalho para baixar", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Copiou um link?")
            .setContentText("Pinterest ou Instagram: toque aqui para baixar")
            .setContentIntent(popupIntent(ctx))
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}

/** Recoloca a notificação e o botão flutuante depois que o celular reinicia. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Shortcut.isOn(context)) Shortcut.show(context)
        BubbleService.start(context)
    }
}

/** Bloco "Baixar pin" nas Configurações rápidas. */
class DownloadTile : TileService() {
    override fun onClick() {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(Shortcut.popupIntent(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(
                Intent(this, PopupActivity::class.java)
                    .setAction(PopupActivity.ACTION_CHECK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
