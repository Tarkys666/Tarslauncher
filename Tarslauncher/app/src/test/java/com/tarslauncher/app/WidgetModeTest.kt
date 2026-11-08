package com.tarslauncher.app

import com.google.gson.JsonObject
import com.tarslauncher.app.data.model.LauncherSettings
import com.tarslauncher.app.data.sync.BackupPayload
import com.tarslauncher.app.ui.LauncherUiState
import org.junit.Assert.*
import org.junit.Test

class WidgetModeTest {
    @Test fun widgetsHideAllThreeDecorationsAndRestoreThePreviousPreferences() {
        // Check every combination, including a restored backup that enabled all three.
        for (header in listOf(false, true)) for (clock in listOf(false, true)) for (diamond in listOf(false, true)) {
            val previous = LauncherSettings(showTarsHeader = header, showClock = clock, showAsciiDiamond = diamond)
            val enabled = LauncherUiState(settings = previous.copy(showWidgets = true))
            assertTrue(enabled.showWidgets)
            assertFalse(enabled.showTarsHeader)
            assertFalse(enabled.showClock)
            assertFalse(enabled.showAsciiDiamond)
            val disabled = enabled.copy(settings = enabled.settings.copy(showWidgets = false))
            assertEquals(header, disabled.showTarsHeader)
            assertEquals(clock, disabled.showClock)
            assertEquals(diamond, disabled.showAsciiDiamond)
            assertEquals(previous, disabled.settings)
        }
    }

    @Test fun backupRestoresWidgetModeWithoutDiscardingTheDecorations() {
        val backup = BackupPayload.fromRow(JsonObject().apply {
            add("payload", JsonObject().apply {
                add("settings", JsonObject().apply {
                    addProperty("showWidgets", true)
                    addProperty("showClock", true)
                    addProperty("showTarsHeader", true)
                    addProperty("showAsciiDiamond", true)
                })
            })
        })
        assertTrue(backup.settings.showWidgets)
        assertTrue(backup.settings.showClock)
        assertTrue(backup.settings.showTarsHeader)
        assertTrue(backup.settings.showAsciiDiamond)
        val legacy = BackupPayload.fromRow(JsonObject().apply { add("payload", JsonObject()) })
        assertFalse(legacy.settings.showWidgets)
    }
}
