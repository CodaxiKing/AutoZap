package com.codaxi.pinsave

import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Um vídeo ou imagem baixado. */
class Item(val uri: Uri, val name: String, val addedSec: Long, val size: Long, val isImage: Boolean)

/** [watermark] indica a etapa de aplicar a logo, depois do download. */
class Progress(val index: Int, val count: Int, val pct: Int, val watermark: Boolean = false)

/** Baixa os arquivos de um pin para a galeria e lista o que já foi baixado. */
object Library {
    private const val VIDEO_DIR = "Movies/PinSave"
    private const val IMAGE_DIR = "Pictures/PinSave"
    private const val AUDIO_DIR = "Music/PinSave"

    /**
     * Bloqueante. Devolve um arquivo para pins comuns e vários para carrosséis.
     * [Progress.pct] vai de 0 a 100, ou -1 quando o tamanho é desconhecido.
     */
    fun download(ctx: Context, link: String, phrase: String?, onProgress: (Progress) -> Unit): List<Uri> {
        onProgress(Progress(0, 1, -1))
        val resolved = Links.resolve(link)
        val count = resolved.media.size
        val out = ArrayList<Uri>()
        var last: IOException = PinterestExtractor.NoVideoException()

        resolved.media.forEachIndexed { index, media ->
            for (url in media.urls) {
                try {
                    val uri = save(ctx, url, resolved.pinId, media.isVideo, index, phrase, resolved.headers) { pct, watermark ->
                        onProgress(Progress(index, count, pct, watermark))
                    }
                    Captions.set(ctx, uri, resolved.caption)
                    out.add(uri)
                    break
                } catch (e: Watermark.WatermarkException) {
                    // o arquivo baixou; tentar outro endereço não resolveria
                    throw e
                } catch (e: IOException) {
                    last = e
                }
            }
        }
        if (out.isEmpty()) throw last
        // com a chave da OpenAI configurada, a legenda original vira só referência
        AiCaptions.auto(ctx, out, phrase.orEmpty(), resolved.caption)
        return out
    }

    /**
     * Monta conteúdo novo a partir do banco: cada item é (endereço do
     * arquivo, frase). Aplica o visual escuro, a frase e a logo; imagens são
     * recortadas no formato de carrossel.
     */
    fun generate(
        ctx: Context, clips: List<Pair<String, String>>, isVideo: Boolean, caption: String, tags: String,
        onProgress: (Progress) -> Unit
    ): List<Uri> {
        val out = ArrayList<Uri>()
        clips.forEachIndexed { index, (url, phrase) ->
            val uri = save(ctx, url, "criado", isVideo, index, phrase, emptyMap(), dark = true, carousel = !isVideo) {
                    pct, watermark -> onProgress(Progress(index, clips.size, pct, watermark))
            }
            out.add(uri)
        }
        // A legenda termina sempre com as hashtags do tema.
        val withTags = { text: String -> listOf(text.trim(), tags.trim()).filter { it.isNotEmpty() }.joinToString("\n\n") }
        val typed = caption.trim()
        out.forEach { Captions.set(ctx, it, withTags(typed)) }
        // Se a legenda é só a frase (com ou sem texto extra), a IA escreve uma de verdade.
        val first = clips.firstOrNull()?.second.orEmpty().trim()
        if (typed.isEmpty() || typed.startsWith(first)) {
            AiCaptions.auto(
                ctx, out, clips.joinToString(" / ") { it.second }, "",
                suffix = withTags(typed.removePrefix(first)), noHashtags = tags.isNotBlank()
            )
        }
        return out
    }

    /**
     * Monta um vídeo com vários trechos do banco em sequência sobre a música.
     * Cada parte é (endereço do vídeo, segundos). Depois de montado, recebe a
     * frase e a logo como qualquer outro vídeo.
     */
    fun montage(
        ctx: Context, music: File, parts: List<Pair<String, Float>>, phrase: String, caption: String, tags: String,
        onProgress: (Progress) -> Unit
    ): List<Uri> {
        val temps = ArrayList<File>()
        try {
            val byUrl = HashMap<String, File>()   // o mesmo fundo pode aparecer em mais de uma parte
            val clips = parts.mapIndexed { i, (url, seconds) ->
                val file = byUrl.getOrPut(url) {
                    File.createTempFile("pinsave_clip", ".mp4", ctx.cacheDir).also { f ->
                        temps.add(f)
                        fetch(url, f, emptyMap()) { onProgress(Progress(i, parts.size, it)) }
                    }
                }
                // trecho mais curto que a parte: usa o que ele tiver
                val length = Montage.duration(file)
                Montage.Part(file, if (length > 0.3f) minOf(seconds, length - 0.1f) else seconds)
            }
            val totalSeconds = clips.sumOf { it.seconds.toDouble() }.toFloat()
            val logo = Watermark.isOn(ctx)
            val text = phrase.trim()
            val stamp = logo || text.isNotEmpty()

            // 1) junta os trechos, sem som
            val cut = File.createTempFile("pinsave_cut", ".mp4", ctx.cacheDir).also { temps.add(it) }
            onProgress(Progress(0, 1, -1, true))
            Montage.concat(ctx, clips, cut) { onProgress(Progress(0, 1, if (stamp) it * 45 / 100 else it * 85 / 100, true)) }
            var silent = cut
            // 2) frase e logo
            if (stamp) {
                val marked = File.createTempFile("pinsave_wm", ".mp4", ctx.cacheDir).also { temps.add(it) }
                Watermark.video(ctx, cut, marked, logo, text) { onProgress(Progress(0, 1, 45 + it * 40 / 100, true)) }
                silent = marked
            }
            // 3) música em AAC; se a conversão falhar e ela já for AAC, usa como está
            val audio = File.createTempFile("pinsave_song", ".m4a", ctx.cacheDir).also { temps.add(it) }
            val song = try {
                Montage.prepareAudio(ctx, music, totalSeconds + 0.5f, audio) { onProgress(Progress(0, 1, 85 + it * 10 / 100, true)) }
                audio
            } catch (e: Watermark.WatermarkException) {
                if (Montage.hasAacAudio(music)) music else throw e
            }
            // 4) encaixa a música no vídeo, sem recodificar
            val result = File.createTempFile("pinsave_final", ".mp4", ctx.cacheDir).also { temps.add(it) }
            Montage.mux(silent, song, result)
            onProgress(Progress(0, 1, 100, true))
            val uri = insert(ctx, result, "montagem", true, 0, "mp4")
            val out = listOf(uri)
            val withTags = { s: String -> listOf(s.trim(), tags.trim()).filter { it.isNotEmpty() }.joinToString("\n\n") }
            val typed = caption.trim()
            Captions.set(ctx, uri, withTags(typed))
            if (typed.isEmpty() || (text.isNotEmpty() && typed.startsWith(text))) {
                AiCaptions.auto(ctx, out, text, "", suffix = withTags(typed.removePrefix(text)), noHashtags = tags.isNotBlank())
            }
            return out
        } finally {
            temps.forEach { it.delete() }
        }
    }

    /** Extrai só o áudio de um vídeo e salva em Music/PinSave. */
    fun extractAudio(ctx: Context, source: Uri, onProgress: (Progress) -> Unit): Uri {
        val tmp = File.createTempFile("pinsave_audio", ".m4a", ctx.cacheDir)
        try {
            onProgress(Progress(0, 1, -1, true))
            Montage.extractAudio(ctx, source, tmp) { onProgress(Progress(0, 1, it, true)) }
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "pinsave_audio_${System.currentTimeMillis()}.m4a")
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, AUDIO_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: throw IOException("Não foi possível criar o arquivo de áudio")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Não foi possível gravar o áudio")
                out.use { o -> tmp.inputStream().use { it.copyTo(o, 65536) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw if (e is IOException) e else IOException(e)
            }
        } finally {
            tmp.delete()
        }
    }

    /** Áudios extraídos pelo app, do mais novo para o mais antigo. */
    fun audios(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        query(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, AUDIO_DIR, false, out)
        out.sortByDescending { it.addedSec }
        return out
    }

    fun deleteAudio(ctx: Context, item: Item): Boolean = try {
        ctx.contentResolver.delete(item.uri, null, null) > 0
    } catch (e: Exception) {
        false
    }

    private fun save(
        ctx: Context, url: String, pinId: String?, isVideo: Boolean, index: Int, phrase: String?,
        headers: Map<String, String>, dark: Boolean = false, carousel: Boolean = false,
        onProgress: (pct: Int, watermark: Boolean) -> Unit
    ): Uri {
        // Baixa para um arquivo temporário, edita e só então grava na galeria.
        val raw = File.createTempFile("pinsave_dl", null, ctx.cacheDir)
        var marked: File? = null
        try {
            var ext = fetch(url, raw, headers) { onProgress(it, false) }
            var file = raw
            val logo = Watermark.isOn(ctx)
            val text = phrase?.trim().orEmpty()
            val edits = logo || text.isNotEmpty() || dark || carousel
            if (edits && (isVideo || ext != "gif")) {
                val out = File.createTempFile("pinsave_wm", if (isVideo) ".mp4" else ".img", ctx.cacheDir)
                marked = out
                onProgress(-1, true)
                if (isVideo) {
                    Watermark.video(ctx, raw, out, logo, text, dark) { onProgress(it, true) }
                } else {
                    ext = Watermark.image(ctx, raw, out, ext, logo, text, dark, if (carousel) 4f / 5f else null)
                }
                file = out
            }
            return insert(ctx, file, pinId, isVideo, index, ext)
        } finally {
            raw.delete()
            marked?.delete()
        }
    }

    /**
     * Grava uma frase num arquivo já baixado. O arquivo novo substitui o
     * antigo e herda a legenda e as marcas de "postado".
     */
    fun addPhrase(ctx: Context, uri: Uri, phrase: String, onProgress: (Progress) -> Unit): Uri {
        val isVideo = !Keys.isImage(uri)
        val resolver = ctx.contentResolver
        val raw = File.createTempFile("pinsave_src", null, ctx.cacheDir)
        var edited: File? = null
        try {
            onProgress(Progress(0, 1, -1, true))
            val input = resolver.openInputStream(uri) ?: throw IOException("Arquivo não encontrado")
            input.use { i -> raw.outputStream().use { i.copyTo(it, 65536) } }
            val out = File.createTempFile("pinsave_wm", if (isVideo) ".mp4" else ".img", ctx.cacheDir)
            edited = out
            val ext = if (isVideo) {
                Watermark.video(ctx, raw, out, false, phrase) { onProgress(Progress(0, 1, it, true)) }
                "mp4"
            } else {
                Watermark.image(ctx, raw, out, if (resolver.getType(uri) == "image/png") "png" else "jpg", false, phrase)
            }
            val created = insert(ctx, out, "frase", isVideo, 0, ext)
            Captions.set(ctx, created, Captions.get(ctx, uri))
            Posted.copy(ctx, uri, created)
            try {
                if (resolver.delete(uri, null, null) > 0) {
                    Captions.set(ctx, uri, "")
                    Posted.clear(ctx, uri)
                }
            } catch (e: Exception) {
                // não deu para apagar o antigo: ficam os dois na lista
            }
            return created
        } finally {
            raw.delete()
            edited?.delete()
        }
    }

    /** Baixa [url] para [dst]. Devolve a extensão do arquivo (só importa para imagens). */
    private fun fetch(url: String, dst: File, headers: Map<String, String>, onProgress: (Int) -> Unit): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", PinterestExtractor.USER_AGENT)
        if (url.contains("pinimg.com")) conn.setRequestProperty("Referer", "https://www.pinterest.com/")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Servidor respondeu $code")
            if (conn.contentType?.startsWith("text") == true) throw IOException("Resposta não é mídia")
            val total = conn.contentLengthLong
            var written = 0L
            var lastPct = -2
            dst.outputStream().use { o ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        written += n
                        val pct = if (total > 0) (written * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            if (written < 512) throw IOException("Arquivo vazio")
            // extensão do caminho, sem os parâmetros que vêm depois de "?"
            return url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "jpg").lowercase()
                .let { if (it in setOf("jpg", "jpeg", "png", "gif", "webp", "heic")) it else "jpg" }
        } finally {
            conn.disconnect()
        }
    }

    /** Copia [file] para a galeria. */
    private fun insert(ctx: Context, file: File, pinId: String?, isVideo: Boolean, index: Int, ext: String): Uri {
        val mime = when {
            isVideo -> "video/mp4"
            ext == "png" -> "image/png"
            ext == "gif" -> "image/gif"
            ext == "webp" -> "image/webp"
            ext == "heic" -> "image/heic"
            else -> "image/jpeg"
        }
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(
                MediaStore.MediaColumns.DISPLAY_NAME,
                "pinsave_${pinId ?: "midia"}_${System.currentTimeMillis()}_${index + 1}.${if (isVideo) "mp4" else ext}"
            )
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) VIDEO_DIR else IMAGE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Não foi possível criar o arquivo")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Não foi possível gravar o arquivo")
            out.use { o -> file.inputStream().use { it.copyTo(o, 65536) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw if (e is IOException) e else IOException(e)
        }
    }

    /** Tudo que o app baixou, do mais novo para o mais antigo. */
    fun list(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        query(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, VIDEO_DIR, false, out)
        query(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, IMAGE_DIR, true, out)
        out.sortByDescending { it.addedSec }
        return out
    }

    private fun query(ctx: Context, base: Uri, dir: String, isImage: Boolean, out: MutableList<Item>) {
        val cols = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE
        )
        try {
            ctx.contentResolver.query(
                base, cols, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$dir%"), null
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Item(
                            ContentUris.withAppendedId(base, c.getLong(0)),
                            c.getString(1) ?: "",
                            c.getLong(2),
                            c.getLong(3),
                            isImage
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // sem acesso à galeria: lista vazia
        }
    }

    fun delete(ctx: Context, item: Item): Boolean = try {
        (ctx.contentResolver.delete(item.uri, null, null) > 0).also {
            if (it) {
                Captions.set(ctx, item.uri, "")
                Posted.clear(ctx, item.uri)
            }
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Chave estável de um arquivo. O mesmo arquivo aparece com endereços
 * diferentes (external / external_primary), então usamos tipo + id.
 */
object Keys {
    fun isImage(uri: Uri) = uri.toString().contains("/images/")
    fun of(uri: Uri) = (if (isImage(uri)) "i" else "v") + (uri.lastPathSegment ?: "")
}

/** Legenda de cada arquivo e as hashtags fixas, guardadas no aparelho. */
object Captions {
    private const val HASHTAGS = "__hashtags"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("captions", Context.MODE_PRIVATE)

    fun get(ctx: Context, uri: Uri): String = prefs(ctx).getString(Keys.of(uri), "") ?: ""

    fun set(ctx: Context, uri: Uri, text: String) {
        val e = prefs(ctx).edit()
        if (text.isBlank()) e.remove(Keys.of(uri)) else e.putString(Keys.of(uri), text.trim())
        e.apply()
    }

    fun hashtags(ctx: Context): String = prefs(ctx).getString(HASHTAGS, "") ?: ""

    fun setHashtags(ctx: Context, text: String) = prefs(ctx).edit().putString(HASHTAGS, text.trim()).apply()

    /** O que vai para a área de transferência: legenda + hashtags fixas. */
    fun full(ctx: Context, uri: Uri): String =
        listOf(get(ctx, uri), hashtags(ctx)).filter { it.isNotBlank() }.joinToString("\n\n")
}

/** Legendas prontas para reaproveitar. */
object Templates {
    private const val KEY = "__templates"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("captions", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<String> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, items: List<String>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** [index] null cria um modelo novo; texto vazio apaga o modelo. */
    fun put(ctx: Context, index: Int?, text: String) {
        val items = list(ctx).toMutableList()
        val clean = text.trim()
        if (index == null) {
            if (clean.isNotEmpty()) items.add(clean)
        } else if (index in items.indices) {
            if (clean.isEmpty()) items.removeAt(index) else items[index] = clean
        }
        save(ctx, items)
    }

    fun label(text: String): String = text.lineSequence().firstOrNull().orEmpty().take(40)
}

/** Em quais redes cada arquivo já foi enviado para postar. */
object Posted {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("posted", Context.MODE_PRIVATE)

    fun get(ctx: Context, uri: Uri): Set<String> = prefs(ctx).getStringSet(Keys.of(uri), null) ?: emptySet()

    fun mark(ctx: Context, uri: Uri, target: Target) {
        val now = HashSet(get(ctx, uri))
        now.add(target.name)
        prefs(ctx).edit().putStringSet(Keys.of(uri), now).apply()
    }

    fun clear(ctx: Context, uri: Uri) = prefs(ctx).edit().remove(Keys.of(uri)).apply()

    fun copy(ctx: Context, from: Uri, to: Uri) {
        val marks = get(ctx, from)
        if (marks.isNotEmpty()) prefs(ctx).edit().putStringSet(Keys.of(to), HashSet(marks)).apply()
    }
}

object Clip {
    /** Link suportado na área de transferência (só funciona com o app em foco). */
    fun readLink(ctx: Context): String? {
        val clip = try {
            ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
        } catch (e: Exception) {
            null
        } ?: return null
        for (i in 0 until clip.itemCount) {
            Links.findLink(clip.getItemAt(i)?.coerceToText(ctx))?.let { return it }
        }
        return null
    }
}
