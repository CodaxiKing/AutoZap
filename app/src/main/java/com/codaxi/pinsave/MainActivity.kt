package com.codaxi.pinsave

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.text.format.Formatter
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val thumbs = HashMap<String, Bitmap>()
    private var videos: List<Video> = emptyList()

    private lateinit var input: EditText
    private lateinit var watchRow: TextView
    private lateinit var tagsRow: TextView
    private lateinit var empty: TextView
    private val adapter = VideoAdapter()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(20), dp(16), 0)
            fitsSystemWindows = true
        }

        root.addView(TextView(this).apply {
            text = "PinSave"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Baixe vídeos do Pinterest e poste no Instagram ou TikTok"
            setTextColor(textDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(2), 0, dp(16))
        })

        // --- link + baixar ---
        input = EditText(this).apply {
            hint = "Cole o link do pin aqui"
            setHintTextColor(textDim)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnEditorActionListener { _, _, _ -> startDownload(); true }
        }
        root.addView(input, LinearLayout.LayoutParams(MATCH, WRAP))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            pill("Colar", card) {
                val link = Clip.readLink(this)
                if (link == null) toast("Nenhum link do Pinterest copiado") else input.setText(link)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
        )
        actions.addView(pill("Baixar", accent) { startDownload() }, LinearLayout.LayoutParams(0, WRAP, 2f))
        root.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        // --- pop-up ao copiar ---
        watchRow = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = rounded(card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { toggleShortcut() }
        }
        root.addView(watchRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        // --- hashtags fixas ---
        tagsRow = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            background = rounded(card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener {
                editText("Hashtags fixas", "Entram no fim de toda legenda. Ex.: #fyp #viral", Captions.hashtags(this@MainActivity)) {
                    Captions.setHashtags(this@MainActivity, it)
                    updateTagsRow()
                }
            }
        }
        root.addView(tagsRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        // --- lista ---
        root.addView(TextView(this).apply {
            text = "Vídeos baixados"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(22), 0, dp(8))
        })
        empty = TextView(this).apply {
            text = "Nada por aqui ainda.\n\nNo Pinterest, toque em Compartilhar → Copiar link, " +
                "ou escolha \"Baixar com PinSave\" na lista de apps."
            setTextColor(textDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }
        root.addView(empty, LinearLayout.LayoutParams(MATCH, WRAP))
        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = GradientDrawable()
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(16))
            adapter = this@MainActivity.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (Shortcut.isOn(this)) Shortcut.show(this)
        updateShortcutRow()
        updateTagsRow()
    }

    private fun updateTagsRow() {
        val tags = Captions.hashtags(this)
        tagsRow.text = if (tags.isBlank()) "＃  Definir hashtags fixas para as legendas" else "＃  $tags"
    }

    /** Diálogo simples com um campo de texto de várias linhas. */
    private fun editText(title: String, hint: String, current: String, onSave: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(current)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Salvar") { _, _ -> onSave(field.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun editCaption(video: Video) {
        editText("Legenda", "Escreva a legenda desta publicação", Captions.get(this, video.uri)) {
            Captions.set(this, video.uri, it)
            adapter.notifyDataSetChanged()
        }
    }

    /** Com o app em foco dá para ler a área de transferência: oferece o link copiado. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val link = Clip.readLink(this) ?: return
        val prefs = getSharedPreferences("pinsave", Context.MODE_PRIVATE)
        if (prefs.getString("last_clip", null) == link) return
        prefs.edit().putString("last_clip", link).apply()
        input.setText(link)
        startActivity(
            Intent(this, PopupActivity::class.java)
                .putExtra(PopupActivity.EXTRA_LINK, link)
                .putExtra(PopupActivity.EXTRA_ASK, true)
        )
    }

    private fun startDownload() {
        val link = PinterestExtractor.findLink(input.text)
        if (link == null) {
            toast("Cole um link do Pinterest (pin.it/… ou pinterest.com/pin/…)")
            return
        }
        getSharedPreferences("pinsave", Context.MODE_PRIVATE).edit().putString("last_clip", link).apply()
        input.setText("")
        startActivity(Intent(this, PopupActivity::class.java).putExtra(PopupActivity.EXTRA_LINK, link))
    }

    private fun updateShortcutRow() {
        val on = Shortcut.isOn(this)
        watchRow.text = if (on) "✓  Atalho na barra de notificações: ativado (toque para desativar)"
        else "⚡  Ativar atalho na barra de notificações"
        watchRow.setTextColor(if (on) Color.parseColor("#7BD88F") else Color.WHITE)
    }

    /** Notificação fixa: copiou o link no Pinterest, puxa a barra e toca nela. */
    private fun toggleShortcut() {
        if (Shortcut.isOn(this)) {
            Shortcut.setOn(this, false)
        } else if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        } else {
            Shortcut.setOn(this, true)
            toast("Pronto: copie o link no Pinterest, puxe a barra de notificações e toque em \"Copiou um link do Pinterest?\"")
        }
        updateShortcutRow()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Shortcut.setOn(this, true)
        } else {
            toast("Sem a permissão de notificações o atalho não aparece")
        }
        updateShortcutRow()
    }

    private fun refresh() {
        worker.execute {
            val found = Videos.list(applicationContext)
            main.post {
                videos = found
                empty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun loadThumb(video: Video, into: ImageView) {
        val key = video.uri.toString()
        into.tag = key
        val cached = thumbs[key]
        into.setImageBitmap(cached)
        if (cached != null) return
        val side = dp(72)
        worker.execute {
            val bmp = try {
                contentResolver.loadThumbnail(video.uri, Size(side * 2, side * 2), null)
            } catch (e: Exception) {
                null
            } ?: return@execute
            main.post {
                thumbs[key] = bmp
                if (into.tag == key) into.setImageBitmap(bmp)
            }
        }
    }

    private fun play(video: Video) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(video.uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("Nenhum player de vídeo encontrado")
        }
    }

    private fun showMenu(anchor: View, video: Video) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Assistir")
            menu.add(0, 4, 0, "Editar legenda")
            menu.add(0, 2, 1, "Compartilhar em outro app")
            menu.add(0, 3, 2, "Excluir")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> play(video)
                    4 -> editCaption(video)
                    2 -> Share.post(this@MainActivity, video.uri, Target.OTHER)
                    3 -> {
                        if (Videos.delete(this@MainActivity, video)) refresh() else toast("Não foi possível excluir")
                    }
                }
                true
            }
            show()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private class Row(val root: View, val thumb: ImageView, val title: TextView, val sub: TextView,
                      val caption: TextView,
                      val more: TextView, val insta: Button, val tiktok: Button)

    private inner class VideoAdapter : BaseAdapter() {
        override fun getCount() = videos.size
        override fun getItem(position: Int) = videos[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView?.tag as? Row) ?: buildRow()
            val v = videos[position]
            row.title.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(v.addedSec * 1000))
            row.sub.text = Formatter.formatShortFileSize(this@MainActivity, v.size)
            val caption = Captions.get(this@MainActivity, v.uri)
            row.caption.text = caption.ifEmpty { "Toque para escrever a legenda" }
            row.caption.setTextColor(if (caption.isEmpty()) textDim else Color.parseColor("#D5D8DE"))
            row.caption.setOnClickListener { editCaption(v) }
            loadThumb(v, row.thumb)
            row.thumb.setOnClickListener { play(v) }
            row.more.setOnClickListener { showMenu(it, v) }
            row.insta.setOnClickListener { Share.post(this@MainActivity, v.uri, Target.INSTAGRAM) }
            row.tiktok.setOnClickListener { Share.post(this@MainActivity, v.uri, Target.TIKTOK) }
            return row.root
        }

        private fun buildRow(): Row {
            val ctx = this@MainActivity
            val thumb = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.parseColor("#2A2D34"), 10)
                clipToOutline = true
                contentDescription = "Assistir vídeo"
            }
            val title = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            val sub = TextView(ctx).apply {
                setTextColor(textDim)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            val caption = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                background = rounded(Color.parseColor("#16181C"), 10)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            val more = TextView(ctx).apply {
                text = "⋮"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
                contentDescription = "Mais opções"
            }
            val insta = pill("Instagram", Color.parseColor("#C13584")) {}
            val tiktok = pill("TikTok", Color.parseColor("#2F3138")) {}

            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(sub)
            }
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(thumb, LinearLayout.LayoutParams(dp(64), dp(64)))
                addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
                addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
            }
            val buttons = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(insta, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
                addView(tiktok, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            val cardView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(card)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                addView(top)
                addView(caption, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
                addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            }
            // margem entre os cartões (ListView ignora margens do item)
            val wrapper = LinearLayout(ctx).apply {
                setPadding(0, 0, 0, dp(10))
                addView(cardView, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            return Row(wrapper, thumb, title, sub, caption, more, insta, tiktok).also { wrapper.tag = it }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
