package com.codaxi.pinsave

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Grava no arquivo a logo (marca d'água meio transparente, centralizada na
 * parte de baixo) e a frase (texto branco no meio do quadro, que surge aos
 * poucos nos vídeos). Vídeos precisam ser recodificados.
 */
object Watermark {
    private const val WIDTH_FRACTION = 0.14f   // largura da logo em relação ao quadro
    private const val CENTER_Y = 0.75f         // altura do centro da logo (0 = topo, 1 = base)
    private const val ALPHA = 128              // 50% de transparência
    private const val PHRASE_SIZE = 0.036f     // altura da letra em relação ao quadro
    private const val PHRASE_WIDTH = 0.72f     // largura máxima da frase
    private const val FADE_START = 0.4f        // segundo em que a frase começa a surgir
    private const val FADE_LENGTH = 1.4f       // duração do surgimento, em segundos

    class WatermarkException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pinsave", Context.MODE_PRIVATE)

    fun isOn(ctx: Context) = prefs(ctx).getBoolean("watermark", true)

    fun setOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("watermark", on).apply()

    /** Se o app pede a frase antes de cada download. */
    fun asksPhrase(ctx: Context) = prefs(ctx).getBoolean("ask_phrase", true)

    fun setAsksPhrase(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("ask_phrase", on).apply()

    private fun drawLogo(ctx: Context, canvas: Canvas, w: Int, h: Int) {
        val logo = BitmapFactory.decodeResource(
            ctx.resources, R.drawable.watermark, BitmapFactory.Options().apply { inScaled = false }
        ) ?: throw WatermarkException("Não foi possível carregar a logo")
        val lw = (minOf(w, h) * WIDTH_FRACTION).toInt().coerceAtLeast(1)
        val lh = (lw.toFloat() * logo.height / logo.width).toInt().coerceAtLeast(1)
        val left = (w - lw) / 2
        val top = (h * CENTER_Y).toInt() - lh / 2
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { alpha = ALPHA }
        canvas.drawBitmap(logo, null, Rect(left, top, left + lw, top + lh), paint)
        logo.recycle()
    }

    /** Frase branca, pequena, centralizada no meio do quadro, com sombra para ler em qualquer fundo. */
    private fun drawPhrase(canvas: Canvas, w: Int, h: Int, phrase: String) {
        val size = minOf(w, h) * PHRASE_SIZE
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(size * 0.22f, 0f, size * 0.05f, Color.argb(220, 0, 0, 0))
        }
        val width = (w * PHRASE_WIDTH).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(phrase, 0, phrase.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate((w - width) / 2f, (h - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Escurece e tira um pouco da cor, para o visual "dark" do conteúdo criado no app. */
    private fun darkPaint(): Paint {
        val m = ColorMatrix().apply { setSaturation(0.55f) }
        val scale = 0.72f
        m.postConcat(ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, 0f,
            0f, scale, 0f, 0f, 0f,
            0f, 0f, scale, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
        return Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) }
    }

    /**
     * Grava em [dst] a imagem [src] com a logo e/ou a frase. [aspect]
     * (largura/altura) recorta pelo centro, como nos slides de carrossel.
     * Devolve a extensão do resultado.
     */
    fun image(
        ctx: Context, src: File, dst: File, ext: String, logo: Boolean, phrase: String,
        dark: Boolean = false, aspect: Float? = null
    ): String {
        try {
            val original = BitmapFactory.decodeFile(src.absolutePath)
                ?: throw WatermarkException("Não foi possível abrir a imagem para editar")
            var sw = original.width
            var sh = original.height
            if (aspect != null) {
                if (sw.toFloat() / sh > aspect) sw = (sh * aspect).toInt() else sh = (sw / aspect).toInt()
            }
            val from = Rect(
                (original.width - sw) / 2, (original.height - sh) / 2,
                (original.width + sw) / 2, (original.height + sh) / 2
            )
            // slides de carrossel saem sempre com 1080 de largura
            val w = if (aspect != null) 1080 else sw
            val h = if (aspect != null) (1080 / aspect).toInt() else sh
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawBitmap(original, from, Rect(0, 0, w, h), if (dark) darkPaint() else Paint(Paint.FILTER_BITMAP_FLAG))
            original.recycle()
            if (logo) drawLogo(ctx, canvas, w, h)
            if (phrase.isNotBlank()) drawPhrase(canvas, w, h, phrase.trim())
            val png = ext == "png" && aspect == null && !dark
            FileOutputStream(dst).use {
                bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it)
            }
            bitmap.recycle()
            return if (png) "png" else "jpg"
        } catch (e: WatermarkException) {
            throw e
        } catch (e: Throwable) {
            throw WatermarkException("Não foi possível gravar a logo ou a frase na imagem", e)
        }
    }

    /** Camada da frase: invisível no começo e surgindo aos poucos. */
    private class FadeOverlay(private val bitmap: Bitmap) : BitmapOverlay() {
        override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val t = ((presentationTimeUs / 1_000_000f - FADE_START) / FADE_LENGTH).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            return OverlaySettings.Builder().setAlphaScale(eased).build()
        }
    }

    /**
     * Recodifica [src] em [dst] com a logo e/ou a frase. A frase surge com
     * um efeito de opacidade nos primeiros segundos. Bloqueante; [onProgress]
     * recebe 0..100.
     */
    fun video(
        ctx: Context, src: File, dst: File, logo: Boolean, phrase: String,
        dark: Boolean = false, onProgress: (Int) -> Unit
    ) {
        val fail = "Não foi possível gravar a logo ou a frase neste vídeo. " +
            "Tente sem frase e com a marca d'água desativada."
        val (w, h) = dimensions(src) ?: throw WatermarkException(fail)

        // Quadros transparentes do tamanho do vídeo: um com a logo, outro com a frase.
        val overlays = ArrayList<TextureOverlay>()
        try {
            if (logo) {
                val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                drawLogo(ctx, Canvas(frame), w, h)
                overlays.add(BitmapOverlay.createStaticBitmapOverlay(frame))
            }
            if (phrase.isNotBlank()) {
                val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                drawPhrase(Canvas(frame), w, h, phrase.trim())
                overlays.add(FadeOverlay(frame))
            }
        } catch (e: Throwable) {
            throw WatermarkException(fail, e)
        }

        export(ctx, dst, fail, onProgress) { t ->
            val effects = ArrayList<Effect>()
            if (dark) {
                effects.add(Brightness(-0.10f))
                effects.add(Contrast(0.12f))
                effects.add(HslAdjustment.Builder().adjustSaturation(-30f).build())
            }
            if (overlays.isNotEmpty()) effects.add(OverlayEffect(ImmutableList.copyOf(overlays)))
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
                .setEffects(Effects(emptyList(), effects))
                .build()
            t.start(item, dst.absolutePath)
        }
    }

    /**
     * Roda uma exportação do Transformer até o fim. [start] recebe o
     * Transformer já criado e deve iniciar o trabalho gravando em [dst].
     * Bloqueante; [onProgress] recebe 0..100.
     */
    internal fun export(
        ctx: Context, dst: File, fail: String, onProgress: (Int) -> Unit, start: (Transformer) -> Unit
    ) {
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        var transformer: Transformer? = null
        // O Transformer precisa ser criado e usado numa thread com Looper.
        main.post {
            try {
                val t = Transformer.Builder(ctx).build()
                transformer = t
                t.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        latch.countDown()
                    }

                    override fun onError(
                        composition: Composition, exportResult: ExportResult, exportException: ExportException
                    ) {
                        error = exportException
                        latch.countDown()
                    }
                })
                start(t)

                val holder = ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (latch.count == 0L) return
                        if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                        main.postDelayed(this, 400)
                    }
                }
                main.postDelayed(poll, 400)
            } catch (e: Throwable) {
                error = e
                latch.countDown()
            }
        }
        val finished = latch.await(20, TimeUnit.MINUTES)
        if (!finished) main.post { try { transformer?.cancel() } catch (e: Exception) { } }
        if (!finished || error != null || dst.length() < 1024) throw WatermarkException(fail, error)
    }

    /** Largura e altura do vídeo como ele é exibido (já considerando a rotação). */
    private fun dimensions(file: File): Pair<Int, Int>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w == null || h == null || w <= 0 || h <= 0) null
            else if (rot == 90 || rot == 270) h to w else w to h
        } catch (e: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // nada a fazer
            }
        }
    }
}
