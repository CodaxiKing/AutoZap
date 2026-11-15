package com.codaxi.pinsave

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descobre o arquivo original de um vídeo (ou as fotos) do TikTok. O arquivo
 * original não tem a marca d'água, que só é aplicada na cópia de download.
 */
object TikTokExtractor {

    private val LINK = Regex(
        """https?://(?:(?:vm|vt)\.tiktok\.com/[A-Za-z0-9]+/?|(?:www\.|m\.)?tiktok\.com/(?:@[^/\s"'<>]+/(?:video|photo)/\d+|t/[A-Za-z0-9]+)(?:[/?][^\s"'<>]*)?)""",
        RegexOption.IGNORE_CASE
    )
    private val ID = Regex("""/(?:video|photo)/(\d+)""")
    private val IMAGE = Regex(""""imageURL"\s*:\s*\{\s*"urlList"\s*:\s*\[\s*"((?:[^"\\]|\\.)*)"""")

    class TikTokException(message: String) : IOException(message)

    fun findLinks(text: CharSequence?): List<String> =
        if (text.isNullOrBlank()) emptyList()
        else LINK.findAll(text).map { it.value.trimEnd('.', ',', ')') }.distinct().toList()

    fun isTikTok(link: String) = LINK.matches(link)

    class Parsed(val media: List<PinterestExtractor.Media>, val caption: String)

    /** Lê o vídeo sem marca d'água (ou as fotos) e a legenda do HTML da página. */
    fun parse(html: String): Parsed {
        val at = html.indexOf("\"itemStruct\"")
        val item = if (at >= 0) html.substring(at) else html
        val media = ArrayList<PinterestExtractor.Media>()

        val post = item.indexOf("\"imagePost\"")
        if (post >= 0) {
            val images = PinterestExtractor.arrayAfter(item.substring(post), "images").orEmpty()
            IMAGE.findAll(images).forEach { m ->
                unescape(m.groupValues[1])?.let { media.add(PinterestExtractor.Media(false, listOf(it))) }
            }
        }
        if (media.isEmpty()) {
            // "playAddr" é o arquivo original; "downloadAddr" é a cópia com marca d'água.
            PinterestExtractor.jsonString(item, "playAddr")?.takeIf { it.startsWith("http") }
                ?.let { media.add(PinterestExtractor.Media(true, listOf(it))) }
        }
        val caption = if (at >= 0) PinterestExtractor.jsonString(item, "desc").orEmpty().trim() else ""
        return Parsed(media.distinctBy { it.urls }, caption.take(2000))
    }

    private fun unescape(raw: String): String? =
        PinterestExtractor.jsonString("\"u\":\"$raw\"", "u")?.takeIf { it.startsWith("http") }

    /** Faz as requisições necessárias. Bloqueante: chame fora da thread principal. */
    fun resolve(link: String): PinterestExtractor.Resolved {
        val cookies = LinkedHashMap<String, String>()
        val (finalUrl, html) = get(link, cookies)
        val parsed = parse(html)
        if (parsed.media.isEmpty()) {
            throw TikTokException(
                "Não consegui baixar esse vídeo do TikTok. Ele pode ser privado, " +
                    "ou o TikTok bloqueou o acesso. Tente de novo mais tarde."
            )
        }
        // O arquivo só é entregue para quem manda os cookies recebidos na página.
        val headers = LinkedHashMap<String, String>()
        headers["Referer"] = "https://www.tiktok.com/"
        if (cookies.isNotEmpty()) headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val id = ID.find(finalUrl)?.groupValues?.get(1)
        return PinterestExtractor.Resolved(if (id == null) "tt" else "tt_$id", parsed.media, parsed.caption, headers)
    }

    /** GET seguindo redirecionamentos e guardando os cookies. Devolve (URL final, corpo). */
    private fun get(start: String, cookies: MutableMap<String, String>): Pair<String, String> {
        var url = URL(start)
        repeat(8) {
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", PinterestExtractor.USER_AGENT)
                conn.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                conn.setRequestProperty("Accept", "text/html,*/*;q=0.8")
                if (cookies.isNotEmpty()) {
                    conn.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
                val code = conn.responseCode
                conn.headerFields?.forEach { (name, values) ->
                    if (name != null && name.equals("Set-Cookie", ignoreCase = true)) {
                        values.forEach { v ->
                            val pair = v.substringBefore(';')
                            val eq = pair.indexOf('=')
                            if (eq > 0) cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
                        }
                    }
                }
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Redirecionamento inválido")
                    url = URL(url, loc)
                    return@repeat
                }
                if (code >= 400) return url.toString() to ""
                val body = conn.inputStream.use { input ->
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(16384)
                    while (buf.size() < 12_000_000) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                    }
                    buf.toString("UTF-8")
                }
                return url.toString() to body
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Redirecionamentos demais")
    }
}
