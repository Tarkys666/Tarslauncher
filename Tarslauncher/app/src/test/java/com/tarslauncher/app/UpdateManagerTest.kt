package com.tarslauncher.app

import com.tarslauncher.app.data.updater.UpdateManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

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
