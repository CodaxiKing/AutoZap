package com.codaxi.pinsave

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Cria um vídeo ou um carrossel: o usuário dá as frases, o app sugere fundos
 * do banco no tema escolhido e o usuário troca os que não gostar. O resultado
 * sai com visual escuro, frase no meio e logo.
 */
class GeneratorActivity : Activity() {
    private class Slide(val phrase: String, var clip: Pexels.Clip)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(3)
    private var carousel = false
    private var theme = -1                       // -1 = aleatório
    private var query = ""
    private var page = 1
    private val pool = ArrayList<Pexels.Clip>()  // fundos já buscados e ainda não usados
    private val slides = ArrayList<Slide>()
    private var listener: DownloadService.Listener? = null
    private var progress: AlertDialog? = null

    private lateinit var keyBox: LinearLayout
    private lateinit var keyField: EditText
    private lateinit var form: LinearLayout
    private lateinit var themeButton: Button
    private lateinit var searchField: EditText
    private lateinit var phraseField: EditText
    private lateinit var slidesBox: LinearLayout
    private lateinit var resultBox: LinearLayout
    private lateinit var caption: CaptionUi.Box

    private val bg = Color.parseColor("#121316")
    private val card = Color.parseColor("#1D1F24")
    private val accent = Color.parseColor("#FF5A5F")
    private val textDim = Color.parseColor("#9AA0AA")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun pill(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color, 12)
        stateListAnimator = null
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    private fun label(text: String, top: Int = 16) = TextView(this).apply {
        this.text = text
        setTextColor(textDim)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(top), 0, dp(6))
    }

    private fun field(hint: String, lines: Int) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(textDim)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if (lines > 1) {
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
        }
        background = rounded(card)
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        carousel = intent.getBooleanExtra(EXTRA_CAROUSEL, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        root.addView(TextView(this).apply {
            text = if (carousel) "Criar carrossel" else "Criar vídeo"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
        })

        // --- chave do banco (só aparece enquanto não houver uma) ---
        keyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        keyBox.addView(TextView(this).apply {
            text = "Os fundos vêm do Pexels, um banco gratuito de vídeos e fotos liberados para publicar. " +
                "Ele pede uma chave gratuita:\n\n1. Abra o site e crie uma conta.\n" +
                "2. Peça a chave de API (é liberada na hora).\n3. Copie a chave e cole aqui."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        keyBox.addView(
            pill("Abrir pexels.com/api", Color.parseColor("#2F3138")) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.pexels.com/api/")))
                } catch (e: Exception) {
                    toast("Abra pexels.com/api no navegador")
                }
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        )
        keyField = field("Cole a chave do Pexels", 1).apply { background = rounded(bg) }
        keyBox.addView(keyField, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        keyBox.addView(
            pill("Salvar chave", accent) {
                val key = keyField.text.toString().trim()
                if (key.length < 20) {
                    toast("Essa chave parece incompleta")
                } else {
                    Pexels.setKey(this, key)
                    showKeyBox(false)
                }
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
        )
        root.addView(keyBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })

        // --- formulário ---
        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(label("Tema do fundo"))
        themeButton = pill("Tema: aleatório ▾", card) { chooseTheme() }
        form.addView(themeButton, LinearLayout.LayoutParams(MATCH, WRAP))
        searchField = field("ou busque outro tema (em inglês acha mais)", 1)
        form.addView(searchField, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        form.addView(label(if (carousel) "Frases — uma por linha, cada linha vira um slide" else "Frase do vídeo"))
        phraseField = field(
            if (carousel) "Primeira frase\nSegunda frase\nTerceira frase" else "Escreva a frase que aparece no meio",
            if (carousel) 4 else 2
        )
        form.addView(phraseField, LinearLayout.LayoutParams(MATCH, WRAP))
        form.addView(
            pill(if (carousel) "Buscar fundos" else "Buscar fundo", accent) { findBackgrounds() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        )

        // --- prévia, legenda e botão de gerar (aparecem depois da busca) ---
        resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        resultBox.addView(label("Prévia — toque em Trocar até gostar do fundo", 22))
        slidesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultBox.addView(slidesBox)
        resultBox.addView(label("Legenda da publicação"))
        caption = CaptionUi.box(this, "", "Legenda e hashtags")
        caption.view.setPadding(0, 0, 0, 0)
        caption.field.setTextColor(Color.WHITE)
        caption.field.setHintTextColor(textDim)
        resultBox.addView(caption.view)
        resultBox.addView(
            pill(if (carousel) "Gerar carrossel" else "Gerar vídeo", accent) { make() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) }
        )
        form.addView(resultBox)

        form.addView(TextView(this).apply {
            text = "Trocar a chave do Pexels"
            setTextColor(textDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(24), 0, 0)
            setOnClickListener { showKeyBox(true) }
        })
        root.addView(form)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            fitsSystemWindows = true
            addView(root)
        })
        showKeyBox(Pexels.key(this).isBlank())
    }

    private fun showKeyBox(show: Boolean) {
        keyBox.visibility = if (show) View.VISIBLE else View.GONE
        form.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun chooseTheme() {
        val names = listOf("Aleatório") + Pexels.THEMES.map { it.first }
        AlertDialog.Builder(this)
            .setTitle("Tema do fundo")
            .setItems(names.toTypedArray()) { _, which ->
                theme = which - 1
                themeButton.text = "Tema: ${names[which].lowercase()} ▾"
            }
            .show()
    }

    private fun phrases(): List<String> {
        val text = phraseField.text.toString().trim()
        if (text.isEmpty()) return emptyList()
        return if (carousel) text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(10) else listOf(text)
    }

    /** Busca fundos no tema escolhido e monta a prévia, um fundo por frase. */
    private fun findBackgrounds() {
        val list = phrases()
        if (list.isEmpty()) {
            toast(if (carousel) "Escreva pelo menos uma frase" else "Escreva a frase do vídeo")
            return
        }
        val custom = searchField.text.toString().trim()
        query = when {
            custom.isNotEmpty() -> "$custom dark"
            theme >= 0 -> Pexels.THEMES[theme].second
            else -> Pexels.THEMES.random().second
        }
        page = 1
        pool.clear()
        toast("Buscando fundos…")
        loadMore {
            if (pool.size < list.size) {
                toast("Achei poucos fundos para esse tema. Tente outro tema ou menos frases.")
                return@loadMore
            }
            slides.clear()
            list.forEach { slides.add(Slide(it, pool.removeAt(0))) }
            if (caption.field.text.isBlank()) caption.field.setText(list[0])
            resultBox.visibility = View.VISIBLE
            renderSlides()
        }
    }

    /** Carrega mais uma página de fundos e chama [then] na thread principal. */
    private fun loadMore(then: () -> Unit) {
        val key = Pexels.key(this)
        val q = query
        val p = page++
        worker.execute {
            try {
                val found = (if (carousel) Pexels.photos(key, q, p) else Pexels.videos(key, q, p)).shuffled()
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    pool.addAll(found)
                    then()
                }
            } catch (e: Exception) {
                val message = DownloadService.friendly(e)
                main.post { if (!isFinishing && !isDestroyed) toast(message) }
            }
        }
    }

    private fun renderSlides() {
        slidesBox.removeAllViews()
        val w = if (carousel) dp(176) else dp(216)
        val h = if (carousel) w * 5 / 4 else w * 16 / 9
        slides.forEach { slide ->
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(card)
                // mesma escurecida que o resultado final recebe
                setColorFilter(Color.argb(70, 0, 0, 0))
            }
            val text = TextView(this).apply {
                this.text = slide.phrase
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, w * 0.036f * 1.15f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setShadowLayer(dp(3).toFloat(), 0f, 1f, Color.BLACK)
                setPadding(w / 7, 0, w / 7, 0)
            }
            val frame = FrameLayout(this).apply {
                addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(text, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
            }
            loadThumb(slide.clip.thumb, image)

            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            buttons.addView(pill("Trocar", Color.parseColor("#2F3138")) {
                val swap = {
                    if (pool.isEmpty()) {
                        toast("Não achei mais fundos nesse tema")
                    } else {
                        slide.clip = pool.removeAt(0)
                        image.setImageBitmap(null)
                        loadThumb(slide.clip.thumb, image)
                    }
                }
                if (pool.isEmpty()) loadMore(swap) else swap()
            })
            if (!carousel) {
                buttons.addView(
                    pill("Ver vídeo", Color.parseColor("#2F3138")) {
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(slide.clip.url), "video/mp4")
                            )
                        } catch (e: Exception) {
                            toast("Nenhum player de vídeo encontrado")
                        }
                    },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) }
                )
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(12))
                addView(frame, LinearLayout.LayoutParams(w, h))
                addView(buttons, LinearLayout.LayoutParams(WRAP, MATCH))
            }
            slidesBox.addView(row)
        }
    }

    private fun loadThumb(url: String, into: ImageView) {
        into.tag = url
        worker.execute {
            val bitmap: Bitmap? = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                try {
                    conn.inputStream.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) main.post { if (into.tag == url) into.setImageBitmap(bitmap) }
        }
    }

    /** Manda para a fila e acompanha; ao terminar abre a tela de postar. */
    private fun make() {
        if (slides.isEmpty()) return
        val pad = dp(24)
        val status = TextView(this).apply { text = "Na fila…" }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(status)
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = pad / 2 })
        }
        val l = object : DownloadService.Listener {
            override fun onProgress(text: String, pct: Int) {
                bar.isIndeterminate = pct < 0
                if (pct >= 0) bar.progress = pct
                status.text = if (pct >= 0) "$text… $pct%" else "$text…"
            }

            override fun onDone(uris: List<Uri>) {
                listener = null
                progress?.dismiss()
                startActivity(
                    Intent(this@GeneratorActivity, PopupActivity::class.java)
                        .setAction(PopupActivity.ACTION_DONE)
                        .putParcelableArrayListExtra(PopupActivity.EXTRA_URIS, ArrayList(uris))
                )
                finish()
            }

            override fun onFail(message: String) {
                listener = null
                progress?.dismiss()
                if (isFinishing || isDestroyed) return
                AlertDialog.Builder(this@GeneratorActivity)
                    .setTitle("Ops")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
        listener = l
        progress = AlertDialog.Builder(this)
            .setTitle(if (carousel) "Gerando carrossel" else "Gerando vídeo")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Continuar em 2º plano") { _, _ ->
                detach()
                toast("Aviso por notificação quando terminar")
                finish()
            }
            .show()
        DownloadService.enqueueGenerate(
            this, "criar:" + System.currentTimeMillis(), l,
            slides.map { it.clip.url to it.phrase }, !carousel, caption.field.text.toString()
        )
    }

    private fun detach() {
        listener?.let { DownloadService.detach(it) }
        listener = null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        detach()
        progress?.dismiss()
        progress = null
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CAROUSEL = "carousel"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
