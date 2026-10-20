package com.tarslauncher.app.data.sync

import android.util.Log
import com.tarslauncher.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight, zero-bloat Supabase REST client for TARS Launcher.
 * Uses standard HttpsURLConnection without heavy external SDKs, keeping the APK ultra-small (~1.3 MB).
 * Credentials are injected via BuildConfig at build time.
 * Enforces HTTPS, granular header binding for RLS, and response size bounds to prevent DoS/OOM.
 */
object SupabaseConfig {
    private const val TAG = "SupabaseConfig"
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 // 2 MB response guard

    val SUPABASE_URL: String
        get() = BuildConfig.SUPABASE_URL.trim().removeSuffix("/")

    val SUPABASE_ANON_KEY: String
        get() = BuildConfig.SUPABASE_ANON_KEY.trim()

    val REST_BASE_URL: String
        get() = "$SUPABASE_URL/rest/v1"

    fun isConfigured(): Boolean =
        SUPABASE_URL.isNotBlank() && SUPABASE_ANON_KEY.isNotBlank()

    fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * Executes a PostgREST HTTP request against `<SUPABASE_URL>/rest/v1/<pathAndQuery>`.
     * Securely binds `x-tars-user-id` header when provided to enforce database-level RLS isolation.
     */
    suspend fun restRequest(
        pathAndQuery: String,
        method: String = "GET",
        bodyJson: String? = null,
        preferHeader: String? = null,
        userIdHeader: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(
                IllegalStateException("Supabase servisi yapılandırılmamış (SUPABASE_URL veya SUPABASE_ANON_KEY eksik).")
            )
        }

        runCatching {
            val fullUrl = "$REST_BASE_URL/$pathAndQuery"
            if (!fullUrl.startsWith("https://", ignoreCase = true)) {
                throw SecurityException("Insecure HTTP protocol is prohibited for Supabase cloud sync.")
            }

            val connection = (URL(fullUrl).openConnection() as HttpsURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("apikey", SUPABASE_ANON_KEY)
                setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (!preferHeader.isNullOrBlank()) {
                    setRequestProperty("Prefer", preferHeader)
                }
                if (!userIdHeader.isNullOrBlank()) {
                    setRequestProperty("x-tars-user-id", userIdHeader)
                }

                if (method.equals("PATCH", ignoreCase = true)) {
                    requestMethod = "POST"
                    setRequestProperty("X-HTTP-Method-Override", "PATCH")
                } else {
                    requestMethod = method.uppercase()
                }

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
                    throw RuntimeException("Supabase HTTP $code: ${responseText.take(300)}")
                }
                responseText
            } finally {
                connection.disconnect()
            }
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
