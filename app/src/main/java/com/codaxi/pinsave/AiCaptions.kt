package com.codaxi.pinsave

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Escreve legendas com hashtags usando a API da OpenAI. A chave é digitada
 * no app e fica só no aparelho; ela nunca deve ir para o repositório.
 */
object AiCaptions {
    // Tenta os modelos nesta ordem; passa para o próximo se a conta não tiver acesso.
    private val MODELS = listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-5-mini")

    private const val SYSTEM = "Você escreve legendas para uma página de Instagram e TikTok de motivação e " +
        "lifestyle com estética dark e de luxo: riqueza, dinheiro, carros de luxo, relógios, disciplina, " +
        "mentalidade, visual de máfia. Escreva em português do Brasil, em tom confiante e direto. " +
        "Responda só com a legenda pronta: uma ou duas frases curtas, uma chamada para ação curta " +
        "(seguir, salvar ou comentar) e, numa linha separada no final, de 6 a 8 hashtags relevantes " +
        "misturando português e inglês. Não use aspas, não explique nada e não repita a frase do vídeo " +
        "palavra por palavra."

    class AiException(message: String) : IOException(message)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pinsave", Context.MODE_PRIVATE)

    fun key(ctx: Context): String = prefs(ctx).getString("openai_key", "").orEmpty()

    fun setKey(ctx: Context, key: String) = prefs(ctx).edit().putString("openai_key", key.trim()).apply()

    fun isOn(ctx: Context) = key(ctx).isNotBlank()

    /**
     * Legenda nova a partir da frase do vídeo e/ou de um texto de referência
     * (a legenda original ou um rascunho). Bloqueante.
     */
    fun generate(key: String, phrase: String, reference: String): String {
        val user = StringBuilder()
        if (phrase.isNotBlank()) user.append("Frase que aparece no vídeo: ").append(phrase.trim()).append('\n')
        if (reference.isNotBlank()) {
            user.append("Texto de referência (legenda original ou rascunho): ")
                .append(reference.trim().take(800)).append('\n')
        }
        if (user.isEmpty()) user.append("O vídeo não tem frase. Escreva uma legenda no tema da página.\n")
        user.append("Escreva a legenda.")

        var last = AiException("Não foi possível gerar a legenda.")
        for (model in MODELS) {
            try {
                return request(key, model, user.toString())
            } catch (e: ModelUnavailable) {
                last = AiException("Sua conta da OpenAI não tem acesso aos modelos que o app usa.")
            }
        }
        throw last
    }

    private class ModelUnavailable : IOException()

    private fun request(key: String, model: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
            .toString()
        val conn = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 45000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val error = try {
                    conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                } catch (e: Exception) {
                    ""
                }
                throw when {
                    code == 401 -> AiException("A chave da OpenAI não foi aceita. Confira se copiou a chave inteira.")
                    error.contains("insufficient_quota") ->
                        AiException("A conta da OpenAI está sem créditos. Adicione saldo em platform.openai.com.")
                    code == 429 -> AiException("A OpenAI limitou os pedidos por agora. Tente de novo em instantes.")
                    code == 404 || error.contains("model") -> ModelUnavailable()
                    else -> AiException("A OpenAI respondeu com erro $code.")
                }
            }
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val content = try {
                JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            } catch (e: Exception) {
                throw AiException("Resposta inesperada da OpenAI.")
            }
            return content.trim().trim('"', '“', '”').trim().ifEmpty { throw AiException("A OpenAI devolveu uma legenda vazia.") }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Usada depois de baixar ou criar: troca a legenda dos arquivos por uma
     * escrita pela IA. Se a chave não existe ou a chamada falha, a legenda
     * que já estava fica como está.
     */
    fun auto(ctx: Context, uris: List<Uri>, phrase: String, reference: String, suffix: String = "") {
        val key = key(ctx)
        if (key.isBlank() || uris.isEmpty()) return
        val caption = try {
            generate(key, phrase, reference)
        } catch (e: Exception) {
            return
        }
        val full = if (suffix.isBlank()) caption else caption + "\n\n" + suffix.trim()
        uris.forEach { Captions.set(ctx, it, full) }
    }
}
