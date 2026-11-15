package com.codaxi.pinsave

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Tela transparente que só mostra diálogos:
 *  1. "Quer baixar?"  2. progresso  3. legenda + "Postar no Instagram / TikTok?"
 *
 * Entradas: toque na notificação ou no bloco (ACTION_CHECK, lê o link copiado),
 * "Compartilhar" do Pinterest ou Instagram (ACTION_SEND), um link passado pelo próprio app
 * (EXTRA_LINK) ou um download que terminou em segundo plano (ACTION_DONE).
 */
class PopupActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var busy = false
    private var checking = false
    private var attempts = 0
    private var listener: DownloadService.Listener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val link = linkOf(intent)
        when {
            !busy -> {
                setIntent(intent)
                handle(intent)
            }
            intent.action == ACTION_DONE -> {
                // o download atual segue em segundo plano e avisa por notificação
                detach()
                setIntent(intent)
                handle(intent)
            }
            link != null -> {
                DownloadService.enqueue(this, link, null)
                toast("Adicionado à fila de downloads")
            }
        }
    }

    private fun linkOf(i: Intent): String? = when {
        i.action == Intent.ACTION_SEND -> Links.findLink(i.getStringExtra(Intent.EXTRA_TEXT))
        i.hasExtra(EXTRA_LINK) -> Links.findLink(i.getStringExtra(EXTRA_LINK))
        else -> null
    }

    private fun handle(i: Intent) {
        val link = linkOf(i)
        when {
            i.action == ACTION_DONE -> {
                @Suppress("DEPRECATION")
                val uris = i.getParcelableArrayListExtra<Uri>(EXTRA_URIS)
                if (uris.isNullOrEmpty()) finish() else done(uris)
            }
            i.action == Intent.ACTION_SEND -> {
                if (link == null) {
                    toast("Isso não é um link do Pinterest nem do Instagram")
                    finish()
                } else {
                    start(link)
                }
            }
            i.hasExtra(EXTRA_LINK) -> when {
                link == null -> finish()
                i.getBooleanExtra(EXTRA_ASK, false) -> ask(link)
                else -> start(link)
            }
            else -> {
                // Veio da notificação ou do bloco: o link ainda precisa ser lido da
                // área de transferência, o que só é permitido com a janela em foco.
                checking = true
                attempts = 0
                main.postDelayed({ if (checking) finish() }, 5000)
                if (hasWindowFocus()) main.postDelayed({ check() }, 250)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && checking) main.postDelayed({ check() }, 250)
    }

    private fun check() {
        if (!checking || isFinishing || isDestroyed) return
        val link = Clip.readLink(this)
        when {
            link != null -> {
                checking = false
                // o botão flutuante já é um pedido para baixar: não pergunta de novo
                if (intent.getBooleanExtra(EXTRA_DIRECT, false)) start(link) else ask(link)
            }
            ++attempts < 5 -> main.postDelayed({ check() }, 400)
            else -> {
                checking = false
                toast("Nenhum link do Pinterest ou Instagram copiado")
                finish()
            }
        }
    }

    /** Quem já pediu para baixar só passa pelo diálogo se o app deve pedir a frase. */
    private fun start(link: String) {
        if (Watermark.asksPhrase(this)) ask(link) else download(link, null)
    }

    private fun ask(link: String) {
        busy = true
        val builder = AlertDialog.Builder(this)
            .setTitle(if (InstagramExtractor.isInstagram(link)) "Baixar do Instagram?" else "Baixar do Pinterest?")
        // Campo opcional para a frase que fica no meio do vídeo.
        val phraseBox = if (Watermark.asksPhrase(this)) {
            CaptionUi.box(
                this, "", "Frase no meio do vídeo (opcional)",
                "A frase fica gravada no vídeo, em branco, no centro.", templates = false
            )
        } else {
            null
        }
        if (phraseBox != null) builder.setView(phraseBox.view) else builder.setMessage(link)
        val phrase = { phraseBox?.field?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
        show(
            builder
                .setPositiveButton("Baixar") { _, _ -> download(link, phrase()) }
                .setNeutralButton("Em 2º plano") { _, _ ->
                    DownloadService.enqueue(this, link, null, phrase())
                    toast("Na fila — aviso por notificação quando terminar")
                    finish()
                }
                .setNegativeButton("Agora não") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .create()
        )
    }

    private fun download(link: String, phrase: String?) {
        busy = true
        val pad = (24 * resources.displayMetrics.density).toInt()
        val status = TextView(this).apply { text = "Na fila…" }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(status)
            addView(
                bar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = pad / 2 }
            )
        }
        val l = object : DownloadService.Listener {
            override fun onProgress(text: String, pct: Int) {
                bar.isIndeterminate = pct < 0
                if (pct >= 0) bar.progress = pct
                status.text = if (pct >= 0) "$text… $pct%" else "$text…"
            }

            override fun onDone(uris: List<Uri>) {
                listener = null
                done(uris)
            }

            override fun onFail(message: String) {
                listener = null
                fail(message)
            }
        }
        listener = l
        show(
            AlertDialog.Builder(this)
                .setTitle("Baixando")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Continuar em 2º plano") { _, _ ->
                    detach()
                    toast("Aviso por notificação quando terminar")
                    finish()
                }
                .create()
        )
        DownloadService.enqueue(this, link, l, phrase)
    }

    private fun done(uris: List<Uri>) {
        if (isFinishing || isDestroyed) return
        busy = true
        val tags = Captions.hashtags(this)
        val box = CaptionUi.box(
            this,
            Captions.get(this, uris[0]),
            "Escreva a legenda desta publicação",
            "A legenda é copiada ao postar: é só colar no Instagram ou TikTok." +
                if (tags.isNotBlank()) "\nHashtags fixas no final: $tags" else ""
        )
        val title = when {
            uris.size > 1 -> "${uris.size} arquivos salvos ✓"
            Keys.isImage(uris[0]) -> "Imagem salva ✓"
            else -> "Vídeo salvo ✓"
        }
        val saveCaption = { uris.forEach { Captions.set(this, it, box.field.text.toString()) } }
        show(
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box.view)
                .setPositiveButton("Instagram") { _, _ ->
                    saveCaption()
                    Share.chooseInstagram(this, uris) { finish() }
                }
                .setNegativeButton("TikTok") { _, _ ->
                    saveCaption()
                    Share.post(this, uris, Target.TIKTOK)
                    finish()
                }
                .setNeutralButton("Só salvar") { _, _ ->
                    saveCaption()
                    finish()
                }
                .setOnCancelListener {
                    saveCaption()
                    finish()
                }
                .create()
        )
    }

    private fun fail(message: String) {
        if (isFinishing || isDestroyed) return
        busy = true
        show(
            AlertDialog.Builder(this)
                .setTitle("Ops")
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .create()
        )
    }

    private fun show(d: AlertDialog) {
        dialog?.dismiss()
        dialog = d
        d.setCanceledOnTouchOutside(false)
        d.show()
    }

    private fun detach() {
        listener?.let { DownloadService.detach(it) }
        listener = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        detach()
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_CHECK = "com.codaxi.pinsave.CHECK_CLIPBOARD"
        const val ACTION_DONE = "com.codaxi.pinsave.DOWNLOAD_DONE"
        const val EXTRA_LINK = "link"
        const val EXTRA_ASK = "ask"
        const val EXTRA_DIRECT = "direct"
        const val EXTRA_URIS = "uris"
    }
}
