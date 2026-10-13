package com.tarslauncher.app.utils

import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object PwaFetcherHelper {
    private const val TAG = "PwaFetcherHelper"

    private val DOMAIN_REGEX = Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z0-9-]{2,}$")

    /**
     * Resolves high resolution icon for a given URL via Google's high-res favicon API.
     */
    suspend fun fetchIconBase64(url: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val domain = extractDomain(url) ?: return@withContext null
            val iconServiceUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://$domain&size=128"
            
            connection = (URL(iconServiceUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
            }
            connection.connect()

            if (connection.responseCode in 200..299) {
                connection.inputStream.use { inputStream ->
                    val imageBytes = inputStream.readBytes()
                    if (imageBytes.isNotEmpty()) {
                        // Validate image without allocating full pixel bitmap
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
                        if (options.outWidth > 0 && options.outHeight > 0) {
                            return@withContext android.util.Base64.encodeToString(
                                imageBytes,
                                android.util.Base64.NO_WRAP
                            )
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching icon for $url: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Extracts and validates a clean domain from URL.
     */
    fun extractDomain(url: String): String? {
        val cleanUrl = if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            "https://$url"
        } else {
            url
        }
        return try {
            val uri = URI(cleanUrl)
            val host = uri.host?.removePrefix("www.")?.removePrefix("m.")?.lowercase()
            if (host != null && host.matches(DOMAIN_REGEX)) {
                host
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generates a readable default app name from domain if user did not provide one.
     */
    fun extractDefaultTitle(url: String): String {
        val domain = extractDomain(url) ?: return "Web App"
        val firstPart = domain.substringBefore(".")
        return firstPart.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
