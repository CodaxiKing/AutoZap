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
import java.util.concurrent.Executors

/**
 * Tela transparente que só mostra diálogos:
 *  1. "Quer baixar esse vídeo?"  2. progresso  3. "Postar no Instagram / TikTok?"
 *
 * Entradas: link copiado (ACTION_CHECK), "Compartilhar" do Pinterest (ACTION_SEND)
 * ou um link passado pelo próprio app (EXTRA_LINK).
 */
class PopupActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var dialog: AlertDialog? = null
    private var busy = false
    private var checking = false
    private var attempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!busy) {
            setIntent(intent)
            handle(intent)
        }
    }

    private fun handle(i: Intent) {
        when {
            i.action == Intent.ACTION_SEND -> {
                val link = PinterestExtractor.findLink(i.getStringExtra(Intent.EXTRA_TEXT))
                if (link == null) {
                    Toast.makeText(this, "Isso não é um link do Pinterest", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    download(link)
                }
            }
            i.hasExtra(EXTRA_LINK) -> {
                val link = PinterestExtractor.findLink(i.getStringExtra(EXTRA_LINK))
                when {
                    link == null -> finish()
                    i.getBooleanExtra(EXTRA_ASK, false) -> ask(link)
                    else -> download(link)
                }
            }
            else -> {
                // Veio da notificação ou do bloco: o link ainda precisa ser lido da área de
                // transferência, o que só é permitido com a janela em foco.
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
                ask(link)
            }
            ++attempts < 5 -> main.postDelayed({ check() }, 400)
            else -> {
                checking = false
                Toast.makeText(this, "Nenhum link do Pinterest copiado", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun ask(link: String) {
        busy = true
        show(
            AlertDialog.Builder(this)
                .setTitle("Baixar vídeo do Pinterest?")
                .setMessage(link)
                .setPositiveButton("Baixar") { _, _ -> download(link) }
                .setNegativeButton("Agora não") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .create()
        )
    }

    private fun download(link: String) {
        busy = true
        val pad = (24 * resources.displayMetrics.density).toInt()
        val status = TextView(this).apply { text = "Procurando o vídeo…" }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(status)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
        }
        show(
            AlertDialog.Builder(this)
                .setTitle("Baixando")
                .setView(box)
                .setCancelable(false)
                .create()
        )

        val app = applicationContext
        worker.execute {
            try {
                val uri = Videos.download(app, link) { pct ->
                    main.post {
                        if (pct >= 0) {
                            bar.isIndeterminate = false
                            bar.progress = pct
                            status.text = "Baixando… $pct%"
                        }
                    }
                }
                main.post { done(uri) }
            } catch (e: Exception) {
                val msg = when (e) {
                    is PinterestExtractor.NoVideoException ->
                        "Não achei vídeo nesse pin. Ele pode ser só uma imagem, ser privado ou ter sido removido."
                    is java.net.UnknownHostException, is java.net.SocketTimeoutException ->
                        "Sem conexão com a internet."
                    else -> "Não foi possível baixar: ${e.message ?: e.javaClass.simpleName}"
                }
                main.post { fail(msg) }
            }
        }
    }

    private fun done(uri: Uri) {
        if (isFinishing || isDestroyed) return
        val options = arrayOf("Postar no Instagram", "Postar no TikTok", "Outro app…", "Só salvar")
        show(
            AlertDialog.Builder(this)
                .setTitle("Vídeo salvo na galeria ✓")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> Share.post(this, uri, Target.INSTAGRAM)
                        1 -> Share.post(this, uri, Target.TIKTOK)
                        2 -> Share.post(this, uri, Target.OTHER)
                    }
                    finish()
                }
                .setOnCancelListener { finish() }
                .create()
        )
    }

    private fun fail(message: String) {
        if (isFinishing || isDestroyed) return
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

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        dialog = null
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CHECK = "com.codaxi.pinsave.CHECK_CLIPBOARD"
        const val EXTRA_LINK = "link"
        const val EXTRA_ASK = "ask"
    }
}
