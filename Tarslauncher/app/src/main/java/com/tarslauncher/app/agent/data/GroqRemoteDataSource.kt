package com.tarslauncher.app.agent.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tarslauncher.app.agent.domain.model.ChatMessage
import com.tarslauncher.app.data.sync.SupabaseConfig
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Pure Supabase Edge Function (`tars-chat`) client.
 * The Android client ONLY sends the message payload to `https://nznftexnhmzboihbeilq.supabase.co/functions/v1/tars-chat`
 * and receives the model response back; all upstream LLM calls (`dots-studio/dots-3-note-preview:free` / `Qwen`)
 * and secret lookups (`public.agent_secrets`) execute on the Supabase Edge Function.
 */
class GroqRemoteDataSource(
    private val apiKeyProvider: () -> String,
    private val preferredModelProvider: () -> String = { DEFAULT_OPENROUTER_MODEL },
    private val userIdProvider: () -> String = { "" }
) {
    companion object {
        const val DEFAULT_OPENROUTER_MODEL = "dots-studio/dots-3-note-preview:free"
        const val DEFAULT_GROQ_MODEL = "qwen/qwen3.8-27b"
        private const val MAX_RETRIES = 3
    }

    @Volatile
    var lastUsedModel: String = DEFAULT_OPENROUTER_MODEL
        private set

    suspend fun generateContent(
        systemInstruction: String,
        messages: List<ChatMessage>
    ): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        val userId = userIdProvider().trim()
        val requestedModel = preferredModelProvider().trim().ifBlank { DEFAULT_OPENROUTER_MODEL }
        val qwenEnabled = requestedModel == DEFAULT_GROQ_MODEL || requestedModel.startsWith("qwen/")

        if (!SupabaseConfig.isConfigured()) {
            return@withContext Result.failure(
                IllegalStateException("Supabase servisi yapılandırılmamış (local.properties veya BuildConfig içinde SUPABASE_URL / SUPABASE_ANON_KEY eksik).")
            )
        }

        var lastError: Throwable? = null
        var attempt = 0

        while (attempt < MAX_RETRIES) {
            attempt++
            val callResult = callSupabaseEdgeFunction(
                userId = userId,
                model = requestedModel,
                systemInstruction = systemInstruction,
                messages = messages,
                apiKey = key,
                qwenEnabled = qwenEnabled
            )

            if (callResult.isSuccess) {
                lastUsedModel = requestedModel
                return@withContext callResult
            }

            val error = callResult.exceptionOrNull()
            val msg = error?.message.orEmpty()
            val isRateLimit = msg.contains("429") || msg.contains("rate_limit", ignoreCase = true)

            if (isRateLimit && attempt < MAX_RETRIES) {
                delay(2200L * attempt)
                continue
            }

            lastError = when {
                error is java.net.UnknownHostException -> {
                    RuntimeException("İnternet / DNS Hatası: Supabase sunucusuna bağlanılamadı.")
                }
                error is java.net.SocketTimeoutException -> {
                    RuntimeException("Zaman Aşımı: Supabase Edge Function yanıt süresi aşıldı.")
                }
                msg.contains("401") || msg.contains("invalid_api_key", ignoreCase = true) -> {
                    RuntimeException("API Anahtarı Eksik veya Geçersiz: Lütfen TARS Agent konsolundan API anahtarınızı kaydedin.")
                }
                isRateLimit -> {
                    RuntimeException("Hız Limiti (429): Lütfen birkaç saniye bekleyip tekrar deneyin.")
                }
                else -> error
            }

            val isFatal = error is java.net.UnknownHostException ||
                msg.contains("401") ||
                msg.contains("invalid_api_key", ignoreCase = true)

            if (isFatal) {
                return@withContext Result.failure(lastError!!)
            }
        }

        Result.failure(lastError ?: RuntimeException("Supabase API ($requestedModel) yanıt vermedi."))
    }

    private fun callSupabaseEdgeFunction(
        userId: String,
        model: String,
        systemInstruction: String,
        messages: List<ChatMessage>,
        apiKey: String,
        qwenEnabled: Boolean
    ): Result<String> = runCatching {
        val messagesArray = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemInstruction)
            })
            messages.forEach { msg ->
                val roleStr = when (msg.role) {
                    ChatMessage.Role.USER -> "user"
                    ChatMessage.Role.MODEL -> "assistant"
                }
                add(JsonObject().apply {
                    addProperty("role", roleStr)
                    addProperty("content", msg.content)
                })
            }
        }

        val edgePayload = JsonObject().apply {
            if (userId.isNotBlank()) {
                addProperty("userId", userId)
            }
            addProperty("model", model)
            addProperty("qwenEnabled", qwenEnabled)
            addProperty("temperature", 0.15)
            addProperty("top_p", 0.9)
            if (apiKey.isNotBlank()) {
                addProperty("apiKey", apiKey)
            }
            add("messages", messagesArray)
        }

        val connection = (URL(SupabaseConfig.EDGE_CHAT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
            setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
        }

        try {
            connection.outputStream.use { out ->
                out.write(edgePayload.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream: InputStream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: throw RuntimeException("Supabase Edge HTTP $code")
            }
            val responseText = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                throw RuntimeException("Supabase Edge ($code): ${responseText.take(250)}")
            }
            extractTextFromResponse(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTextFromResponse(jsonResponse: String): String {
        val root = JsonParser.parseString(jsonResponse).asJsonObject
        val choices = root.getAsJsonArray("choices")
            ?: throw IllegalStateException("Model choices listesi boş.")
        if (choices.size() == 0) {
            throw IllegalStateException("Model yanıt döndürmedi.")
        }

        val firstChoice = choices[0].asJsonObject
        val message = firstChoice.getAsJsonObject("message")
            ?: throw IllegalStateException("Model message içeriği bulunamadı.")

        val content = message.get("content")
            ?.takeIf { !it.isJsonNull }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val reasoning = message.get("reasoning")
            ?.takeIf { !it.isJsonNull }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val resolved = content ?: reasoning
            ?: throw IllegalStateException("Model yanıt içeriği boş.")

        val finishReason = firstChoice.get("finish_reason")
            ?.takeIf { !it.isJsonNull }
            ?.asString

        if (finishReason == "length" && (!resolved.contains("{") || !resolved.contains("}"))) {
            throw IllegalStateException("Model çıktısı maksimum token sınırına takılarak yarıda kesildi.")
        }

        return resolved
    }
}
