package com.tarslauncher.app

import com.google.gson.JsonObject
import com.tarslauncher.app.data.model.GridSize
import com.tarslauncher.app.data.sync.BackupPayload
import org.junit.Assert.*
import org.junit.Test

class BackupPayloadTest {
    private fun row(settings: JsonObject = JsonObject(), folders: String = "[]", pwas: String = "[]", usage: String = "[]") = JsonObject().apply {
        addProperty("updated_at", 123L)
        add("payload", JsonObject().apply {
            add("settings", settings)
            addProperty("foldersJson", folders)
            addProperty("pwasJson", pwas)
            addProperty("usageStatsJson", usage)
        })
    }

    @Test fun parsesValidBackupWithoutMutatingItsInput() {
        val input = row(
            settings = JsonObject().apply { addProperty("gridSize", "5x5") },
            folders = """[{"id":"1","apps":["com.example.app"]}]""",
            pwas = """[{"id":"pwa1","name":"Example","url":"https://example.com"}]""",
            usage = """[{"packageName":"com.example.app","usageCount":3,"isFavorite":true}]"""
        )
        val original = input.toString()
        val backup = BackupPayload.fromRow(input)
        assertEquals(25, backup.settings.gridSize.totalCells)
        assertEquals(123L, backup.updatedAt)
        assertNotNull(backup.foldersJson)
        assertNotNull(backup.pwasJson)
        assertEquals(original, input.toString())
    }

    @Test fun rejectsOversizedGridAndUnsafeNumericSettings() {
        for ((field, value) in listOf(
            "gridSize" to "1000000x1000", "gridSize" to "65536x65536",
            "appDrawerIconSize" to -1, "appDrawerRows" to 1000000,
            "asciiDiamondScale" to Int.MAX_VALUE, "appDrawerOffsetY" to -1,
            "asciiDiamondFps" to 0, "asciiDiamondSpeed" to -1,
            "appDrawerIconSize" to 4294967328L, "appDrawerRows" to 1.5
        )) {
            val settings = JsonObject().apply {
                if (value is Number) addProperty(field, value) else addProperty(field, value.toString())
            }
            assertTrue("$field=$value", runCatching { BackupPayload.fromRow(row(settings)) }.isFailure)
        }
    }

    @Test fun rejectsMalformedCollectionsAndDuplicatePwaIdsBeforeSaving() {
        val invalid = listOf(
            row(folders = "not-json"), row(folders = "{}"),
            row(folders = """[{"id":"1","apps":[null]}]"""),
            row(pwas = """[{"name":"missing id"}]"""),
            row(pwas = """[{"id":"same","name":"A"},{"id":"same","name":"B"}]"""),
            row(usage = """[{"packageName":"com.example.app","usageCount":-1}]"""),
            row(usage = """[{"packageName":"com.example.app","isFavorite":"yes"}]""")
        )
        invalid.forEach { assertTrue(runCatching { BackupPayload.fromRow(it) }.isFailure) }
    }

    @Test fun missingOptionalLegacyFieldsKeepSafeDefaults() {
        val backup = BackupPayload.fromRow(JsonObject().apply { add("payload", JsonObject()) })
        assertEquals(GridSize.DEFAULT, backup.settings.gridSize)
        assertEquals(32, backup.settings.appDrawerIconSize)
        assertNull(backup.foldersJson)
        assertNull(backup.pwasJson)
        assertNull(backup.usageStatsJson)
    }
}
