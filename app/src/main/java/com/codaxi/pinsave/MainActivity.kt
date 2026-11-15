package com.codaxi.pinsave

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
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
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.text.format.Formatter
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private var all: List<Item> = emptyList()
    private var shown: List<Item> = emptyList()
    private var onlyUnposted = false

    private lateinit var input: EditText
    private lateinit var queueRow: TextView
    private lateinit var shortcutRow: TextView
    private lateinit var bubbleRow: TextView
    private lateinit var watermarkRow: TextView
    private lateinit var phraseRow: TextView
    private lateinit var reminderRow: TextView
    private lateinit var aiRow: TextView
    private lateinit var tagsRow: TextView
    private lateinit var templatesRow: TextView
    private lateinit var filterAll: Button
    private lateinit var filterUnposted: Button
    private lateinit var empty: TextView
    private val adapter = ItemAdapter()

    private val bg = Color.parseColor("#121316")
    private val card = Color.parseColor("#1D1F24")
    private val accent = Color.parseColor("#FF5A5F")
    private val green = Color.parseColor("#7BD88F")
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

    private fun settingRow(onClick: () -> Unit) = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        background = rounded(card)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tudo acima da lista vai no cabeçalho dela, para a tela inteira rolar junto.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, 0)
        }

        header.addView(TextView(this).apply {
            text = "PinSave"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
        })
        header.addView(TextView(this).apply {
            text = "Baixe ou crie conteúdo e poste no Instagram ou TikTok"
            setTextColor(textDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(2), 0, dp(16))
        })

        // --- links + baixar ---
        input = EditText(this).apply {
            hint = "Cole links do Pinterest, Instagram ou TikTok"
            setHintTextColor(textDim)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            background = rounded(card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        header.addView(input, LinearLayout.LayoutParams(MATCH, WRAP))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            pill("Colar", card) {
                val link = Clip.readLink(this)
                if (link == null) {
                    toast("Nenhum link do Pinterest, Instagram ou TikTok copiado")
                } else {
                    val current = input.text.toString().trim()
                    if (!current.contains(link)) input.setText(if (current.isEmpty()) link else "$current\n$link")
                }
            },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
        )
        actions.addView(pill("Baixar", accent) { startDownload() }, LinearLayout.LayoutParams(0, WRAP, 2f))
        header.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        // --- criar conteúdo novo ---
        val create = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        create.addView(
            pill("Criar vídeo", Color.parseColor("#2F3138")) {
                startActivity(Intent(this, GeneratorActivity::class.java))
            },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
        )
        create.addView(
            pill("Criar carrossel", Color.parseColor("#2F3138")) {
                startActivity(Intent(this, GeneratorActivity::class.java).putExtra(GeneratorActivity.EXTRA_CAROUSEL, true))
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        header.addView(create, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        header.addView(
            pill("Criar vários vídeos de uma vez", Color.parseColor("#2F3138")) {
                startActivity(Intent(this, GeneratorActivity::class.java).putExtra(GeneratorActivity.EXTRA_BATCH, true))
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        )

        queueRow = TextView(this).apply {
            setTextColor(green)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(4), dp(10), dp(4), 0)
            visibility = View.GONE
        }
        header.addView(queueRow)

        // --- ajustes ---
        shortcutRow = settingRow { toggleShortcut() }
        header.addView(shortcutRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        bubbleRow = settingRow { toggleBubble() }
        header.addView(bubbleRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        watermarkRow = settingRow {
            Watermark.setOn(this, !Watermark.isOn(this))
            updateRows()
        }
        header.addView(watermarkRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        phraseRow = settingRow {
            Watermark.setAsksPhrase(this, !Watermark.asksPhrase(this))
            updateRows()
        }
        header.addView(phraseRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        aiRow = settingRow {
            editText(
                "Legendas com IA",
                "Cole aqui a chave da OpenAI (sk-…)",
                "",
                onDelete = if (AiCaptions.isOn(this)) {
                    {
                        AiCaptions.setKey(this, "")
                        updateRows()
                    }
                } else {
                    null
                }
            ) { typed ->
                val key = typed.trim()
                if (key.isEmpty()) return@editText
                if (!key.startsWith("sk-") || key.length < 40) {
                    toast("Essa chave parece incompleta")
                } else {
                    AiCaptions.setKey(this, key)
                    toast("Chave salva só neste aparelho. As próximas legendas já vêm prontas.")
                    updateRows()
                }
            }
        }
        header.addView(aiRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        reminderRow = settingRow { manageReminders() }
        header.addView(reminderRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        tagsRow = settingRow {
            editText("Hashtags fixas", "Entram no fim de toda legenda. Ex.: #fyp #viral", Captions.hashtags(this)) {
                Captions.setHashtags(this, it)
                updateRows()
            }
        }
        header.addView(tagsRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        templatesRow = settingRow { manageTemplates() }
        header.addView(templatesRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        // --- lista ---
        header.addView(TextView(this).apply {
            text = "Baixados"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(22), 0, dp(8))
        })
        filterAll = pill("Todos", card) { setFilter(false) }
        filterUnposted = pill("Ainda não postei", card) { setFilter(true) }
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
            addView(filterAll, LinearLayout.LayoutParams(WRAP, dp(38)).apply { rightMargin = dp(8) })
            addView(filterUnposted, LinearLayout.LayoutParams(WRAP, dp(38)))
        }
        header.addView(filters)

        empty = TextView(this).apply {
            setTextColor(textDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        header.addView(empty, LinearLayout.LayoutParams(MATCH, WRAP))

        val list = ListView(this).apply {
            setBackgroundColor(bg)
            divider = null
            dividerHeight = 0
            selector = GradientDrawable()
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            fitsSystemWindows = true
            itemsCanFocus = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setPadding(dp(16), 0, dp(16), dp(16))
            addHeaderView(header, null, false)
            adapter = this@MainActivity.adapter
        }
        setContentView(list)
        setFilter(false)
    }

    override fun onResume() {
        super.onResume()
        if (Shortcut.isOn(this)) Shortcut.show(this)
        BubbleService.start(this)
        Reminders.schedule(this)
        updateRows()
        refresh()
        showQueue(DownloadService.currentStatus)
        DownloadService.observer = { status, itemFinished ->
            showQueue(status)
            if (itemFinished) refresh()
        }
    }

    override fun onPause() {
        DownloadService.observer = null
        super.onPause()
    }

    private fun showQueue(status: String?) {
        queueRow.text = status ?: ""
        queueRow.visibility = if (status == null) View.GONE else View.VISIBLE
    }

    private fun updateRows() {
        val on = Shortcut.isOn(this)
        shortcutRow.text = if (on) "✓  Atalho na barra de notificações: ativado (toque para desativar)"
        else "⚡  Ativar atalho na barra de notificações"
        shortcutRow.setTextColor(if (on) green else Color.WHITE)

        val bubble = BubbleService.isOn(this)
        bubbleRow.text = if (bubble) "✓  Botão flutuante: ativado (toque para desativar)"
        else "●  Ativar botão flutuante (copiou o link, toca na bolinha)"
        bubbleRow.setTextColor(if (bubble) green else Color.WHITE)

        val mark = Watermark.isOn(this)
        watermarkRow.text = if (mark) "✓  Marca d'água com a logo: ativada (toque para desativar)"
        else "◇  Ativar marca d'água com a logo nos próximos downloads"
        watermarkRow.setTextColor(if (mark) green else Color.WHITE)

        val asks = Watermark.asksPhrase(this)
        phraseRow.text = if (asks) "✓  Pedir a frase do vídeo antes de baixar: ativado"
        else "❝  Pedir a frase do vídeo antes de baixar"
        phraseRow.setTextColor(if (asks) green else Color.WHITE)

        val ai = AiCaptions.isOn(this)
        aiRow.text = if (ai) "✓  Legendas com IA: ativadas (chave …${AiCaptions.key(this).takeLast(4)})"
        else "✨  Ativar legendas com IA (chave da OpenAI)"
        aiRow.setTextColor(if (ai) green else Color.WHITE)

        val times = Reminders.times(this)
        reminderRow.text = if (times.isEmpty()) "⏰  Criar lembretes de postagem"
        else "⏰  Lembretes de postagem: ${times.joinToString(", ")}"
        reminderRow.setTextColor(if (times.isEmpty()) Color.WHITE else green)

        val tags = Captions.hashtags(this)
        tagsRow.text = if (tags.isBlank()) "＃  Definir hashtags fixas para as legendas" else "＃  $tags"

        val count = Templates.list(this).size
        templatesRow.text = if (count == 0) "✎  Criar modelos de legenda" else "✎  Modelos de legenda ($count)"
    }

    private fun setFilter(unposted: Boolean) {
        onlyUnposted = unposted
        filterAll.background = rounded(if (unposted) card else accent, 12)
        filterUnposted.background = rounded(if (unposted) accent else card, 12)
        applyFilter()
    }

    private fun applyFilter() {
        shown = if (onlyUnposted) all.filter { Posted.get(this, it.uri).isEmpty() } else all
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (all.isEmpty()) {
            "Nada por aqui ainda.\n\nNo Pinterest, Instagram ou TikTok, toque em Compartilhar → Copiar link, " +
                "ou escolha \"Baixar com PinSave\" na lista de apps."
        } else {
            "Tudo que você baixou já foi postado."
        }
        adapter.notifyDataSetChanged()
    }

    /** Com o app em foco dá para ler a área de transferência: oferece o link copiado. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val link = Clip.readLink(this) ?: return
        val prefs = getSharedPreferences("pinsave", Context.MODE_PRIVATE)
        if (prefs.getString("last_clip", null) == link) return
        prefs.edit().putString("last_clip", link).apply()
        startActivity(
            Intent(this, PopupActivity::class.java)
                .putExtra(PopupActivity.EXTRA_LINK, link)
                .putExtra(PopupActivity.EXTRA_ASK, true)
        )
    }

    private fun startDownload() {
        val links = Links.findLinks(input.text)
        if (links.isEmpty()) {
            toast("Cole um link do Pinterest, Instagram ou TikTok")
            return
        }
        getSharedPreferences("pinsave", Context.MODE_PRIVATE).edit().putString("last_clip", links.last()).apply()
        input.setText("")
        if (links.size == 1) {
            startActivity(Intent(this, PopupActivity::class.java).putExtra(PopupActivity.EXTRA_LINK, links[0]))
        } else {
            links.forEach { DownloadService.enqueue(this, it, null) }
            toast("${links.size} downloads na fila. Eles aparecem na lista quando terminarem.")
        }
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
            toast("Pronto: copie o link, puxe a barra de notificações e toque em \"Copiou um link?\"")
        }
        updateRows()
    }

    /** Lista os horários de lembrete: tocar em um apaga, e o último item adiciona. */
    private fun manageReminders() {
        val times = Reminders.times(this)
        val labels = times.map { "$it  —  tocar para remover" } + "+ Adicionar horário"
        AlertDialog.Builder(this)
            .setTitle("Lembretes de postagem")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < times.size) {
                    Reminders.remove(this, times[which])
                    updateRows()
                } else {
                    TimePickerDialog(this, { _, hour, minute ->
                        Reminders.add(this, hour, minute)
                        updateRows()
                        // sem a permissão de notificações o lembrete não aparece
                        if (Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                        }
                    }, 18, 0, true).show()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    /** Bolinha sobre os outros apps: copiou o link, toca nela e o download começa. */
    private fun toggleBubble() {
        if (BubbleService.isOn(this)) {
            BubbleService.setOn(this, false)
        } else if (Settings.canDrawOverlays(this)) {
            BubbleService.setOn(this, true)
            toast("Pronto: copie o link e toque na bolinha. Arraste para mudar de lugar.")
        } else {
            // fica marcado como ligado; a bolinha aparece ao voltar com a permissão dada
            getSharedPreferences("pinsave", Context.MODE_PRIVATE).edit().putBoolean("bubble", true).apply()
            AlertDialog.Builder(this)
                .setTitle("Botão flutuante")
                .setMessage(
                    "Para a bolinha aparecer por cima dos outros apps, o Android pede a permissão " +
                        "\"Sobrepor a outros apps\".\n\nNa próxima tela, ative-a para o PinSave e volte.\n\n" +
                        "Se a opção estiver cinza ou aparecer \"Configuração restrita\": Configurações → Apps → " +
                        "PinSave → menu ⋮ → Permitir configurações restritas, e tente de novo."
                )
                .setPositiveButton("Abrir configurações") { _, _ ->
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    } catch (e: Exception) {
                        toast("Abra Configurações → Apps → PinSave → Sobrepor a outros apps")
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        updateRows()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Shortcut.setOn(this, true)
        } else {
            toast("Sem a permissão de notificações o atalho não aparece")
        }
        updateRows()
    }

    /** Diálogo simples com um campo de texto de várias linhas. */
    private fun editText(
        title: String, hint: String, current: String,
        templates: Boolean = false, onDelete: (() -> Unit)? = null, onSave: (String) -> Unit
    ) {
        val box = CaptionUi.box(this, current, hint, null, templates)
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box.view)
            .setPositiveButton("Salvar") { _, _ -> onSave(box.field.text.toString()) }
            .setNegativeButton("Cancelar", null)
        if (onDelete != null) builder.setNeutralButton("Excluir") { _, _ -> onDelete() }
        builder.show()
    }

    private fun editCaption(item: Item) {
        editText("Legenda", "Escreva a legenda desta publicação", Captions.get(this, item.uri), templates = true) {
            Captions.set(this, item.uri, it)
            adapter.notifyDataSetChanged()
        }
    }

    private fun manageTemplates() {
        val list = Templates.list(this)
        val labels = list.map { Templates.label(it) } + "+ Novo modelo"
        AlertDialog.Builder(this)
            .setTitle("Modelos de legenda")
            .setItems(labels.toTypedArray()) { _, which ->
                val index = if (which < list.size) which else null
                editText(
                    if (index == null) "Novo modelo" else "Editar modelo",
                    "Texto que você reaproveita nas legendas",
                    if (index == null) "" else list[index],
                    onDelete = if (index == null) null else {
                        {
                            Templates.put(this, index, "")
                            updateRows()
                        }
                    }
                ) {
                    Templates.put(this, index, it)
                    updateRows()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun refresh() {
        worker.execute {
            val found = Library.list(applicationContext)
            main.post {
                all = found
                applyFilter()
            }
        }
    }

    private fun loadThumb(item: Item, into: ImageView) {
        val key = Keys.of(item.uri)
        into.tag = key
        val cached = thumbs[key]
        into.setImageBitmap(cached)
        if (cached != null) return
        val side = dp(72)
        worker.execute {
            val bmp = try {
                contentResolver.loadThumbnail(item.uri, Size(side * 2, side * 2), null)
            } catch (e: Exception) {
                null
            } ?: return@execute
            main.post {
                thumbs[key] = bmp
                if (into.tag == key) into.setImageBitmap(bmp)
            }
        }
    }

    private fun open(item: Item) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(item.uri, if (item.isImage) "image/*" else "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("Nenhum app encontrado para abrir o arquivo")
        }
    }

    private fun showMenu(anchor: View, item: Item) {
        val posted = Posted.get(this, item.uri).isNotEmpty()
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, if (item.isImage) "Ver imagem" else "Assistir")
            menu.add(0, 2, 1, "Editar legenda")
            menu.add(0, 6, 1, if (item.isImage) "Adicionar frase na imagem" else "Adicionar frase no vídeo")
            if (posted) menu.add(0, 3, 2, "Desmarcar como postado")
            menu.add(0, 4, 3, "Compartilhar em outro app")
            menu.add(0, 5, 4, "Excluir")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> open(item)
                    2 -> editCaption(item)
                    6 -> editText("Frase", "Frase que aparece no meio, em branco", "") { text ->
                        if (text.isBlank()) {
                            toast("Escreva a frase")
                        } else {
                            DownloadService.enqueuePhrase(this@MainActivity, item.uri, text.trim())
                            toast("Gravando a frase. O item é atualizado quando terminar.")
                        }
                    }
                    3 -> {
                        Posted.clear(this@MainActivity, item.uri)
                        applyFilter()
                    }
                    4 -> Share.post(this@MainActivity, listOf(item.uri), Target.OTHER)
                    5 -> if (Library.delete(this@MainActivity, item)) refresh() else toast("Não foi possível excluir")
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

    private class Row(
        val root: View, val thumb: ImageView, val title: TextView, val sub: TextView, val badges: TextView,
        val caption: TextView, val more: TextView, val insta: Button, val tiktok: Button
    )

    private inner class ItemAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView?.tag as? Row) ?: buildRow()
            val item = shown[position]
            val ctx = this@MainActivity
            row.title.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(item.addedSec * 1000))
            row.sub.text = (if (item.isImage) "Imagem · " else "Vídeo · ") +
                Formatter.formatShortFileSize(ctx, item.size)

            val posted = Posted.get(ctx, item.uri)
            row.badges.text = Target.values().filter { it.name in posted }.joinToString("   ") { "✓ ${it.label}" }
            row.badges.visibility = if (posted.isEmpty()) View.GONE else View.VISIBLE

            val caption = Captions.get(ctx, item.uri)
            row.caption.text = caption.ifEmpty { "Toque para escrever a legenda" }
            row.caption.setTextColor(if (caption.isEmpty()) textDim else Color.parseColor("#D5D8DE"))
            row.caption.setOnClickListener { editCaption(item) }

            loadThumb(item, row.thumb)
            row.thumb.setOnClickListener { open(item) }
            row.more.setOnClickListener { showMenu(it, item) }
            row.insta.setOnClickListener { Share.chooseInstagram(ctx, listOf(item.uri)) }
            row.tiktok.setOnClickListener { Share.post(ctx, listOf(item.uri), Target.TIKTOK) }
            return row.root
        }

        private fun buildRow(): Row {
            val ctx = this@MainActivity
            val thumb = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.parseColor("#2A2D34"), 10)
                clipToOutline = true
                contentDescription = "Abrir arquivo"
            }
            val title = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            val sub = TextView(ctx).apply {
                setTextColor(textDim)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            val badges = TextView(ctx).apply {
                setTextColor(green)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
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
                addView(badges)
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
            return Row(wrapper, thumb, title, sub, badges, caption, more, insta, tiktok).also { wrapper.tag = it }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
