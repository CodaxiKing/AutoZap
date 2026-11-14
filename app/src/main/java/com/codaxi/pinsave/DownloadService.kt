package com.codaxi.pinsave

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Fila de downloads. Baixa um pin por vez, em segundo plano, e continua
 * mesmo com o pop-up fechado. Quem estiver acompanhando um link recebe o
 * resultado pelo [Listener]; senão, o resultado vira uma notificação.
 */
class DownloadService : Service() {

    interface Listener {
        fun onProgress(text: String, pct: Int)
        fun onDone(uris: List<Uri>)
        fun onFail(message: String)
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Downloads em andamento", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Downloads concluídos", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(
                PROGRESS_ID, progressNotification(status ?: "Preparando", -1),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: Exception) {
            // o sistema não deixou iniciar agora; a fila continua enquanto o app estiver aberto
        }
        if (!running) {
            running = true
            next()
        }
        return START_NOT_STICKY
    }

    private fun next() {
        val link = queue.pollFirst()
        if (link == null) {
            running = false
            current = null
            total = 0
            finished = 0
            status = null
            observer?.invoke(null, false)
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
            return
        }
        current = link
        val app = applicationContext
        worker.execute {
            try {
                val uris = Library.download(app, link) { p -> main.post { progress(link, p) } }
                main.post { done(link, uris) }
            } catch (e: Exception) {
                val message = friendly(e)
                main.post { failed(link, message) }
            }
        }
    }

    private fun progress(link: String, p: Progress) {
        var text = if (total > 1) "Baixando ${finished + 1} de $total" else "Baixando"
        if (p.count > 1) text += " (arquivo ${p.index + 1}/${p.count})"
        status = text
        listeners[link]?.onProgress(text, p.pct)

        val now = SystemClock.elapsedRealtime()
        if (now - lastNotify < 500) return
        lastNotify = now
        getSystemService(NotificationManager::class.java)?.notify(PROGRESS_ID, progressNotification(text, p.pct))
        observer?.invoke(if (p.pct >= 0) "$text… ${p.pct}%" else "$text…", false)
    }

    private fun done(link: String, uris: List<Uri>) {
        finished++
        val listener = listeners.remove(link)
        if (listener != null) listener.onDone(uris) else notifyDone(uris)
        observer?.invoke(status, true)
        next()
    }

    private fun failed(link: String, message: String) {
        finished++
        val listener = listeners.remove(link)
        if (listener != null) {
            listener.onFail(message)
        } else {
            notifyResult("Não foi possível baixar", "$message\n$link", null)
        }
        observer?.invoke(status, true)
        next()
    }

    private fun progressNotification(text: String, pct: Int): Notification =
        Notification.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(text)
            .setProgress(100, maxOf(pct, 0), pct < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun notifyDone(uris: List<Uri>) {
        val images = uris.all { Keys.isImage(it) }
        val title = when {
            uris.size > 1 -> "${uris.size} arquivos baixados"
            images -> "Imagem baixada"
            else -> "Vídeo baixado"
        }
        val open = Intent(this, PopupActivity::class.java)
            .setAction(PopupActivity.ACTION_DONE)
            .putParcelableArrayListExtra(PopupActivity.EXTRA_URIS, ArrayList(uris))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        notifyResult(title, "Toque para escrever a legenda e postar", open)
    }

    private fun notifyResult(title: String, text: String, open: Intent?) {
        val id = nextId++
        val builder = Notification.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (open != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        getSystemService(NotificationManager::class.java)?.notify(id, builder.build())
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CH_PROGRESS = "progresso"
        private const val CH_DONE = "concluido"
        private const val PROGRESS_ID = 2
        private var nextId = 1000

        // Tudo abaixo só é tocado na thread principal.
        private val main = Handler(Looper.getMainLooper())
        private val queue = ArrayDeque<String>()
        private val listeners = HashMap<String, Listener>()
        private var current: String? = null
        private var running = false
        private var total = 0
        private var finished = 0
        private var status: String? = null

        /** Avisa a tela principal: texto de andamento (ou null) e se um item terminou. */
        var observer: ((status: String?, itemFinished: Boolean) -> Unit)? = null

        val currentStatus: String? get() = status

        /** Chame da thread principal, com o app em primeiro plano. */
        fun enqueue(ctx: Context, link: String, listener: Listener?) {
            if (listener != null) listeners[link] = listener
            if (link == current || queue.contains(link)) return
            queue.add(link)
            total++
            try {
                ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
            } catch (e: Exception) {
                queue.remove(link)
                total--
                listeners.remove(link)?.onFail("Não foi possível iniciar o download em segundo plano")
            }
        }

        fun detach(listener: Listener) {
            listeners.values.removeAll { it === listener }
        }

        fun friendly(e: Exception): String = when (e) {
            is PinterestExtractor.NoVideoException ->
                "Não achei vídeo nem imagem nesse pin. Ele pode ser privado ou ter sido removido."
            is java.net.UnknownHostException, is java.net.SocketTimeoutException ->
                "Sem conexão com a internet."
            else -> "Não foi possível baixar: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
