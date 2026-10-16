package com.tarslauncher.app.agent.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tarslauncher.app.agent.domain.model.ChatMessage
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remote data source for Groq Cloud API (OpenAI-compatible Chat Completions).
 * Runs at ultra-high inference speeds (300-800+ tokens/sec) on LPUs.
 */
class GroqRemoteDataSource(
    private val apiKeyProvider: () -> String,
    private val preferredModelProvider: () -> String = { DEFAULT_GROQ_MODEL }
) {
    companion object {
        const val DEFAULT_GROQ_MODEL = "qwen/qwen3.8-27b"
    }

    @Volatile
    var lastUsedModel: String = DEFAULT_GROQ_MODEL
        private set

    suspend fun generateContent(
        systemInstruction: String,
        messages: List<ChatMessage>
    ): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Groq API anahtarı girilmemiş. Lütfen ayarlardan Groq anahtarınızı (gsk_...) ekleyin.")
            )
        }

        val candidateModels = listOf(
            DEFAULT_GROQ_MODEL,
            "Qwen/Qwen3.8-27B",
            "qwen-2.5-32b",
            "openai/gpt-oss-20b"
        ).distinct()

        var lastError: Throwable? = null

        for (model in candidateModels) {
            val callResult = runCatching {
                val requestBody = buildRequestBody(model, systemInstruction, messages)
                val endpoint = URL("https://api.groq.com/openai/v1/chat/completions")

                val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $key")
                }

                connection.outputStream.use { output ->
                    output.write(requestBody.toString().toByteArray(Charsets.UTF_8))
                }

                val statusCode = connection.responseCode
                val inputStream: InputStream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: throw RuntimeException("HTTP $statusCode: Yanıt alınamadı.")
                }

                val responseText = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

                if (statusCode !in 200..299) {
                    throw RuntimeException("Groq API Hatası ($statusCode) [Model: $model]: ${responseText.take(250)}")
                }

                extractTextFromResponse(responseText)
            }

            if (callResult.isSuccess) {
                lastUsedModel = model
                return@withContext callResult
            }

            val error = callResult.exceptionOrNull()
            val msg = error?.message.orEmpty()

            lastError = when {
                error is java.net.UnknownHostException -> {
                    RuntimeException("İnternet / DNS Hatası: Groq sunucusuna bağlanılamadı. Lütfen internetinizi kontrol edin.")
                }
                error is java.net.SocketTimeoutException -> {
                    RuntimeException("Zaman Aşımı: Groq sunucusuna bağlanırken süre aşıldı.")
                }
                msg.contains("401") || msg.contains("invalid_api_key", ignoreCase = true) -> {
                    RuntimeException("Geçersiz Groq API Anahtarı: Lütfen console.groq.com adresinden aldığınız gsk_... anahtarınızı kontrol edin.")
                }
                msg.contains("429") || msg.contains("rate_limit", ignoreCase = true) -> {
                    RuntimeException("Groq Hız Limiti (429 Rate Limit): Çok hızlı istek gönderildi. Yedek model deneniyor...")
                }
                else -> error
            }

            val isFatal = error is java.net.UnknownHostException ||
                msg.contains("401") ||
                msg.contains("invalid_api_key", ignoreCase = true)

            if (isFatal) {
                break
            }
        }

        Result.failure(lastError ?: RuntimeException("Hiçbir Groq modeliyle yanıt alınamadı."))
    }

    private fun buildRequestBody(
        model: String,
        systemInstruction: String,
        messages: List<ChatMessage>
    ): JsonObject {
        return JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.2)
            addProperty("max_completion_tokens", 2048)
            addProperty("top_p", 0.95)

            // Force JSON object format for reliable Agent parsing
            add("response_format", JsonObject().apply {
                addProperty("type", "json_object")
            })

            val messagesArray = JsonArray()

            // System prompt
            messagesArray.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemInstruction)
            })

            // Conversation history
            messages.forEach { msg ->
                val roleStr = when (msg.role) {
                    ChatMessage.Role.USER -> "user"
                    ChatMessage.Role.MODEL -> "assistant"
                }
                messagesArray.add(JsonObject().apply {
                    addProperty("role", roleStr)
                    addProperty("content", msg.content)
                })
            }

            add("messages", messagesArray)
        }
    }

    private fun extractTextFromResponse(jsonResponse: String): String {
        val root = JsonParser.parseString(jsonResponse).asJsonObject
        val choices = root.getAsJsonArray("choices")
            ?: throw IllegalStateException("Groq choices listesi boş.")
        if (choices.size() == 0) {
            throw IllegalStateException("Groq yanıt döndürmedi.")
        }

        val firstChoice = choices[0].asJsonObject
        val message = firstChoice.getAsJsonObject("message")
            ?: throw IllegalStateException("Groq message içeriği bulunamadı.")

        return message.get("content")?.asString
            ?: message.get("reasoning")?.asString
            ?: throw IllegalStateException("Groq yanıt içeriği boş.")
    }
}
