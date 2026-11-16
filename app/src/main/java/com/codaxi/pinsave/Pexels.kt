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
    /** [seconds] é a duração, só preenchida para vídeos. */
    class Clip(val thumb: String, val url: String, val seconds: Int = 0)

    class PexelsException(message: String) : IOException(message)

    /** Tema do nicho: a busca em inglês traz os melhores fundos; [tags] são as hashtags padrão. */
    class Theme(val name: String, val query: String, val tags: String)

    val THEMES = listOf(
        Theme("Máfia", "gangster suit dark", "#mafia #mafiaaesthetic #poder #respeito #mentalidade #luxury #darkaesthetic"),
        Theme("Carros de luxo", "luxury sports car night", "#carros #supercarros #luxurycars #carlifestyle #luxury #motivação #sucesso"),
        Theme("Dinheiro", "money cash dollars", "#dinheiro #riqueza #money #mentalidademilionária #sucesso #motivação #wealth"),
        Theme("Relógios e joias", "luxury watch dark", "#relógios #luxurywatch #luxo #estilo #success #riqueza #lifestyle"),
        Theme("Cidade à noite", "city night skyline dark", "#cidade #nightlife #darkaesthetic #mentalidade #foco #motivação #luxury"),
        Theme("Terno e estilo", "man in suit dark", "#elegância #estilomasculino #gentleman #disciplina #mentalidade #sucesso #luxury"),
        Theme("Mansões", "luxury mansion night", "#mansão #luxuryhomes #riqueza #vidadeluxo #sucesso #motivação #lifestyle"),
        Theme("Jatos e iates", "private jet yacht luxury", "#jatinho #iate #privatejet #vidadeluxo #riqueza #success #luxurylifestyle"),
        Theme("Cassino", "casino poker chips", "#cassino #poker #risco #mentalidade #dinheiro #luxury #darkaesthetic"),
        Theme("Whisky e charuto", "whiskey cigar dark", "#whisky #charuto #gentleman #estilo #luxo #mentalidade #darkaesthetic")
    )

    /** Hashtags usadas quando a busca é livre, sem um tema da lista. */
    const val GENERAL_TAGS = "#luxury #motivação #mentalidade #sucesso #lifestyle #darkaesthetic #riqueza"

    /** Hashtags do tema: as que o usuário editou, ou as padrão. */
    fun tags(ctx: Context, theme: Theme?): String =
        if (theme == null) prefs(ctx).getString("tags_", null) ?: GENERAL_TAGS
        else prefs(ctx).getString("tags_" + theme.name, null) ?: theme.tags

    fun setTags(ctx: Context, theme: Theme?, tags: String) =
        prefs(ctx).edit().putString("tags_" + (theme?.name ?: ""), tags.trim()).apply()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pinsave", Context.MODE_PRIVATE)

    /** Chave embutida a pedido do dono do app; "Trocar a chave" no gerador substitui por outra. */
    private const val DEFAULT_KEY = "DOyhJmmsSVWC49YVZmKr34BoTCohLCUm3VgPIOiPUQFtW1jA4z1uFb0X"

    fun key(ctx: Context): String = prefs(ctx).getString("pexels_key", "").orEmpty().ifBlank { DEFAULT_KEY }

    fun setKey(ctx: Context, key: String) = prefs(ctx).edit().putString("pexels_key", key.trim()).apply()

    /** Vídeos verticais para a busca. Bloqueante. */
    fun videos(key: String, query: String, page: Int, side: Int = 1080): List<Clip> {
        val json = get(key, "https://api.pexels.com/videos/search?orientation=portrait&per_page=30&page=$page&query=" + enc(query))
        val out = ArrayList<Clip>()
        val videos = json.optJSONArray("videos") ?: return out
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val files = v.optJSONArray("video_files") ?: continue
            // o arquivo mais próximo do tamanho pedido, sem passar dele se der
            var best: JSONObject? = null
            var bestScore = Int.MAX_VALUE
            for (j in 0 until files.length()) {
                val f = files.optJSONObject(j) ?: continue
                if (f.optString("file_type") != "video/mp4") continue
                val size = minOf(f.optInt("width"), f.optInt("height"))
                if (size <= 0) continue
                val score = if (size <= side) side - size else (size - side) * 4
                if (score < bestScore) {
                    bestScore = score
                    best = f
                }
            }
            val link = best?.optString("link").orEmpty()
            val thumb = v.optString("image")
            if (link.startsWith("http") && thumb.startsWith("http")) out.add(Clip(thumb, link, v.optInt("duration")))
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
