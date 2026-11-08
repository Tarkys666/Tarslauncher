package com.tarslauncher.app

import com.tarslauncher.app.data.updater.UpdateManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun downloadsMustStartAtThisRepositorysRelease() {
        assertTrue(UpdateManager.isTrustedDownloadUrl("https://github.com/Tarkys666/Tarslauncher/releases/download/v2.0.27/TarsLauncher-release.apk"))
        for (url in listOf(
            "https://github.com/another/repository/releases/download/v1.0.0/app.apk",
            "https://github.com/Tarkys666/Tarslauncher/blob/main/app.apk",
            "https://github.com/Tarkys666/Tarslauncher/releases/download/v1/../app.apk",
            "https://github.com/Tarkys666/Tarslauncher/releases/download/v1/%2e%2e/app.apk",
            "https://github.com:8443/Tarkys666/Tarslauncher/releases/download/v1/app.apk",
            "https://user@github.com/Tarkys666/Tarslauncher/releases/download/v1/app.apk",
            "http://github.com/Tarkys666/Tarslauncher/releases/download/v1/app.apk",
            "https://release-assets.githubusercontent.com/asset.apk"
        )) assertFalse(url, UpdateManager.isTrustedDownloadUrl(url))
    }

    @Test
    fun redirectsOnlyAllowExactGitHubAssetHosts() {
        for (host in listOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")) {
            assertTrue(UpdateManager.isTrustedDownloadUrl("https://$host/asset", allowAssetHost = true))
        }
        for (host in listOf("github-review-example.s3.amazonaws.com", "github-production-release-asset-untrusted.s3.amazonaws.com", "raw.githubusercontent.com", "user-images.githubusercontent.com", "release-assets.githubusercontent.com.example.com", "evil.release-assets.githubusercontent.com")) {
            assertFalse(host, UpdateManager.isTrustedDownloadUrl("https://$host/asset", allowAssetHost = true))
        }
    }

    @Test
    fun unreadableAndMismatchedSigningCertificatesNeverPass() {
        assertFalse(UpdateManager.signingCertificatesMatch(emptySet(), setOf("installed")))
        assertFalse(UpdateManager.signingCertificatesMatch(setOf("archive"), emptySet()))
        assertFalse(UpdateManager.signingCertificatesMatch(emptySet(), emptySet()))
        assertFalse(UpdateManager.signingCertificatesMatch(setOf("unrelated"), setOf("installed")))
        assertTrue(UpdateManager.signingCertificatesMatch(setOf("installed"), setOf("installed")))
        assertTrue(UpdateManager.signingCertificatesMatch(setOf("old"), setOf("old", "current")))
    }

    @Test
    fun isNewerVersion_greaterPatch_returnsTrue() {
        assertTrue(UpdateManager.isNewerVersion("1.2.1", "1.2.0"))
    }

    @Test
    fun isNewerVersion_greaterMinor_returnsTrue() {
        assertTrue(UpdateManager.isNewerVersion("1.3.0", "1.2.9"))
    }

    @Test
    fun isNewerVersion_greaterMajor_returnsTrue() {
        assertTrue(UpdateManager.isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun isNewerVersion_withPrefixV_returnsTrue() {
        assertTrue(UpdateManager.isNewerVersion("v1.2.1", "1.2.0"))
        assertTrue(UpdateManager.isNewerVersion("1.2.1", "v1.2.0"))
    }

    @Test
    fun isNewerVersion_withSuffix_returnsTrue() {
        assertTrue(UpdateManager.isNewerVersion("1.2.1-release", "1.2.0-debug"))
    }

    @Test
    fun isNewerVersion_sameVersion_returnsFalse() {
        assertFalse(UpdateManager.isNewerVersion("1.2.0", "1.2.0"))
        assertFalse(UpdateManager.isNewerVersion("v1.2.0", "1.2.0"))
        assertFalse(UpdateManager.isNewerVersion("1.2.0", "v1.2.0"))
    }

    @Test
    fun isNewerVersion_olderVersion_returnsFalse() {
        assertFalse(UpdateManager.isNewerVersion("1.1.9", "1.2.0"))
        assertFalse(UpdateManager.isNewerVersion("1.0.0", "1.2.0"))
    }

    @Test
    fun verifyApkHash_correctHash_returnsTrue() {
        val tempFile = java.io.File.createTempFile("test_apk", ".apk").apply {
            writeText("Test APK content for integrity check")
            deleteOnExit()
        }
        // echo -n "Test APK content for integrity check" | sha256sum -> e.g. calculated
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val expectedHash = digest.digest("Test APK content for integrity check".toByteArray())
            .joinToString("") { "%02x".format(it) }

        assertTrue(UpdateManager.verifyApkHash(tempFile, expectedHash))
        assertTrue(UpdateManager.verifyApkHash(tempFile, "SHA256:$expectedHash"))
        tempFile.delete()
    }

    @Test
    fun verifyApkHash_mismatchedHash_returnsFalse() {
        val tempFile = java.io.File.createTempFile("test_apk_mismatch", ".apk").apply {
            writeText("Test APK content")
            deleteOnExit()
        }
        val wrongHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        assertFalse(UpdateManager.verifyApkHash(tempFile, wrongHash))
        tempFile.delete()
    }

    @Test
    fun verifyApkHash_nonExistentFileOrBlankHash_returnsFalse() {
        val nonExistent = java.io.File("non_existent_file.apk")
        assertFalse(UpdateManager.verifyApkHash(nonExistent, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))

        val tempFile = java.io.File.createTempFile("test_blank", ".apk").apply {
            writeText("some content")
            deleteOnExit()
        }
        assertFalse(UpdateManager.verifyApkHash(tempFile, ""))
        assertFalse(UpdateManager.verifyApkHash(tempFile, "   "))
        tempFile.delete()
    }
}
