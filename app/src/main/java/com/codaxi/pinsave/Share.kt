package com.codaxi.pinsave

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

enum class Target(val label: String, val packages: List<String>) {
    INSTAGRAM("Instagram", listOf("com.instagram.android")),
    TIKTOK("TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go")),
    OTHER("Outro app", emptyList())
}

/** Para onde ir dentro do Instagram. [action] null usa o seletor do próprio Instagram. */
enum class InstaMode(val label: String, val action: String?) {
    REELS("Reels", "com.instagram.share.ADD_TO_REEL"),
    STORY("Stories", "com.instagram.share.ADD_TO_STORY"),
    FEED("Feed", "com.instagram.share.ADD_TO_FEED"),
    CHOOSE("Escolher dentro do Instagram", null)
}

object Share {
    /**
     * Abre o app escolhido com os arquivos anexados e copia a legenda.
     * Devolve false se o app não está instalado.
     */
    fun post(activity: Activity, uris: List<Uri>, target: Target, mode: InstaMode = InstaMode.CHOOSE): Boolean {
        if (uris.isEmpty()) return false
        val mime = when {
            uris.all { Keys.isImage(it) } -> "image/*"
            uris.none { Keys.isImage(it) } -> "video/mp4"
            else -> "*/*"
        }
        val generic = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        generic.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        generic.clipData = clipOf(uris)

        // Instagram e TikTok não aceitam legenda vinda de outro app, então ela
        // vai para a área de transferência para ser colada na publicação.
        val caption = Captions.full(activity, uris[0])
        if (caption.isNotEmpty()) {
            generic.putExtra(Intent.EXTRA_TEXT, caption)
            try {
                activity.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Legenda", caption))
            } catch (e: Exception) {
                // sem acesso à área de transferência: segue sem a legenda
            }
        }

        if (target == Target.OTHER) {
            return start(activity, Intent.createChooser(generic, "Compartilhar"))
        }
        var launched = false
        for (pkg in target.packages) {
            val action = mode.action
            if (target == Target.INSTAGRAM && action != null && uris.size == 1) {
                val direct = Intent(action).setPackage(pkg).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (mode == InstaMode.STORY) {
                    direct.setDataAndType(uris[0], activity.contentResolver.getType(uris[0]) ?: mime)
                } else {
                    direct.setType(mime).putExtra(Intent.EXTRA_STREAM, uris[0])
                }
                direct.clipData = clipOf(uris)
                if (start(activity, direct)) {
                    launched = true
                    break
                }
            }
            if (start(activity, Intent(generic).setPackage(pkg))) {
                launched = true
                break
            }
        }
        if (!launched) {
            Toast.makeText(activity, "${target.label} não está instalado", Toast.LENGTH_LONG).show()
            return false
        }
        uris.forEach { Posted.mark(activity, it, target) }
        if (caption.isNotEmpty()) {
            Toast.makeText(activity, "Legenda copiada — cole na publicação", Toast.LENGTH_LONG).show()
        }
        return true
    }

    /** Pergunta "Reels, Stories ou Feed?" antes de abrir o Instagram. */
    fun chooseInstagram(activity: Activity, uris: List<Uri>, onClosed: () -> Unit = {}) {
        if (uris.size > 1) {
            post(activity, uris, Target.INSTAGRAM)
            onClosed()
            return
        }
        val modes = if (Keys.isImage(uris[0])) {
            listOf(InstaMode.STORY, InstaMode.FEED, InstaMode.CHOOSE)
        } else {
            listOf(InstaMode.REELS, InstaMode.STORY, InstaMode.FEED, InstaMode.CHOOSE)
        }
        AlertDialog.Builder(activity)
            .setTitle("Postar no Instagram em…")
            .setItems(modes.map { it.label }.toTypedArray()) { _, which ->
                post(activity, uris, Target.INSTAGRAM, modes[which])
                onClosed()
            }
            .setOnCancelListener { onClosed() }
            .show()
    }

    private fun clipOf(uris: List<Uri>): ClipData {
        val clip = ClipData.newRawUri(null, uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return clip
    }

    private fun start(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

/** Campo de legenda com o atalho "usar modelo", usado no pop-up e na lista. */
object CaptionUi {
    class Box(val view: View, val field: EditText)

    fun box(activity: Activity, initial: String, hint: String, note: String? = null, templates: Boolean = true): Box {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val field = EditText(activity).apply {
            setText(initial)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 7
            gravity = Gravity.TOP or Gravity.START
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        if (templates && Templates.list(activity).isNotEmpty()) {
            layout.addView(TextView(activity).apply {
                text = "Usar modelo de legenda ▾"
                textSize = 14f
                setTextColor(Color.parseColor("#FF5A5F"))
                setPadding(0, pad / 3, 0, pad / 3)
                setOnClickListener { pick(activity, field) }
            })
        }
        layout.addView(
            field,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        if (note != null) {
            layout.addView(TextView(activity).apply {
                text = note
                textSize = 12f
                alpha = 0.7f
            })
        }
        return Box(layout, field)
    }

    private fun pick(activity: Activity, field: EditText) {
        val list = Templates.list(activity)
        AlertDialog.Builder(activity)
            .setTitle("Modelos de legenda")
            .setItems(list.map { Templates.label(it) }.toTypedArray()) { _, which ->
                val current = field.text.toString().trim()
                field.setText(if (current.isEmpty()) list[which] else current + "\n\n" + list[which])
            }
            .show()
    }
}
