package com.codaxi.pinsave

import java.io.IOException
import java.net.URLEncoder

/**
 * Descobre os arquivos de um post, Reel ou carrossel público do Instagram.
 * O Instagram não tem acesso oficial sem login, então tentamos mais de um
 * caminho; contas privadas e Stories não funcionam.
 */
object InstagramExtractor {

    private val LINK = Regex(
        """https?://(?:www\.)?(?:instagram\.com|instagr\.am)/(?:[A-Za-z0-9_.]+/)?(?:p|reel|reels|tv|share)/[A-Za-z0-9_\-/]+(?:\?[^\s"'<>]*)?""",
        RegexOption.IGNORE_CASE
    )
    private val CODE = Regex("""/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""")
    private val NODE = Regex(""""__typename"\s*:\s*"(?:XDT)?Graph(Image|Video)"""")
    private val CAPTION = Regex(
        """"edge_media_to_caption"\s*:\s*\{\s*"edges"\s*:\s*\[\s*\{\s*"node"\s*:\s*\{"""
    )

    class InstagramException(message: String) : IOException(message)

    fun findLinks(text: CharSequence?): List<String> =
        if (text.isNullOrBlank()) emptyList()
        else LINK.findAll(text).map { it.value.trimEnd('.', ',', ')') }.distinct().toList()

    fun isInstagram(link: String) = LINK.matches(link)

    /** Código do post. Links "/share/" não têm o código: precisam ser abertos antes. */
    fun shortcode(url: String): String? =
        if (url.contains("/share/")) null else CODE.find(url)?.groupValues?.get(1)

    class Parsed(val media: List<PinterestExtractor.Media>, val caption: String)

    /**
     * Lê os arquivos e a legenda de uma resposta do Instagram (JSON, ou HTML
     * com o JSON embutido). Cada item de um carrossel vira uma entrada.
     */
    fun parse(body: String): Parsed {
        // Na página de incorporação o JSON vem dentro de uma string JavaScript.
        val text = if (body.contains("\\\"video_url\\\"") || body.contains("\\\"display_url\\\"")) {
            unescapeJs(body)
        } else {
            body
        }
        val media = ArrayList<PinterestExtractor.Media>()
        val marks = NODE.findAll(text).toList()
        marks.forEachIndexed { i, m ->
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else text.length
            val node = text.substring(m.range.first, end)
            val video = if (m.groupValues[1] == "Video") url(node, "video_url") else null
            val image = url(node, "display_url")
            when {
                video != null -> media.add(PinterestExtractor.Media(true, listOf(video)))
                image != null -> media.add(PinterestExtractor.Media(false, listOf(image)))
            }
        }
        if (media.isEmpty()) {
            // formato desconhecido: pega o primeiro vídeo ou imagem que aparecer
            val video = url(text, "video_url")
            val image = url(text, "display_url")
            when {
                video != null -> media.add(PinterestExtractor.Media(true, listOf(video)))
                image != null -> media.add(PinterestExtractor.Media(false, listOf(image)))
            }
        }
        val caption = CAPTION.find(text)
            ?.let { text.substring(it.range.last, minOf(text.length, it.range.last + 6000)) }
            ?.let { PinterestExtractor.jsonString(it, "text") }.orEmpty().trim()
        return Parsed(media.distinctBy { it.urls }, caption.take(2000))
    }

    private fun url(json: String, key: String): String? =
        PinterestExtractor.jsonString(json, key)?.takeIf { it.startsWith("http") }

    /** Desfaz um nível de escape de string JavaScript (\\ \" \/), mantendo o resto. */
    private fun unescapeJs(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && (s[i + 1] == '\\' || s[i + 1] == '"' || s[i + 1] == '/')) {
                out.append(s[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Faz as requisições necessárias. Bloqueante: chame fora da thread principal. */
    fun resolve(link: String): PinterestExtractor.Resolved {
        // links curtos de compartilhamento redirecionam para o post
        val code: String = shortcode(link)
            ?: try {
                shortcode(PinterestExtractor.fetch(link).first)
            } catch (e: IOException) {
                null
            }
            ?: throw InstagramException(
                "Esse link do Instagram não é de um post ou Reel. Stories e perfis não são suportados."
            )

        val attempts = listOf<() -> String>(
            {
                val variables = """{"shortcode":"$code","fetch_tagged_user_count":null,""" +
                    """"hoisted_comment_id":null,"hoisted_reply_id":null}"""
                PinterestExtractor.fetch(
                    "https://www.instagram.com/graphql/query",
                    headers = mapOf(
                        "X-IG-App-ID" to "936619743392459",
                        "X-FB-LSD" to "AVqbxe3J_YA",
                        "X-ASBD-ID" to "129477",
                        "Sec-Fetch-Site" to "same-origin",
                        "Content-Type" to "application/x-www-form-urlencoded"
                    ),
                    post = "variables=" + URLEncoder.encode(variables, "UTF-8") +
                        "&doc_id=8845758582119845&lsd=AVqbxe3J_YA"
                ).second
            },
            { PinterestExtractor.fetch("https://www.instagram.com/p/$code/embed/captioned/").second },
            { PinterestExtractor.fetch("https://www.instagram.com/p/$code/").second }
        )
        var caption = ""
        for (attempt in attempts) {
            val parsed = try {
                parse(attempt())
            } catch (e: IOException) {
                continue
            }
            if (caption.isEmpty()) caption = parsed.caption
            if (parsed.media.isNotEmpty()) return PinterestExtractor.Resolved("ig_$code", parsed.media, caption)
        }
        throw InstagramException(
            "Não consegui baixar esse post do Instagram. Ele pode ser de uma conta privada, " +
                "ou o Instagram bloqueou o acesso sem login. Tente de novo mais tarde."
        )
    }
}

/** Ponto único para reconhecer e resolver links de qualquer origem suportada. */
object Links {
    fun findLinks(text: CharSequence?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        // mantém a ordem em que os links aparecem no texto
        return (PinterestExtractor.findLinks(text) + InstagramExtractor.findLinks(text) + TikTokExtractor.findLinks(text))
            .distinct()
            .sortedBy { text.indexOf(it) }
    }

    fun findLink(text: CharSequence?): String? = findLinks(text).firstOrNull()

    /** Nome do site de origem, para mostrar ao usuário. */
    fun source(link: String): String = when {
        InstagramExtractor.isInstagram(link) -> "Instagram"
        TikTokExtractor.isTikTok(link) -> "TikTok"
        else -> "Pinterest"
    }

    fun resolve(link: String): PinterestExtractor.Resolved = when {
        InstagramExtractor.isInstagram(link) -> InstagramExtractor.resolve(link)
        TikTokExtractor.isTikTok(link) -> TikTokExtractor.resolve(link)
        else -> PinterestExtractor.resolve(link)
    }
}
