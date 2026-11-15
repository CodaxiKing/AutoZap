package com.codaxi.pinsave

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Banco de vídeos e imagens. Usa o Pexels, que é gratuito e libera o uso do
 * material em publicações; precisa de uma chave de API gratuita.
 */
object Pexels {
    /** Um vídeo ou foto do banco: miniatura para escolher e arquivo para baixar. */
    class Clip(val thumb: String, val url: String)

    class PexelsException(message: String) : IOException(message)

    /** Temas do nicho, com a busca em inglês que traz os melhores resultados. */
    val THEMES = listOf(
        "Carros de luxo" to "luxury sports car night",
        "Dinheiro" to "money cash dollars",
        "Relógios e joias" to "luxury watch dark",
        "Cidade à noite" to "city night skyline dark",
        "Terno e estilo" to "man in suit dark",
        "Mansões" to "luxury mansion night",
        "Jatos e iates" to "private jet yacht luxury",
        "Cassino" to "casino poker chips",
        "Whisky e charuto" to "whiskey cigar dark"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pinsave", Context.MODE_PRIVATE)

    fun key(ctx: Context): String = prefs(ctx).getString("pexels_key", "") ?: ""

    fun setKey(ctx: Context, key: String) = prefs(ctx).edit().putString("pexels_key", key.trim()).apply()

    /** Vídeos verticais para a busca. Bloqueante. */
    fun videos(key: String, query: String, page: Int): List<Clip> {
        val json = get(key, "https://api.pexels.com/videos/search?orientation=portrait&per_page=30&page=$page&query=" + enc(query))
        val out = ArrayList<Clip>()
        val videos = json.optJSONArray("videos") ?: return out
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val files = v.optJSONArray("video_files") ?: continue
            // o arquivo mais próximo de 1080 de largura, sem passar disso se der
            var best: JSONObject? = null
            var bestScore = Int.MAX_VALUE
            for (j in 0 until files.length()) {
                val f = files.optJSONObject(j) ?: continue
                if (f.optString("file_type") != "video/mp4") continue
                val side = minOf(f.optInt("width"), f.optInt("height"))
                if (side <= 0) continue
                val score = if (side <= 1080) 1080 - side else (side - 1080) * 4
                if (score < bestScore) {
                    bestScore = score
                    best = f
                }
            }
            val link = best?.optString("link").orEmpty()
            val thumb = v.optString("image")
            if (link.startsWith("http") && thumb.startsWith("http")) out.add(Clip(thumb, link))
        }
        return out
    }

    /** Fotos verticais para a busca. Bloqueante. */
    fun photos(key: String, query: String, page: Int): List<Clip> {
        val json = get(key, "https://api.pexels.com/v1/search?orientation=portrait&per_page=40&page=$page&query=" + enc(query))
        val out = ArrayList<Clip>()
        val photos = json.optJSONArray("photos") ?: return out
        for (i in 0 until photos.length()) {
            val src = photos.optJSONObject(i)?.optJSONObject("src") ?: continue
            val link = src.optString("large2x").ifEmpty { src.optString("original") }
            val thumb = src.optString("medium").ifEmpty { link }
            if (link.startsWith("http")) out.add(Clip(thumb, link))
        }
        return out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(key: String, url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Authorization", key)
            conn.setRequestProperty("User-Agent", PinterestExtractor.USER_AGENT)
            when (val code = conn.responseCode) {
                in 200..299 -> {}
                401, 403 -> throw PexelsException("A chave do Pexels não foi aceita. Confira se copiou a chave inteira.")
                429 -> throw PexelsException("O limite de buscas do Pexels acabou por agora. Tente de novo em uma hora.")
                else -> throw PexelsException("O Pexels respondeu com erro $code.")
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                throw PexelsException("Resposta inesperada do Pexels.")
            }
        } finally {
            conn.disconnect()
        }
    }
}
