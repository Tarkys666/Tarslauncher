package com.tarslauncher.app

import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tarslauncher.app.ui.screens.HomeScreen
import com.tarslauncher.app.data.model.GridSize
import com.tarslauncher.app.widgets.LauncherWidgetController
import com.tarslauncher.app.widgets.WidgetPanel
import com.tarslauncher.app.WidgetFixtures.ReadyProvider
import com.tarslauncher.app.WidgetFixtures.CancelledProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs against real Android widget services; fixtures exist only in the test APK. */
@RunWith(AndroidJUnit4::class)
class WidgetHostingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun bindsConfiguresRendersPersistsAndRemovesWidgetsWithoutLeakingCancelledIds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Permission setup is confined to the disposable test device.
        instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package com.tarslauncher.app").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: LauncherWidgetController
            val mode = mutableStateOf(true)
            fun render(activity: MainActivity) {
                controller = activity.widgetController
                activity.setContent {
                    HomeScreen(
                        installedApps = emptyList(), folders = emptyList(), onFoldersUpdate = {},
                        onSettingsClick = {}, onAppClick = {}, showAppDrawer = false,
                        gridSize = GridSize.DEFAULT, iconPackLoader = null, showTarsHeader = true,
                        appDrawerIconSize = 32, showClock = true, showAsciiDiamond = false,
                        showWidgets = mode.value, showFolderGrid = false,
                        widgetContent = { WidgetPanel(controller) }
                    )
                }
            }
            scenario.onActivity { activity ->
                render(activity)
                controller.widgetIds.value.forEach(controller::remove)
                val provider = controller.providers().first { it.provider.className == ReadyProvider::class.java.name }
                controller.add(provider)
            }
            compose.waitUntil(20_000) { controller.widgetIds.value.size == 1 }
            compose.onNodeWithText("+ ADD WIDGET").assertIsDisplayed()
            compose.onNodeWithText("F A C E T").assertDoesNotExist()
            onView(withText("Widget ready")).check(matches(isDisplayed()))
            val id = controller.widgetIds.value.single()
            scenario.onActivity {
                val options = AppWidgetManager.getInstance(it).getAppWidgetOptions(id)
                assertTrue(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) > 0)
                assertTrue(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) > 0)
            }
            compose.onNodeWithText("EDIT").performClick()
            compose.onNodeWithText("+").performClick()
            var resizedHeight = 0
            scenario.onActivity { resizedHeight = controller.height(id, controller.info(id)!!) }
            scenario.recreate()
            scenario.onActivity { activity ->
                render(activity)
                assertEquals(listOf(id), controller.widgetIds.value)
                assertEquals(resizedHeight, controller.height(id, controller.info(id)!!))
                controller.add(controller.providers().first { it.provider.className == CancelledProvider::class.java.name })
            }
            compose.waitUntil(20_000) { controller.host.appWidgetIds.toList() == listOf(id) }
            onView(withText("Widget ready")).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val output = File(activity.getExternalFilesDir(null), "widget-test.png")
                output.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.runOnIdle { mode.value = false }
            compose.onNodeWithText("F A C E T").assertIsDisplayed()
            compose.onNodeWithText("+ ADD WIDGET").assertDoesNotExist()
            compose.runOnIdle { mode.value = true }
            compose.onNodeWithText("EDIT").performClick()
            compose.onNodeWithText("REMOVE").performClick()
            compose.onNodeWithText("Add your first widget").assertIsDisplayed()
            assertTrue(controller.host.appWidgetIds.isEmpty())
        }
    }
}
