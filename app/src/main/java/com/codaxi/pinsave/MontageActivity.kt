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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Monta um vídeo com vários trechos que trocam no ritmo de uma música. O
 * usuário escolhe a música, a duração e o ritmo; o app acha as batidas,
 * sugere um fundo do banco para cada parte e deixa trocar um por um.
 */
class MontageActivity : Activity() {
    private class Part(val seconds: Float, var clip: Pexels.Clip)

    private class Pace(val name: String, val min: Float, val max: Float)

    private val paces = listOf(
        Pace("rápido (troca a cada ~1 s)", 0.7f, 1.4f),
        Pace("médio (troca a cada ~2 s)", 1.4f, 2.6f),
        Pace("lento (troca a cada ~3,5 s)", 2.6f, 4.5f)
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(3)
    private var music: File? = null
    private var musicSeconds = 0f
    private var pace = 0
    private var theme = -1
    private var activeTheme: Pexels.Theme? = null
    private var tags = ""
    private var query = ""
    private var page = 1
    private val pool = ArrayList<Pexels.Clip>()
    private val parts = ArrayList<Part>()
    private var swaps = 0
    private var listener: DownloadService.Listener? = null
    private var progress: AlertDialog? = null

    private lateinit var musicButton: Button
    private lateinit var limitField: EditText
    private lateinit var paceButton: Button
    private lateinit var themeButton: Button
    private lateinit var searchField: EditText
    private lateinit var phraseField: EditText
    private lateinit var partsBox: LinearLayout
    private lateinit var resultBox: LinearLayout
    private lateinit var caption: CaptionUi.Box
    private lateinit var tagsView: TextView

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

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(textDim)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        inputType = type
        background = rounded(card)
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // sobras de montagens antigas que terminaram com a tela fechada
        cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("montagem_") && System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "Criar montagem com música"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
        })

        root.addView(label("Música"))
        musicButton = pill("Escolher música ▾", card) { chooseMusic() }
        root.addView(musicButton, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(label("Duração máxima do vídeo, em segundos"))
        limitField = field("15", InputType.TYPE_CLASS_NUMBER).apply { setText("15") }
        root.addView(limitField, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(label("Ritmo das trocas (os cortes caem nas batidas da música)"))
        paceButton = pill("Ritmo: ${paces[pace].name} ▾", card) {
            AlertDialog.Builder(this)
                .setTitle("Ritmo das trocas")
                .setItems(paces.map { it.name }.toTypedArray()) { _, which ->
                    pace = which
                    paceButton.text = "Ritmo: ${paces[pace].name} ▾"
                }
                .show()
        }
        root.addView(paceButton, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(label("Tema dos vídeos"))
        themeButton = pill("Tema: aleatório ▾", card) {
            val names = listOf("Aleatório") + Pexels.THEMES.map { it.name }
            AlertDialog.Builder(this)
                .setTitle("Tema dos vídeos")
                .setItems(names.toTypedArray()) { _, which ->
                    theme = which - 1
                    themeButton.text = "Tema: ${names[which].lowercase()} ▾"
                }
                .show()
        }
        root.addView(themeButton, LinearLayout.LayoutParams(MATCH, WRAP))
        searchField = field("ou busque outro tema (em inglês acha mais)", InputType.TYPE_CLASS_TEXT)
        root.addView(searchField, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        root.addView(label("Frase no meio do vídeo (opcional)"))
        phraseField = field("Ex.: MAFIA", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        root.addView(phraseField, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(TextView(this).apply {
            text = "Usar frase do banco ▾"
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(10), 0, dp(2))
            setOnClickListener { PhraseBank.pickOne(this@MontageActivity) { phraseField.setText(it) } }
        })

        root.addView(
            pill("Montar prévia", accent) { prepare() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        )

        resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        resultBox.addView(label("Partes — toque em Trocar para mudar o vídeo de cada uma", 22))
        partsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultBox.addView(partsBox)
        resultBox.addView(label("Legenda da publicação"))
        caption = CaptionUi.box(this, "", "Legenda")
        caption.view.setPadding(0, 0, 0, 0)
        caption.field.setTextColor(Color.WHITE)
        caption.field.setHintTextColor(textDim)
        resultBox.addView(caption.view)
        tagsView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = rounded(card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { editTags() }
        }
        resultBox.addView(tagsView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        resultBox.addView(
            pill("Gerar montagem", accent) { make() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) }
        )
        root.addView(resultBox)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            fitsSystemWindows = true
            addView(root)
        })
    }

    // ---------- música ----------

    private fun chooseMusic() {
        val songs = Library.audios(this)
        val stamp = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        val labels = listOf("Escolher um áudio ou vídeo do celular…") +
            songs.map { "Áudio extraído em " + stamp.format(java.util.Date(it.addedSec * 1000)) }
        AlertDialog.Builder(this)
            .setTitle("Música")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    try {
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("*/*")
                                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*")),
                            PICK_MUSIC
                        )
                    } catch (e: Exception) {
                        toast("Nenhum seletor de arquivos encontrado")
                    }
                } else {
                    loadMusic(songs[which - 1].uri)
                }
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == PICK_MUSIC && resultCode == RESULT_OK) loadMusic(uri)
    }

    /** Copia a música para dentro do app, para ela continuar acessível durante a montagem. */
    private fun loadMusic(uri: Uri) {
        musicButton.text = "Carregando a música…"
        worker.execute {
            val file = File(cacheDir, "montagem_musica")
            val seconds = try {
                val input = contentResolver.openInputStream(uri) ?: throw java.io.IOException()
                input.use { i -> file.outputStream().use { i.copyTo(it, 65536) } }
                Montage.duration(file)
            } catch (e: Exception) {
                0f
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (seconds < 1f) {
                    music = null
                    musicButton.text = "Escolher música ▾"
                    toast("Não consegui ler esse arquivo como música")
                } else {
                    music = file
                    musicSeconds = seconds
                    musicButton.text = "Música: ${seconds.toInt()} s ▾"
                }
            }
        }
    }

    // ---------- prévia ----------

    /** Acha as batidas, divide o vídeo em partes e busca um fundo para cada uma. */
    private fun prepare() {
        val song = music
        if (song == null) {
            toast("Escolha a música primeiro")
            return
        }
        val limit = limitField.text.toString().toIntOrNull()?.coerceIn(3, 60) ?: 15
        val total = minOf(limit.toFloat(), musicSeconds)
        val speed = paces[pace]
        val custom = searchField.text.toString().trim()
        activeTheme = when {
            custom.isNotEmpty() -> null
            theme >= 0 -> Pexels.THEMES[theme]
            else -> Pexels.THEMES.random()
        }
        query = activeTheme?.query ?: "$custom dark"
        tags = Pexels.tags(this, activeTheme)
        page = 1
        pool.clear()
        toast("Analisando a música e buscando vídeos…")
        worker.execute {
            val lengths = Beats.segments(Beats.peaks(Beats.envelope(song, total)), total, speed.min, speed.max)
            main.post {
                if (isFinishing || isDestroyed) return@post
                loadMore {
                    if (pool.isEmpty()) {
                        toast("Não achei vídeos para esse tema. Tente outro.")
                        return@loadMore
                    }
                    parts.clear()
                    lengths.forEach { parts.add(Part(it, takeClip(it))) }
                    val phrase = phraseField.text.toString().trim()
                    if (caption.field.text.isBlank() && phrase.isNotEmpty()) caption.field.setText(phrase)
                    resultBox.visibility = View.VISIBLE
                    showTags()
                    renderParts()
                }
            }
        }
    }

    /** Próximo fundo que dure pelo menos [seconds]; reaproveita os já vistos se acabarem. */
    private fun takeClip(seconds: Float): Pexels.Clip {
        val i = pool.indexOfFirst { it.seconds == 0 || it.seconds >= seconds + 0.5f }
        val clip = pool.removeAt(if (i >= 0) i else 0)
        pool.add(clip)   // volta para o fim da fila, para servir de reserva
        return clip
    }

    private fun loadMore(then: () -> Unit) {
        val key = Pexels.key(this)
        val q = query
        val p = page++
        worker.execute {
            try {
                // arquivos menores: são vários por montagem
                val found = Pexels.videos(key, q, p, 720).shuffled()
                main.post {
                    if (isFinishing || isDestroyed) return@post
                    pool.addAll(0, found)
                    then()
                }
            } catch (e: Exception) {
                val message = DownloadService.friendly(e)
                main.post { if (!isFinishing && !isDestroyed) toast(message) }
            }
        }
    }

    private fun renderParts() {
        partsBox.removeAllViews()
        var start = 0f
        parts.forEachIndexed { index, part ->
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(card)
            }
            loadThumb(part.clip.thumb, image)
            val info = TextView(this).apply {
                text = "Parte ${index + 1}\n%.1f s – %.1f s".format(start, start + part.seconds)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(12), 0, dp(8), 0)
            }
            val swap = pill("Trocar", Color.parseColor("#2F3138")) {
                val change = {
                    part.clip = takeClip(part.seconds)
                    image.setImageBitmap(null)
                    loadThumb(part.clip.thumb, image)
                }
                // a cada volta completa pelos fundos já vistos, busca mais uma página
                if (++swaps % maxOf(1, pool.size) == 0) loadMore(change) else change()
            }
            partsBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(10))
                addView(image, LinearLayout.LayoutParams(dp(63), dp(112)))
                addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(swap)
            })
            start += part.seconds
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
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })
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

    private fun showTags() {
        val name = activeTheme?.name ?: "busca livre"
        tagsView.text = "Hashtags do tema ($name) — toque para editar:\n" + tags.ifBlank { "(nenhuma)" }
    }

    private fun editTags() {
        val box = CaptionUi.box(this, tags, "#hashtag1 #hashtag2", null, false)
        AlertDialog.Builder(this)
            .setTitle("Hashtags: " + (activeTheme?.name ?: "busca livre"))
            .setView(box.view)
            .setPositiveButton("Salvar") { _, _ ->
                tags = box.field.text.toString().trim()
                Pexels.setTags(this, activeTheme, tags)
                showTags()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- gerar ----------

    private fun make() {
        val song = music
        if (parts.isEmpty() || song == null) return
        val phrase = phraseField.text.toString().trim()
        if (phrase.isNotEmpty()) PhraseBank.markUsed(this, listOf(phrase))

        // A fila trabalha em segundo plano: a música ganha um nome próprio para
        // não ser trocada se o usuário montar outro vídeo em seguida.
        val copy = File(cacheDir, "montagem_${System.currentTimeMillis()}")
        try {
            song.copyTo(copy, overwrite = true)
        } catch (e: Exception) {
            toast("Não consegui preparar a música")
            return
        }

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
                copy.delete()
                progress?.dismiss()
                startActivity(
                    Intent(this@MontageActivity, PopupActivity::class.java)
                        .setAction(PopupActivity.ACTION_DONE)
                        .putParcelableArrayListExtra(PopupActivity.EXTRA_URIS, ArrayList(uris))
                )
                finish()
            }

            override fun onFail(message: String) {
                listener = null
                copy.delete()
                progress?.dismiss()
                if (isFinishing || isDestroyed) return
                AlertDialog.Builder(this@MontageActivity)
                    .setTitle("Ops")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
        listener = l
        progress = AlertDialog.Builder(this)
            .setTitle("Gerando montagem")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Continuar em 2º plano") { _, _ ->
                detach()
                toast("Aviso por notificação quando terminar")
                finish()
            }
            .show()
        DownloadService.enqueueMontage(
            this, "montagem:" + System.currentTimeMillis(), l, copy,
            parts.map { it.clip.url to it.seconds }, phrase, caption.field.text.toString(), tags
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

    private companion object {
        const val PICK_MUSIC = 20
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
