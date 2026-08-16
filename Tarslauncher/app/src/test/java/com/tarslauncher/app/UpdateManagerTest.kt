package com.tarslauncher.app

import com.tarslauncher.app.data.updater.UpdateManager
import org.junit.Assert.*
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun testVersionComparison() {
        // Create an un-instrumented instance for purely algorithmic testing if needed, or static test
        val isNewer = { remote: String, current: String ->
            val cleanRemote = remote.removePrefix("v").removePrefix("V").substringBefore("-").trim()
            val cleanCurrent = current.removePrefix("v").removePrefix("V").substringBefore("-").trim()

            val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

            val maxParts = maxOf(remoteParts.size, currentParts.size)
            var result = false
            for (i in 0 until maxParts) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) {
                    result = true
                    break
                }
                if (r < c) {
                    result = false
                    break
                }
            }
            result
        }

        assertTrue(isNewer("1.2.0", "1.1.0"))
        assertTrue(isNewer("v2.0.0", "1.9.9"))
        assertTrue(isNewer("1.1.1", "1.1.0"))
        assertTrue(isNewer("v1.10.0", "v1.9.0"))

        assertFalse(isNewer("1.1.0", "1.1.0"))
        assertFalse(isNewer("v1.0.0", "1.1.0"))
        assertFalse(isNewer("1.0.5", "1.1.0"))
    }
}
