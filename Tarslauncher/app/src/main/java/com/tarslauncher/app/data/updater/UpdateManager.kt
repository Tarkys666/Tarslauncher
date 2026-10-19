package com.tarslauncher.app.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateManager(
    private val context: Context,
    private val repoOwner: String = "Tarkys666",
    private val repoName: String = "Tarslauncher"
) {
    private val gson = Gson()

    suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        // 1. Try static version.json first (Immune to GitHub 60 req/hr API rate limits and 403)
        val staticResult = checkViaStaticVersion(currentVersion)
        if (staticResult.isSuccess && staticResult.getOrNull()?.hasUpdate == true) {
            return@withContext staticResult
        }

        // 2. Fallback to GitHub Releases API if static version does not show an update or failed
        val apiResult = checkViaGitHubApi(currentVersion)
        if (apiResult.isSuccess) {
            return@withContext apiResult
        }

        // 3. If GitHub API failed (e.g. rate limit), return static result if available
        if (staticResult.isSuccess) {
            return@withContext staticResult
        }

        apiResult
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
            val expectedHash = staticInfo.sha256 ?: staticInfo.apkHash
            Result.success(
                UpdateInfo(
                    versionName = tagName,
                    releaseNotes = staticInfo.releaseNotes.orEmpty(),
                    downloadUrl = staticInfo.downloadUrl,
                    apkSize = staticInfo.apkSize,
                    hasUpdate = isNewer,
                    expectedHash = expectedHash
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
            val hashInBody = Regex("(?i)(?:sha[-_]?256|hash)[\\s:=]+([a-fA-F0-9]{64})")
                .find(release.body.orEmpty())?.groupValues?.get(1)

            val updateInfo = UpdateInfo(
                versionName = tagName,
                releaseNotes = release.body.orEmpty(),
                downloadUrl = apkAsset.downloadUrl.orEmpty(),
                apkSize = apkAsset.size,
                hasUpdate = isNewer,
                expectedHash = hashInBody
            )

            Result.success(updateInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        versionName: String,
        expectedHash: String? = null,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var targetFile: File? = null
        if (!isTrustedDownloadUrl(downloadUrl, repoOwner, repoName)) {
            return@withContext Result.failure(SecurityException("Güvenilmeyen indirme adresi reddedildi."))
        }
        if (expectedHash.isNullOrBlank() || !SHA256_PATTERN.matches(expectedHash.trim())) {
            return@withContext Result.failure(SecurityException("Sürüm için SHA-256 özeti yok; güvenlik nedeniyle güncelleme reddedildi."))
        }
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

            val safeVersionName = versionName.replace(Regex("[^a-zA-Z0-9._-]"), "").ifEmpty { "update" }
            val file = File(updatesDir, "TarsLauncher-v$safeVersionName.apk")
            if (!file.canonicalFile.startsWith(updatesDir.canonicalFile)) {
                return@withContext Result.failure(SecurityException("Geçersiz dosya yolu."))
            }
            targetFile = file

            // Download supporting redirects (GitHub redirects releases to S3/CDN)
            var currentUrl = downloadUrl
            var connection: HttpURLConnection
            var redirects = 0
            val maxRedirects = 6

            var activeConnection: HttpURLConnection? = null
            try {
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
                    activeConnection = connection

                    val code = connection.responseCode
                    if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)) {
                        val location = connection.getHeaderField("Location")
                            ?: return@withContext Result.failure(Exception("İndirme yönlendirme adresi bulunamadı."))
                        connection.disconnect()
                        val resolvedUrl = try {
                            java.net.URI(currentUrl).resolve(location).toString()
                        } catch (e: Exception) {
                            location
                        }
                        if (!isTrustedDownloadUrl(resolvedUrl, repoOwner, repoName)) {
                            return@withContext Result.failure(SecurityException("Güvenilmeyen yönlendirme adresi reddedildi."))
                        }
                        currentUrl = resolvedUrl
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
                            coroutineContext.ensureActive()
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

                // SHA-256 zorunlu (yukarıda doğrulandı)
                if (!verifyApkHash(file, expectedHash)) {
                    file.delete()
                    return@withContext Result.failure(
                        SecurityException("APK bütünlük doğrulaması başarısız oldu: İndirilen dosyanın hash değeri beklenen değer ile uyuşmuyor.")
                    )
                }

                // Paket adı, imza sertifikası ve sürüm kodu kontrolü
                val identityError = verifyApkIdentity(file)
                if (identityError != null) {
                    file.delete()
                    return@withContext Result.failure(SecurityException(identityError))
                }

                Result.success(file)
            } finally {
                activeConnection?.disconnect()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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

    /**
     * İndirilen APK'nın kimliğini doğrular: aynı paket adı, yüklü uygulamayla aynı imza sertifikası
     * ve daha yüksek sürüm kodu (downgrade engeli). Sorun varsa hata mesajı, yoksa null döner.
     */
    @Suppress("DEPRECATION")
    fun verifyApkIdentity(apkFile: File): String? {
        val pm = context.packageManager
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES or android.content.pm.PackageManager.GET_SIGNATURES
            } else {
                android.content.pm.PackageManager.GET_SIGNATURES
            }
            var archive = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: return "APK dosyası okunamadı veya bozuk."
            if (archive.packageName != context.packageName) {
                return "APK paket adı uyuşmuyor."
            }

            // Android 9-13 getPackageArchiveInfo bug fallback: if neither signingInfo nor signatures are returned
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && archive.signingInfo == null && archive.signatures == null) {
                val fallbackArchive = pm.getPackageArchiveInfo(apkFile.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
                if (fallbackArchive != null) {
                    archive = fallbackArchive
                }
            }

            val installed = pm.getPackageInfo(context.packageName, flags)

            val installedVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installed.longVersionCode else installed.versionCode.toLong()
            val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
            if (archiveVersion <= installedVersion) {
                return "APK sürümü mevcut sürümden yeni değil (downgrade reddedildi)."
            }

            fun certs(info: android.content.pm.PackageInfo): Set<String> {
                val hashes = mutableSetOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
                    val sInfo = info.signingInfo
                    sInfo?.apkContentsSigners?.forEach { s ->
                        hashes.add(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                            .joinToString("") { "%02x".format(it) })
                    }
                    sInfo?.signingCertificateHistory?.forEach { s ->
                        hashes.add(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                            .joinToString("") { "%02x".format(it) })
                    }
                }
                if (hashes.isEmpty()) {
                    info.signatures?.forEach { s ->
                        hashes.add(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                            .joinToString("") { "%02x".format(it) })
                    }
                }
                return hashes
            }
            val archiveCerts = certs(archive)
            val installedCerts = certs(installed)
            val matches = archiveCerts.isNotEmpty() && (
                archiveCerts == installedCerts ||
                (installedCerts.containsAll(archiveCerts) && archiveCerts.intersect(installedCerts).isNotEmpty())
            )
            if (!matches) {
                return "APK imza sertifikası yüklü uygulamayla eşleşmiyor."
            }
            null
        } catch (e: Exception) {
            "APK kimliği doğrulanamadı: ${e.message}"
        }
    }

    fun verifyApkHash(apkFile: File, expectedHash: String): Boolean {
        return Companion.verifyApkHash(apkFile, expectedHash)
    }

    companion object {
        private val SHA256_PATTERN = Regex("^(?:sha-?256:)?[a-fA-F0-9]{64}$", RegexOption.IGNORE_CASE)

        /**
         * Yalnızca HTTPS ve GitHub'a ait alanlardan veya release yönlendirmelerinden (CDN / S3) indirmeye izin verir.
         * İndirme URL'sinin repository yolu (/Tarkys666/Tarslauncher/) ile başladığını doğrular.
         */
        fun isTrustedDownloadUrl(
            raw: String,
            repoOwner: String = "Tarkys666",
            repoName: String = "Tarslauncher"
        ): Boolean {
            return try {
                val uri = java.net.URI(raw)
                val host = uri.host?.lowercase() ?: return false
                if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) {
                    return false
                }
                val isGitHub = host == "github.com"
                val isGitHubCdn = host == "objects.githubusercontent.com" || host.endsWith(".githubusercontent.com")
                val isGitHubS3 = host.endsWith(".amazonaws.com") &&
                    (host.startsWith("github-production-release-asset-") || host.contains("github"))

                val expectedRepoPath = "/${repoOwner.trim('/')}/${repoName.trim('/')}/".lowercase()

                when {
                    isGitHub -> {
                        val path = uri.path?.lowercase().orEmpty()
                        path.startsWith(expectedRepoPath)
                    }
                    isGitHubCdn || isGitHubS3 -> true
                    else -> false
                }
            } catch (e: Exception) {
                false
            }
        }

        fun verifyApkHash(apkFile: File, expectedHash: String): Boolean {
            if (!apkFile.exists() || !apkFile.isFile || expectedHash.isBlank()) {
                return false
            }
            val cleanExpected = if (expectedHash.contains(":")) {
                expectedHash.substringAfter(":").trim().lowercase()
            } else {
                expectedHash.trim().lowercase()
            }

            val algorithm = when (cleanExpected.length) {
                64 -> "SHA-256"
                128 -> "SHA-512"
                else -> "SHA-256"
            }

            return try {
                val digest = MessageDigest.getInstance(algorithm)
                apkFile.inputStream().buffered().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                val calculatedHash = digest.digest().joinToString("") { "%02x".format(it) }
                calculatedHash.equals(cleanExpected, ignoreCase = true)
            } catch (e: Exception) {
                false
            }
        }

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
