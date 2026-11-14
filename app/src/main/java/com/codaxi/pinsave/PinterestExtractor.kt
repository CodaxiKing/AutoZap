package com.codaxi.pinsave

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Descobre o endereço do arquivo de vídeo de um pin do Pinterest.
 * Kotlin puro (sem Android) para poder ser testado na JVM.
 */
object PinterestExtractor {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val LINK = Regex(
        """https?://(?:(?:[a-z0-9-]+\.)?pinterest\.[a-z.]{2,8}/pin/[^\s"'<>]+|pin\.it/[A-Za-z0-9]+)""",
        RegexOption.IGNORE_CASE
    )
    private val PIN_ID = Regex("""/pin/(?:[^/?#]*-)?(\d+)(?=[/?#]|$)""")
    private val VIDEO = Regex("""https?://v\d*\.pinimg\.com/videos/[A-Za-z0-9_\-./]+?\.(?:mp4|m3u8)""")
    private val HASH = Regex("""[0-9a-f]{32}""")
    private val RESOLUTION = Regex("""/(\d{3,4})p/""")

    class NoVideoException : IOException("Nenhum vídeo encontrado nesse pin")

    /** Primeiro link do Pinterest dentro de um texto qualquer, ou null. */
    fun findLink(text: CharSequence?): String? =
        if (text.isNullOrBlank()) null else LINK.find(text)?.value?.trimEnd('.', ',', ')')

    fun pinId(url: String): String? = PIN_ID.find(url)?.groupValues?.get(1)

    /**
     * Endereços de vídeo (mp4) encontrados em um HTML/JSON do Pinterest,
     * do melhor para o pior. Só considera o primeiro vídeo que aparece,
     * que é o do pin principal (a página também traz pins relacionados).
     */
    fun candidates(body: String): List<String> {
        val clean = body
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\/", "/")
        val all = VIDEO.findAll(clean)
            .map { it.value.replace("http://", "https://") }
            .distinct()
            .toList()
        if (all.isEmpty()) return emptyList()

        val key = HASH.find(all.first().substringAfterLast('/'))?.value
        val mine = if (key == null) listOf(all.first()) else all.filter { it.contains(key) }

        val out = LinkedHashSet<String>()
        mine.filter { it.endsWith(".mp4") }
            .sortedByDescending { quality(it) }
            .forEach { out.add(it) }
        // Só veio o stream HLS: o mp4 fica no mesmo caminho, trocando a pasta.
        mine.filter { it.endsWith(".m3u8") && it.contains("/hls/") }.forEach {
            out.add(it.replace("/hls/", "/720p/").removeSuffix(".m3u8") + ".mp4")
        }
        return out.toList()
    }

    private fun quality(url: String): Int =
        RESOLUTION.find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (url.contains("expMp4")) 719 else 0

    private val LD_JSON = Regex(
        """<script[^>]*application/ld\+json[^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val SITE_SUFFIX = Regex("""\s*[|\-–]\s*Pinterest\s*$""", RegexOption.IGNORE_CASE)
    private val BOILERPLATE = Regex(
        """discover \(and save|descubra \(e salve|was discovered by|descobriu este pin|pinterest""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Título e descrição do pin, para servir de legenda inicial.
     * Devolve "" quando a página só traz os textos genéricos do Pinterest.
     */
    fun caption(body: String): String {
        val blocks = LD_JSON.findAll(body).map { it.groupValues[1] }.toList()
        val ld = blocks.firstOrNull { it.contains("VideoObject") } ?: blocks.firstOrNull()

        val title = clean(ld?.let { jsonString(it, "headline") } ?: meta(body, "og:title"))
        val desc = clean(
            ld?.let { jsonString(it, "description") ?: jsonString(it, "articleBody") }
                ?: meta(body, "og:description")
        )
        val truncated = title.endsWith("…") || title.endsWith("...")
        val parts = when {
            desc.isEmpty() -> listOf(title)
            title.isEmpty() || truncated || desc.startsWith(title.trimEnd('.', '…')) -> listOf(desc)
            else -> listOf(title, desc)
        }
        return parts.filter { it.isNotEmpty() }.joinToString("\n\n").take(2000)
    }

    private fun clean(raw: String?): String {
        val s = (raw ?: "").replace(SITE_SUFFIX, "").trim()
        return if (BOILERPLATE.containsMatchIn(s)) "" else s
    }

    private fun meta(html: String, property: String): String? {
        val tag = Regex(
            """<meta[^>]*(?:property|name)=["']""" + Regex.escape(property) + """["'][^>]*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.value ?: return null
        val content = Regex("""content="([^"]*)"""").find(tag)?.groupValues?.get(1)
            ?: Regex("""content='([^']*)'""").find(tag)?.groupValues?.get(1)
            ?: return null
        return htmlUnescape(content)
    }

    private fun htmlUnescape(s: String): String =
        Regex("""&#(x?)([0-9a-fA-F]+);""").replace(s) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (code == null || code > 0x10FFFF) m.value else String(Character.toChars(code))
        }.replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun jsonString(json: String, key: String): String? {
        val raw = Regex("\"" + key + """"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)
            ?.groupValues?.get(1) ?: return null
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                out.append(c); i++; continue
            }
            val n = raw[i + 1]
            i += 2
            when (n) {
                'n' -> out.append('\n')
                't' -> out.append(' ')
                'r' -> {}
                'u' -> {
                    val code = raw.substring(i, minOf(i + 4, raw.length)).toIntOrNull(16)
                    if (code != null) { out.append(code.toChar()); i += 4 }
                }
                else -> out.append(n)
            }
        }
        return htmlUnescape(out.toString())
    }

    class Resolved(val pinId: String?, val videoUrls: List<String>, val caption: String)

    /** Faz as requisições necessárias. Bloqueante: chame fora da thread principal. */
    fun resolve(link: String): Resolved {
        val (finalUrl, html) = fetch(link)
        val id = pinId(finalUrl) ?: pinId(link)
        val caption = caption(html)
        candidates(html).let { if (it.isNotEmpty()) return Resolved(id, it, caption) }

        if (id != null) {
            val fallbacks = listOf(
                "https://www.pinterest.com/pin/$id/",
                "https://www.pinterest.com/resource/PinResource/get/?source_url=" +
                    enc("/pin/$id/") + "&data=" +
                    enc("""{"options":{"id":"$id","field_set_key":"unauth_react_main_pin"},"context":{}}"""),
                "https://widgets.pinterest.com/v3/pidgets/pins/info/?pin_ids=$id"
            )
            for (url in fallbacks) {
                if (url == finalUrl) continue
                val body = try {
                    fetch(url).second
                } catch (e: IOException) {
                    continue
                }
                candidates(body).let {
                    if (it.isNotEmpty()) return Resolved(id, it, caption.ifEmpty { caption(body) })
                }
            }
        }
        throw NoVideoException()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** GET seguindo redirecionamentos. Devolve (URL final, corpo). */
    private fun fetch(start: String): Pair<String, String> {
        var url = URL(start)
        repeat(8) {
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                conn.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IOException("Redirecionamento inválido")
                    url = URL(url, loc)
                    return@repeat
                }
                if (code >= 400) return url.toString() to ""
                val body = conn.inputStream.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
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
