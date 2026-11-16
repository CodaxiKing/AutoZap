package com.codaxi.pinsave

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import java.io.File
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/** Monta um vídeo com vários trechos em sequência sobre uma música, e extrai áudio de vídeos. */
object Montage {
    /** Um trecho: o arquivo do vídeo e quantos segundos dele entram. */
    class Part(val file: File, val seconds: Float)

    private const val FAIL = "Não foi possível montar o vídeo neste aparelho. Tente com menos partes ou outra música."

    /**
     * Junta os trechos em 1080x1920, sem o som original, com a música por
     * cima. Bloqueante; [onProgress] recebe 0..100.
     */
    fun render(ctx: Context, parts: List<Part>, music: File, dst: File, onProgress: (Int) -> Unit) {
        val total = parts.sumOf { it.seconds.toDouble() }
        Watermark.export(ctx, dst, FAIL, onProgress) { transformer ->
            val clips = parts.map { part ->
                val item = MediaItem.Builder()
                    .setUri(Uri.fromFile(part.file))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionMs((part.seconds * 1000).toLong())
                            .build()
                    )
                    .build()
                // todos os trechos no mesmo formato vertical, com o visual escuro da página
                val effects = listOf<Effect>(
                    Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                    Brightness(-0.10f),
                    Contrast(0.12f),
                    HslAdjustment.Builder().adjustSaturation(-30f).build()
                )
                EditedMediaItem.Builder(item)
                    .setRemoveAudio(true)
                    .setEffects(Effects(emptyList(), effects))
                    .build()
            }
            val song = EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(Uri.fromFile(music))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setEndPositionMs((total * 1000).toLong()).build()
                    )
                    .build()
            ).setRemoveVideo(true).build()
            val composition = Composition.Builder(
                EditedMediaItemSequence(ImmutableList.copyOf(clips)),
                EditedMediaItemSequence(ImmutableList.of(song))
            ).build()
            transformer.start(composition, dst.absolutePath)
        }
    }

    /** Grava em [dst] só o áudio de [source] (um vídeo ou áudio qualquer). Bloqueante. */
    fun extractAudio(ctx: Context, source: Uri, dst: File, onProgress: (Int) -> Unit) {
        Watermark.export(ctx, dst, "Não foi possível extrair o áudio. O vídeo pode não ter som.", onProgress) { transformer ->
            transformer.start(
                EditedMediaItem.Builder(MediaItem.fromUri(source)).setRemoveVideo(true).build(),
                dst.absolutePath
            )
        }
    }

    /** Duração de um arquivo de mídia em segundos, ou 0 se não der para ler. */
    fun duration(file: File): Float {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000f
        } catch (e: Exception) {
            0f
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
                // nada a fazer
            }
        }
    }
}

/** Acha as batidas de uma música para os cortes do vídeo caírem nelas. */
object Beats {
    const val FPS = 100   // pontos da curva de energia por segundo

    /**
     * Curva de energia dos graves nos primeiros [seconds] da música, com
     * [FPS] pontos por segundo. Vazia se o arquivo não tiver áudio legível.
     */
    fun envelope(file: File, seconds: Float): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val out = ArrayList<Float>()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return FloatArray(0)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val limitUs = (seconds * 1_000_000).toLong()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var low = 0f          // passa-baixa: fica só com o grave (bumbo, baixo)
            var sum = 0.0
            var count = 0
            var guard = 0
            while (guard++ < 200_000) {
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (n < 0 || extractor.sampleTime > limitUs) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val buffer = decoder.getOutputBuffer(o)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val samples = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val hop = maxOf(1, rate / FPS)
                    val alpha = 1f - exp(-2.0 * Math.PI * 150.0 / rate).toFloat()
                    while (samples.remaining() >= channels) {
                        var mono = 0f
                        repeat(channels) { mono += samples.get() }
                        mono /= channels * 32768f
                        low += alpha * (mono - low)
                        sum += low * low
                        if (++count >= hop) {
                            out.add(sqrt(sum / count).toFloat())
                            sum = 0.0
                            count = 0
                        }
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) {
            // devolve o que conseguiu ler; sem curva os cortes ficam em intervalos fixos
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {
                // nada a fazer
            }
            extractor.release()
        }
        return out.toFloatArray()
    }

    /**
     * Instantes (em segundos) onde a energia sobe de repente: as batidas.
     * Devolve pares (tempo, força).
     */
    fun peaks(envelope: FloatArray): List<Pair<Float, Float>> {
        val n = envelope.size
        if (n < 3) return emptyList()
        val rise = FloatArray(n)
        for (i in 1 until n) rise[i] = maxOf(0f, envelope[i] - envelope[i - 1])
        val out = ArrayList<Pair<Float, Float>>()
        for (i in 1 until n - 1) {
            val lo = maxOf(0, i - FPS / 2)
            val hi = minOf(n, i + FPS / 2)
            var mean = 0f
            for (j in lo until hi) mean += rise[j]
            mean /= (hi - lo)
            if (rise[i] <= mean * 2.5f + 1e-4f) continue
            var isMax = true
            for (j in maxOf(0, i - FPS / 10)..minOf(n - 1, i + FPS / 10)) if (rise[j] > rise[i]) isMax = false
            if (isMax) out.add(i.toFloat() / FPS to rise[i])
        }
        return out
    }

    /**
     * Duração de cada parte do vídeo, somando [total] segundos. Cada corte cai
     * na batida mais forte entre [minLen] e [maxLen] depois do corte anterior;
     * sem batida nesse intervalo, o corte fica no meio dele.
     */
    fun segments(peaks: List<Pair<Float, Float>>, total: Float, minLen: Float, maxLen: Float): List<Float> {
        val out = ArrayList<Float>()
        var t = 0f
        while (total - t > maxLen) {
            val best = peaks.filter { it.first >= t + minLen && it.first <= t + maxLen }.maxByOrNull { it.second }
            val cut = best?.first ?: (t + (minLen + maxLen) / 2f)
            // não deixa sobrar um restinho curto demais no final
            if (total - cut < minLen) break
            out.add(cut - t)
            t = cut
        }
        if (total - t > 0.05f) out.add(total - t)
        return out
    }
}
