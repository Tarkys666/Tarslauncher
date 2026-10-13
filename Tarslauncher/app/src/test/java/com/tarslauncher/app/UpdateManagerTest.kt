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
}
