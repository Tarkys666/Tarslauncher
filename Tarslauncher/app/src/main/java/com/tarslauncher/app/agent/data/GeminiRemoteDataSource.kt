package com.tarslauncher.app.agent.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tarslauncher.app.agent.domain.model.ChatMessage
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class GeminiRemoteDataSource(
    private val apiKeyProvider: () -> String,
    private val preferredModel: String = "gemini-2.5-flash"
) {

    @Volatile
    var lastUsedModel: String = preferredModel
        private set

    private val candidateModels = listOf(
        preferredModel,
        "gemini-2.0-flash"
    ).distinct()

    suspend fun generateContent(
        systemInstruction: String,
        messages: List<ChatMessage>
    ): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Gemini API anahtarı girilmemiş. Lütfen ayarlardan anahtarınızı ekleyin.")
            )
        }

        val requestBody = buildRequestBody(systemInstruction, messages)
        var lastError: Throwable? = null
        val maxRetries = 2

        for (model in candidateModels) {
            for (attempt in 0..maxRetries) {
                val callResult = runCatching {
                    val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")

                    val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("x-goog-api-key", key)
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
                        throw RuntimeException("Gemini API Hatası ($statusCode) [Model: $model]: ${responseText.take(250)}")
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
                        RuntimeException("İnternet / DNS Hatası: Google Gemini sunucusuna bağlanılamadı. Lütfen internetinizi veya WARP/VPN bağlantınızı kontrol edin.")
                    }
                    error is java.net.SocketTimeoutException -> {
                        RuntimeException("Zaman Aşımı: Google sunucusuna bağlanırken süre aşıldı. Lütfen internet hızınızı kontrol edin.")
                    }
                    msg.contains("503") || msg.contains("high demand", ignoreCase = true) || msg.contains("UNAVAILABLE", ignoreCase = true) -> {
                        RuntimeException("Google Gemini sunucuları yoğunluk yaşıyor (503 High Demand). Lütfen birkaç saniye sonra tekrar deneyin.")
                    }
                    msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) || msg.contains("rate limit", ignoreCase = true) -> {
                        RuntimeException("Gemini API istek sınırı aşıldı (429 Rate Limit).")
                    }
                    else -> error
                }

                // Fatal unrecoverable errors: Device is offline or API key is strictly invalid
                val isFatal = error is java.net.UnknownHostException ||
                    msg.contains("API_KEY_INVALID", ignoreCase = true) ||
                    msg.contains("API key not valid", ignoreCase = true) ||
                    (msg.contains("400") && msg.contains("API_KEY", ignoreCase = true)) ||
                    (msg.contains("401") || (msg.contains("403") && msg.contains("PERMISSION_DENIED", ignoreCase = true)))

                if (isFatal) {
                    return@withContext Result.failure(lastError ?: RuntimeException("Kritik API hatası."))
                }

                // 429 veya 503 durumlarında exponential backoff gecikmesi
                val isTransientOverload = msg.contains("429") || msg.contains("503") ||
                    msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                    msg.contains("UNAVAILABLE", ignoreCase = true) ||
                    msg.contains("high demand", ignoreCase = true)

                if (isTransientOverload && attempt < maxRetries) {
                    val backoffDelayMs = 1000L * (1L shl attempt)
                    delay(backoffDelayMs)
                } else {
                    break
                }
            }
        }

        Result.failure(lastError ?: RuntimeException("Hiçbir Gemini modeliyle bağlantı kurulamadı."))
    }

    private fun buildRequestBody(
        systemInstruction: String,
        messages: List<ChatMessage>
    ): JsonObject {
        return JsonObject().apply {
            // System instruction
            add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", systemInstruction) })
                })
            })

            // Group consecutive same-role messages into single turn with multiple parts (Gemini alternating turns requirement)
            val contentsArray = JsonArray()
            var currentRole: String? = null
            var currentParts = JsonArray()

            messages.forEach { msg ->
                val roleName = msg.role.serializedName
                if (roleName == currentRole) {
                    currentParts.add(JsonObject().apply { addProperty("text", msg.content) })
                } else {
                    if (currentRole != null) {
                        contentsArray.add(JsonObject().apply {
                            addProperty("role", currentRole)
                            add("parts", currentParts)
                        })
                    }
                    currentRole = roleName
                    currentParts = JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", msg.content) })
                    }
                }
            }

            if (currentRole != null) {
                contentsArray.add(JsonObject().apply {
                    addProperty("role", currentRole)
                    add("parts", currentParts)
                })
            }

            add("contents", contentsArray)

            // Generation config
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.15)
                addProperty("responseMimeType", "application/json")
            })
        }
    }

    private fun extractTextFromResponse(jsonResponse: String): String {
        val root = JsonParser.parseString(jsonResponse).asJsonObject
        val candidates = root.getAsJsonArray("candidates")
            ?: throw IllegalStateException("Model adayı bulunamadı.")
        if (candidates.size() == 0) {
            throw IllegalStateException("Model boş yanıt döndürdü.")
        }

        val firstCandidate = candidates[0].asJsonObject
        val content = firstCandidate.getAsJsonObject("content")
            ?: throw IllegalStateException("Model içeriği boş.")
        val parts = content.getAsJsonArray("parts")
            ?: throw IllegalStateException("Model parça içeriği eksik.")

        return parts[0].asJsonObject.get("text").asString
    }
}
