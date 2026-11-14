package com.codaxi.pinsave

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class Video(val uri: Uri, val name: String, val addedSec: Long, val size: Long)

/** Baixa vídeos para a galeria (Movies/PinSave) e lista o que já foi baixado. */
object Videos {
    private const val DIR = "Movies/PinSave"

    /** Bloqueante. [onProgress] recebe 0..100, ou -1 quando o tamanho é desconhecido. */
    fun download(ctx: Context, link: String, onProgress: (Int) -> Unit): Uri {
        // A legenda inicial vem do título/descrição do pin e fica guardada por vídeo.
        onProgress(-1)
        val resolved = PinterestExtractor.resolve(link)
        var last: Exception = PinterestExtractor.NoVideoException()
        for (videoUrl in resolved.videoUrls) {
            try {
                val uri = save(ctx, videoUrl, resolved.pinId, onProgress)
                Captions.set(ctx, uri, resolved.caption)
                return uri
            } catch (e: IOException) {
                last = e
            }
        }
        throw last
    }

    private fun save(ctx: Context, videoUrl: String, pinId: String?, onProgress: (Int) -> Unit): Uri {
        val conn = URL(videoUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", PinterestExtractor.USER_AGENT)
        conn.setRequestProperty("Referer", "https://www.pinterest.com/")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Servidor respondeu $code")
            if (conn.contentType?.startsWith("text") == true) throw IOException("Resposta não é um vídeo")
            val total = conn.contentLengthLong

            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "pin_${pinId ?: "video"}_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, DIR)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: throw IOException("Não foi possível criar o arquivo")
            try {
                var written = 0L
                var lastPct = -2
                val out = resolver.openOutputStream(uri) ?: throw IOException("Não foi possível gravar o arquivo")
                out.use { o ->
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
                if (written < 1024) throw IOException("Arquivo vazio")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw if (e is IOException) e else IOException(e)
            }
        } finally {
            conn.disconnect()
        }
    }

    fun list(ctx: Context): List<Video> {
        val out = ArrayList<Video>()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE
        )
        try {
            ctx.contentResolver.query(
                base, cols,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("$DIR%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Video(
                            ContentUris.withAppendedId(base, c.getLong(0)),
                            c.getString(1) ?: "video.mp4",
                            c.getLong(2),
                            c.getLong(3)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // sem acesso à galeria: lista vazia
        }
        return out
    }

    fun delete(ctx: Context, video: Video): Boolean = try {
        (ctx.contentResolver.delete(video.uri, null, null) > 0).also { if (it) Captions.set(ctx, video.uri, "") }
    } catch (e: Exception) {
        false
    }
}

/** Legenda de cada vídeo e as hashtags fixas, guardadas no aparelho. */
object Captions {
    private const val HASHTAGS = "__hashtags"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("captions", Context.MODE_PRIVATE)

    fun get(ctx: Context, uri: Uri): String = prefs(ctx).getString(uri.toString(), "") ?: ""

    fun set(ctx: Context, uri: Uri, text: String) {
        val e = prefs(ctx).edit()
        if (text.isBlank()) e.remove(uri.toString()) else e.putString(uri.toString(), text.trim())
        e.apply()
    }

    fun hashtags(ctx: Context): String = prefs(ctx).getString(HASHTAGS, "") ?: ""

    fun setHashtags(ctx: Context, text: String) = prefs(ctx).edit().putString(HASHTAGS, text.trim()).apply()

    /** O que vai para a área de transferência: legenda do vídeo + hashtags fixas. */
    fun full(ctx: Context, uri: Uri): String =
        listOf(get(ctx, uri), hashtags(ctx)).filter { it.isNotBlank() }.joinToString("\n\n")
}

enum class Target(val label: String, val packages: List<String>) {
    INSTAGRAM("Instagram", listOf("com.instagram.android")),
    TIKTOK("TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go")),
    OTHER("Outro app", emptyList())
}

object Share {
    /** Abre o app escolhido já com o vídeo anexado, na tela de criar publicação. */
    fun post(activity: Activity, uri: Uri, target: Target) {
        val base = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        base.clipData = ClipData.newRawUri(null, uri)

        // Instagram e TikTok não aceitam legenda vinda de outro app, então ela
        // vai para a área de transferência para ser colada na publicação.
        val caption = Captions.full(activity, uri)
        if (caption.isNotEmpty()) {
            base.putExtra(Intent.EXTRA_TEXT, caption)
            try {
                activity.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Legenda", caption))
                Toast.makeText(activity, "Legenda copiada — cole na publicação", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                // sem acesso à área de transferência: segue sem a legenda
            }
        }

        if (target == Target.OTHER) {
            activity.startActivity(Intent.createChooser(base, "Compartilhar vídeo"))
            return
        }
        for (pkg in target.packages) {
            try {
                activity.startActivity(Intent(base).setPackage(pkg))
                return
            } catch (e: ActivityNotFoundException) {
                // tenta o próximo pacote
            }
        }
        Toast.makeText(activity, "${target.label} não está instalado", Toast.LENGTH_LONG).show()
    }
}

object Clip {
    /** Link do Pinterest na área de transferência (só funciona com o app em foco). */
    fun readLink(ctx: Context): String? {
        val clip = try {
            ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
        } catch (e: Exception) {
            null
        } ?: return null
        for (i in 0 until clip.itemCount) {
            PinterestExtractor.findLink(clip.getItemAt(i)?.coerceToText(ctx))?.let { return it }
        }
        return null
    }
}
