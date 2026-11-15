package com.codaxi.pinsave

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Lembretes diários de postagem. Nos horários escolhidos o app avisa e, se
 * houver algo baixado ou criado que ainda não foi postado, abre direto nele.
 */
object Reminders {
    private const val CHANNEL = "lembrete"
    private const val ID = 3

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pinsave", Context.MODE_PRIVATE)

    /** Horários no formato "HH:mm", em ordem. */
    fun times(ctx: Context): List<String> =
        (prefs(ctx).getStringSet("reminders", null) ?: emptySet()).sorted()

    fun add(ctx: Context, hour: Int, minute: Int) = save(ctx, times(ctx) + "%02d:%02d".format(hour, minute))

    fun remove(ctx: Context, time: String) = save(ctx, times(ctx) - time)

    private fun save(ctx: Context, times: List<String>) {
        prefs(ctx).edit().putStringSet("reminders", HashSet(times)).apply()
        schedule(ctx)
    }

    /** Próximo disparo depois de [now], em milissegundos, ou null se não há horários. */
    fun nextTrigger(times: List<String>, now: Long): Long? = times.mapNotNull { time ->
        val hour = time.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
        val minute = time.substringAfter(':').toIntOrNull() ?: return@mapNotNull null
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // um horário que acabou de passar (ou de disparar) fica para amanhã
        if (cal.timeInMillis <= now + 30_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.timeInMillis
    }.minOrNull()

    /** Agenda o próximo lembrete. O Android pode atrasar alguns minutos para poupar bateria. */
    fun schedule(ctx: Context) {
        val alarms = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, ReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        alarms.cancel(pending)
        val next = nextTrigger(times(ctx), System.currentTimeMillis()) ?: return
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        } catch (e: Exception) {
            // sem permissão para agendar agora; tenta de novo quando o app abrir
        }
    }

    fun notify(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Lembretes de postagem", NotificationManager.IMPORTANCE_HIGH)
        )
        val waiting = Library.list(ctx).filter { Posted.get(ctx, it.uri).isEmpty() }
        val text: String
        val open: Intent
        if (waiting.isEmpty()) {
            text = "Não há nada pronto na fila. Toque para criar um vídeo."
            open = Intent(ctx, GeneratorActivity::class.java)
        } else {
            text = if (waiting.size == 1) "1 item pronto para postar. Toque para abrir."
            else "${waiting.size} itens prontos. Toque para postar o próximo."
            // o mais antigo primeiro
            open = Intent(ctx, PopupActivity::class.java)
                .setAction(PopupActivity.ACTION_DONE)
                .putParcelableArrayListExtra(PopupActivity.EXTRA_URIS, arrayListOf(waiting.last().uri))
                .putExtra(PopupActivity.EXTRA_TITLE, "Hora de postar")
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        nm.notify(
            ID,
            Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Hora de postar")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        ctx, 50, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .build()
        )
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Reminders.notify(context)
        Reminders.schedule(context)
    }
}
