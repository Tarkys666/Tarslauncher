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

class GeminiRemoteDataSource(
    private val apiKeyProvider: () -> String,
    private val preferredModel: String = "gemini-flash-latest"
) {

    private val candidateModels = listOf(
        preferredModel,
        "gemini-flash-latest",
        "gemini-2.5-flash",
        "gemini-1.5-flash",
        "gemini-1.5-flash-latest"
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

        for (model in candidateModels) {
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
                return@withContext callResult
            }

            val error = callResult.exceptionOrNull()
            lastError = error
            val msg = error?.message.orEmpty()

            // If error is 404 (model not found / deprecated / no longer available), try next candidate
            val isModelUnavailable = msg.contains("404") ||
                msg.contains("not found", ignoreCase = true) ||
                msg.contains("no longer available", ignoreCase = true) ||
                msg.contains("deprecated", ignoreCase = true) ||
                msg.contains("unsupported", ignoreCase = true)

            if (!isModelUnavailable) {
                // If it's an authentication error (401/403) or quota error (429), stop immediately
                break
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
