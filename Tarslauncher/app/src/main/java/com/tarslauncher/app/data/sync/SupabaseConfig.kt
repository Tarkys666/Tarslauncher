package com.tarslauncher.app.data.sync

import android.util.Log
import com.tarslauncher.app.BuildConfig
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Core Supabase REST & Edge Function client for TARS Launcher.
 * Credentials are loaded safely at build time via BuildConfig / local.properties,
 * ensuring zero hardcoded secrets in the open-source repository.
 */
object SupabaseConfig {
    private const val TAG = "SupabaseConfig"

    private const val DEFAULT_SUPABASE_URL = "https://nznftexnhmzboihbeilq.supabase.co"
    private const val DEFAULT_SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im56bmZ0ZXhuaG16Ym9paGJlaWxxIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTIzMzk0OTUsImV4cCI6MjEwNzkxNTQ5NX0.FZo6dEMZNbe33Bj8VjQ6VZ57j-O-y464YPJQXDS6kD0"

    /**
     * Supabase project base URL (e.g. https://<project-ref>.supabase.co).
     */
    val SUPABASE_URL: String
        get() = BuildConfig.SUPABASE_URL.ifBlank { DEFAULT_SUPABASE_URL }.trim()

    /**
     * Supabase anon/public JWT key.
     */
    val SUPABASE_ANON_KEY: String
        get() = BuildConfig.SUPABASE_ANON_KEY.ifBlank { DEFAULT_SUPABASE_ANON_KEY }.trim()

    val REST_BASE_URL: String
        get() = "$SUPABASE_URL/rest/v1"

    val EDGE_CHAT_URL: String
        get() = "$SUPABASE_URL/functions/v1/tars-chat"

    /**
     * Returns true when both Supabase URL and Anon Key are configured.
     */
    fun isConfigured(): Boolean =
        SUPABASE_URL.isNotBlank() && SUPABASE_ANON_KEY.isNotBlank()

    fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    /**
     * Executes a PostgREST HTTP request against `<SUPABASE_URL>/rest/v1/<pathAndQuery>`.
     */
    suspend fun restRequest(
        pathAndQuery: String,
        method: String = "GET",
        bodyJson: String? = null,
        preferHeader: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(
                IllegalStateException("Supabase servisi yapılandırılmamış (SUPABASE_URL veya SUPABASE_ANON_KEY eksik).")
            )
        }

        runCatching {
            val fullUrl = "$REST_BASE_URL/$pathAndQuery"
            val connection = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("apikey", SUPABASE_ANON_KEY)
                setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (!preferHeader.isNullOrBlank()) {
                    setRequestProperty("Prefer", preferHeader)
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
                val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                if (code !in 200..299) {
                    throw RuntimeException("Supabase HTTP $code: ${responseText.take(300)}")
                }
                responseText
            } finally {
                connection.disconnect()
            }
        }
    }
}
