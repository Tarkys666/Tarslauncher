package com.tarslauncher.app.data.sync

import com.tarslauncher.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Lightweight, zero-bloat Supabase REST client for TARS Launcher.
 * Uses standard HttpsURLConnection without heavy external SDKs, keeping the APK ultra-small (~1.3 MB).
 * Credentials are injected via BuildConfig at build time.
 * Uses Supabase Auth access tokens for RLS and bounds response sizes.
 */
object SupabaseConfig {
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 // 2 MB response guard

    val SUPABASE_URL: String
        get() = BuildConfig.SUPABASE_URL.trim().removeSuffix("/")

    val SUPABASE_ANON_KEY: String
        get() = BuildConfig.SUPABASE_ANON_KEY.trim()

    fun isConfigured(): Boolean =
        SUPABASE_URL.isNotBlank() && SUPABASE_ANON_KEY.isNotBlank()

    fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * Executes a PostgREST HTTP request against `<SUPABASE_URL>/rest/v1/<pathAndQuery>`.
     * The public API key identifies the project; the access token identifies the user.
     */
    suspend fun restRequest(
        pathAndQuery: String,
        accessToken: String,
        method: String = "GET",
        bodyJson: String? = null,
        preferHeader: String? = null
    ): Result<String> {
        require(accessToken.isNotBlank()) { "Bulut işlemi için Supabase oturumu gerekli." }
        return request("rest/v1/$pathAndQuery", method, bodyJson, preferHeader, accessToken)
    }

    suspend fun authRequest(
        pathAndQuery: String,
        bodyJson: String? = null,
        accessToken: String? = null
    ): Result<String> = request("auth/v1/$pathAndQuery", "POST", bodyJson, null, accessToken)

    private suspend fun request(
        pathAndQuery: String,
        method: String,
        bodyJson: String?,
        preferHeader: String?,
        accessToken: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(
                IllegalStateException("Supabase servisi yapılandırılmamış (SUPABASE_URL veya SUPABASE_ANON_KEY eksik).")
            )
        }

        try {
            val fullUrl = "$SUPABASE_URL/$pathAndQuery"
            if (!fullUrl.startsWith("https://", ignoreCase = true)) {
                throw SecurityException("Insecure HTTP protocol is prohibited for Supabase cloud sync.")
            }

            val connection = (URL(fullUrl).openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("apikey", SUPABASE_ANON_KEY)
                if (!accessToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $accessToken")
                }
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (!preferHeader.isNullOrBlank()) {
                    setRequestProperty("Prefer", preferHeader)
                }
                requestMethod = method.uppercase()

                if (bodyJson != null) {
                    doOutput = true
                }
            }

            try {
                if (bodyJson != null) {
                    connection.outputStream.use { out ->
                        out.write(bodyJson.toByteArray(Charsets.UTF_8))
                    }
                }

                val code = connection.responseCode
                val stream: InputStream? = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val responseText = readStreamWithLimit(stream, MAX_RESPONSE_BYTES)

                if (code !in 200..299) {
                    throw SupabaseHttpException(code, "Supabase HTTP $code: ${responseText.take(300)}")
                }
                Result.success(responseText)
            } finally {
                connection.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readStreamWithLimit(stream: InputStream?, maxBytes: Int): String {
        if (stream == null) return ""
        val buffer = ByteArray(4096)
        val out = ByteArrayOutputStream()
        var totalRead = 0
        stream.use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                totalRead += bytesRead
                if (totalRead > maxBytes) {
                    throw IllegalStateException("Sunucu yanıtı izin verilen sınırı aştı (${maxBytes / 1024} KB).")
                }
                out.write(buffer, 0, bytesRead)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

class SupabaseHttpException(val statusCode: Int, message: String) : Exception(message)
