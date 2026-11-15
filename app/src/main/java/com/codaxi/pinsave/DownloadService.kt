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
        val job = queue.pollFirst()
        if (job == null) {
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
        current = job.key
        val app = applicationContext
        worker.execute {
            try {
                val report: (Progress) -> Unit = { p -> main.post { progress(job.key, p) } }
                val uris = job.work(app, report)
                main.post { done(job.key, uris) }
            } catch (e: Exception) {
                val message = friendly(e)
                main.post { failed(job.key, message, job.shown) }
            }
        }
    }

    private fun progress(link: String, p: Progress) {
        val verb = if (p.watermark) "Gravando logo e frase" else "Baixando"
        var text = if (total > 1) "$verb ${finished + 1} de $total" else verb
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

    private fun failed(link: String, message: String, shown: String) {
        finished++
        val listener = listeners.remove(link)
        if (listener != null) {
            listener.onFail(message)
        } else {
            notifyResult("Não foi possível concluir", "$message\n$shown".trim(), null)
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
        /** [shown] identifica o trabalho para o usuário em caso de erro. */
        private class Job(
            val key: String, val shown: String, val work: (Context, (Progress) -> Unit) -> List<Uri>
        )

        private val queue = ArrayDeque<Job>()
        private val listeners = HashMap<String, Listener>()
        private var current: String? = null
        private var running = false
        private var total = 0
        private var finished = 0
        private var status: String? = null

        /** Avisa a tela principal: texto de andamento (ou null) e se um item terminou. */
        var observer: ((status: String?, itemFinished: Boolean) -> Unit)? = null

        val currentStatus: String? get() = status

        /**
         * Põe um link na fila. [phrase] é gravada no meio do vídeo ou imagem.
         * Chame da thread principal, com o app em primeiro plano.
         */
        fun enqueue(ctx: Context, link: String, listener: Listener?, phrase: String? = null) {
            if (listener != null) listeners[link] = listener
            add(ctx, Job(link, link) { c, report -> Library.download(c, link, phrase, report) })
        }

        /** Põe na fila a gravação de uma frase num arquivo já baixado. */
        fun enqueuePhrase(ctx: Context, uri: Uri, phrase: String) {
            add(ctx, Job("frase:" + Keys.of(uri), "") { c, report -> listOf(Library.addPhrase(c, uri, phrase, report)) })
        }

        /** Põe na fila a criação de um vídeo ou carrossel a partir do banco. */
        fun enqueueGenerate(
            ctx: Context, key: String, listener: Listener?, clips: List<Pair<String, String>>,
            isVideo: Boolean, caption: String
        ) {
            if (listener != null) listeners[key] = listener
            add(ctx, Job(key, "") { c, report -> Library.generate(c, clips, isVideo, caption, report) })
        }

        private fun add(ctx: Context, job: Job) {
            if (job.key == current || queue.any { it.key == job.key }) return
            queue.add(job)
            total++
            try {
                ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
            } catch (e: Exception) {
                queue.remove(job)
                total--
                listeners.remove(job.key)?.onFail("Não foi possível iniciar o trabalho em segundo plano")
            }
        }

        fun detach(listener: Listener) {
            listeners.values.removeAll { it === listener }
        }

        fun friendly(e: Exception): String = when (e) {
            is Watermark.WatermarkException -> e.message ?: "Não foi possível aplicar a marca d'água."
            is TikTokExtractor.TikTokException -> e.message ?: "Não foi possível baixar do TikTok."
            is Pexels.PexelsException -> e.message ?: "Não foi possível usar o banco de vídeos."
            is InstagramExtractor.InstagramException -> e.message ?: "Não foi possível baixar do Instagram."
            is PinterestExtractor.NoVideoException ->
                "Não achei vídeo nem imagem nesse link. Ele pode ser privado ou ter sido removido."
            is java.net.UnknownHostException, is java.net.SocketTimeoutException ->
                "Sem conexão com a internet."
            else -> "Não foi possível baixar: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
