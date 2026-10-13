package com.tarslauncher.app.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(
    private val context: Context,
    private val repoOwner: String = "Tarkys666",
    private val repoName: String = "Tarslauncher"
) {
    private val gson = Gson()

    suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        // 1. Try static version.json first (Immune to GitHub 60 req/hr API rate limits and 403)
        val staticResult = checkViaStaticVersion(currentVersion)
        if (staticResult.isSuccess) {
            return@withContext staticResult
        }

        // 2. Fallback to GitHub Releases API
        checkViaGitHubApi(currentVersion)
    }

    private fun checkViaStaticVersion(currentVersion: String): Result<UpdateInfo> {
        return try {
            val rawUrl = "https://raw.githubusercontent.com/$repoOwner/$repoName/main/version.json"
            val url = URL(rawUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("User-Agent", "TarsLauncher-Android")
            }

            if (connection.responseCode !in 200..299) {
                return Result.failure(Exception("Static version HTTP ${connection.responseCode}"))
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val staticInfo = gson.fromJson(body, StaticVersionInfo::class.java)
                ?: return Result.failure(Exception("Statik sürüm bilgisi ayrıştırılamadı."))

            val tagName = staticInfo.versionName.orEmpty().removePrefix("v").removePrefix("V").trim()
            if (tagName.isEmpty() || staticInfo.downloadUrl.isNullOrEmpty()) {
                return Result.failure(Exception("Geçersiz statik sürüm."))
            }

            val isNewer = isNewerVersion(remoteVersion = tagName, currentVersion = currentVersion)
            Result.success(
                UpdateInfo(
                    versionName = tagName,
                    releaseNotes = staticInfo.releaseNotes.orEmpty(),
                    downloadUrl = staticInfo.downloadUrl,
                    apkSize = staticInfo.apkSize,
                    hasUpdate = isNewer
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun checkViaGitHubApi(currentVersion: String): Result<UpdateInfo> {
        return try {
            val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            val url = URL(apiUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "TarsLauncher-Android")
            }

            val responseCode = connection.responseCode
            if (responseCode == 404) {
                return Result.failure(Exception("Henüz yayınlanmış bir sürüm bulunamadı."))
            }
            if (responseCode !in 200..299) {
                return Result.failure(Exception("GitHub API hatası: HTTP $responseCode"))
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val release = gson.fromJson(responseBody, GitHubRelease::class.java)
                ?: return Result.failure(Exception("Sürüm bilgisi ayrıştırılamadı."))

            val tagName = release.tagName.orEmpty().removePrefix("v").removePrefix("V").trim()
            if (tagName.isEmpty()) {
                return Result.failure(Exception("Geçersiz sürüm numarası."))
            }

            // Find APK asset
            val apkAsset = release.assets?.firstOrNull { 
                it.name.orEmpty().endsWith(".apk", ignoreCase = true) 
            } ?: return Result.failure(Exception("Sürüm içinde APK dosyası bulunamadı."))

            val isNewer = isNewerVersion(remoteVersion = tagName, currentVersion = currentVersion)
            val updateInfo = UpdateInfo(
                versionName = tagName,
                releaseNotes = release.body.orEmpty(),
                downloadUrl = apkAsset.downloadUrl.orEmpty(),
                apkSize = apkAsset.size,
                hasUpdate = isNewer
            )

            Result.success(updateInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        versionName: String,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var targetFile: File? = null
        try {
            val updatesDir = File(context.cacheDir, "updates").apply {
                if (!exists()) mkdirs()
            }

            // Clean up any old downloaded APK files
            updatesDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".apk")) {
                    file.delete()
                }
            }

            val file = File(updatesDir, "TarsLauncher-v$versionName.apk")
            targetFile = file

            // Download supporting redirects (GitHub redirects releases to S3/CDN)
            var currentUrl = downloadUrl
            var connection: HttpURLConnection
            var redirects = 0
            val maxRedirects = 6

            while (true) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "TarsLauncher-Android")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Connection", "keep-alive")
                }

                val code = connection.responseCode
                if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: return@withContext Result.failure(Exception("İndirme yönlendirme adresi bulunamadı."))
                    currentUrl = location
                    redirects++
                    if (redirects > maxRedirects) {
                        return@withContext Result.failure(Exception("Çok fazla yönlendirme hatası."))
                    }
                } else if (code in 200..299) {
                    break
                } else {
                    return@withContext Result.failure(Exception("APK indirme hatası: HTTP $code"))
                }
            }

            val totalLength = connection.contentLengthLong
            var downloaded = 0L

            val bufferSize = 64 * 1024 // 64 KB high-throughput buffer to maximize socket I/O efficiency
            val buffer = ByteArray(bufferSize)
            var bytesRead: Int

            var lastProgressTime = 0L
            val throttleIntervalMs = 120L // Throttles progress emission to prevent Compose UI recomposition bottleneck

            BufferedInputStream(connection.inputStream, bufferSize).use { input ->
                BufferedOutputStream(FileOutputStream(file), bufferSize).use { output ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastProgressTime >= throttleIntervalMs || (totalLength > 0 && downloaded >= totalLength)) {
                            lastProgressTime = now
                            val progress = if (totalLength > 0) downloaded.toFloat() / totalLength else 0f
                            onProgress(progress, downloaded, totalLength)
                        }
                    }
                    output.flush()
                }
            }

            // Ensure terminal 100% progress event is emitted
            if (totalLength > 0 && downloaded >= totalLength) {
                onProgress(1f, totalLength, totalLength)
            }

            Result.success(file)
        } catch (e: Exception) {
            targetFile?.let { if (it.exists()) it.delete() }
            Result.failure(e)
        }
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    fun installApk(apkFile: File): Result<Unit> {
        return try {
            if (!apkFile.exists()) {
                return Result.failure(Exception("Yüklenecek APK dosyası bulunamadı."))
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(installIntent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
            val cleanRemote = remoteVersion.removePrefix("v").removePrefix("V").substringBefore("-").trim()
            val cleanCurrent = currentVersion.removePrefix("v").removePrefix("V").substringBefore("-").trim()

            val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }
}
