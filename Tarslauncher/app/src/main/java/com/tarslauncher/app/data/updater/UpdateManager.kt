package com.tarslauncher.app.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.tarslauncher.app.data.model.RepositoryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_REPO_OWNER = "Tarkys666"
        private const val GITHUB_REPO_NAME = "Tarslauncher"
        private const val GITHUB_LATEST_RELEASE_URL =
            "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
    }

    /**
     * Checks GitHub Releases for a newer version than currentVersion.
     */
    suspend fun checkForUpdates(currentVersion: String): RepositoryResult<UpdateInfo?> =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(GITHUB_LATEST_RELEASE_URL)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "TARS-Launcher-App")
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext RepositoryResult.Error(
                        IllegalStateException("GitHub API returned HTTP $responseCode"),
                        "Güncellemeler denetlenemedi (HTTP $responseCode)"
                    )
                }

                val jsonResponse = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JsonParser.parseString(jsonResponse).asJsonObject

                val tagName = jsonObject.get("tag_name")?.asString ?: ""
                val releaseNotes = jsonObject.get("body")?.asString ?: ""
                val publishedAt = jsonObject.get("published_at")?.asString ?: ""
                val cleanRemoteVersion = tagName.removePrefix("v").removePrefix("V").trim()

                // Find APK download asset
                val assets = jsonObject.getAsJsonArray("assets")
                var downloadUrl: String? = null
                var apkSize = 0L

                if (assets != null) {
                    for (i in 0 until assets.size()) {
                        val assetObj = assets[i].asJsonObject
                        val name = assetObj.get("name")?.asString ?: ""
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = assetObj.get("browser_download_url")?.asString
                            apkSize = assetObj.get("size")?.asLong ?: 0L
                            break
                        }
                    }
                }

                if (downloadUrl.isNullOrBlank()) {
                    Log.w(TAG, "No APK asset found in latest release $tagName")
                    return@withContext RepositoryResult.Success(null)
                }

                val isNewer = isVersionNewer(cleanRemoteVersion, currentVersion)
                if (isNewer) {
                    val updateInfo = UpdateInfo(
                        versionName = cleanRemoteVersion,
                        tagName = tagName,
                        releaseNotes = releaseNotes,
                        downloadUrl = downloadUrl,
                        publishedAt = publishedAt,
                        apkSize = apkSize
                    )
                    RepositoryResult.Success(updateInfo)
                } else {
                    RepositoryResult.Success(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                RepositoryResult.Error(e, "Güncelleme kontrolü sırasında bağlantı hatası oluştu.")
            }
        }

    /**
     * Downloads the APK file to cache directory with progress reporting.
     */
    suspend fun downloadApk(
        updateInfo: UpdateInfo,
        onProgress: (progress: Float, downloaded: Long, total: Long) -> Unit
    ): RepositoryResult<File> = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates").apply {
                if (!exists()) mkdirs()
            }
            val destinationFile = File(updatesDir, "TarsLauncher-v${updateInfo.versionName}.apk")
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            var connection = (URL(updateInfo.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "TARS-Launcher-App")
                instanceFollowRedirects = true
            }

            // Handle manual redirect if needed
            var redirectCode = connection.responseCode
            if (redirectCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                redirectCode == HttpURLConnection.HTTP_MOVED_PERM ||
                redirectCode == HttpURLConnection.HTTP_SEE_OTHER
            ) {
                val newUrl = connection.getHeaderField("Location")
                connection = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "TARS-Launcher-App")
                }
            }

            val totalBytes = if (connection.contentLengthLong > 0) connection.contentLengthLong else updateInfo.apkSize
            var downloadedBytes = 0L

            connection.inputStream.use { input: InputStream ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var lastReportedTime = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastReportedTime > 100 || downloadedBytes == totalBytes) {
                            lastReportedTime = currentTime
                            val progress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                            onProgress(progress.coerceIn(0f, 1f), downloadedBytes, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            RepositoryResult.Success(destinationFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading APK update", e)
            RepositoryResult.Error(e, "Güncelleme indirilemedi: ${e.localizedMessage}")
        }
    }

    /**
     * Prompts the system package installer to install the downloaded APK.
     */
    fun installApk(apkFile: File): Boolean {
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file does not exist: ${apkFile.absolutePath}")
            return false
        }

        return try {
            // Android 8.0 (API 26) check for unknown sources
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            false
        }
    }

    /**
     * Compares remote SemVer with current SemVer.
     * Returns true if remote is strictly greater.
     */
    fun isVersionNewer(remote: String, current: String): Boolean {
        val cleanRemote = remote.removePrefix("v").removePrefix("V").substringBefore("-").trim()
        val cleanCurrent = current.removePrefix("v").removePrefix("V").substringBefore("-").trim()

        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

        val maxParts = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxParts) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
