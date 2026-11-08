package com.tarslauncher.app

import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tarslauncher.app.data.model.AppInfo
import com.tarslauncher.app.data.model.getOrDefault
import com.tarslauncher.app.repository.AppRepository
import com.tarslauncher.app.ui.LauncherViewModel
import com.tarslauncher.app.ui.LauncherViewModelFactory
import com.tarslauncher.app.ui.components.AsciiDiamondCanvas
import com.tarslauncher.app.ui.screens.rememberAppIcon
import com.tarslauncher.app.utils.SecureBroadcastHelper
import com.tarslauncher.app.utils.getAppImageBitmap
import com.tarslauncher.app.utils.drawableToBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Functional lifecycle/cache checks, not a device battery benchmark. */
@RunWith(AndroidJUnit4::class)
class BatteryOptimizationsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun staticDiamondRendersOnceAndBackgroundStopsAllFrames() {
        val frames = AtomicInteger()
        val speed = mutableStateOf(0)
        val scale = mutableStateOf(100)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AsciiDiamondCanvas(speed = speed.value, scale = scale.value, onFrameRendered = { frames.incrementAndGet() })
                }
            }
            compose.waitUntil(10_000) { frames.get() == 1 }
            Thread.sleep(250)
            assertEquals("Static mode must not keep producing identical frames", 1, frames.get())
            scenario.onActivity { speed.value = 100 }
            compose.waitUntil(10_000) { frames.get() >= 4 }
            scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(100)
            val stopped = frames.get()
            Thread.sleep(300)
            assertEquals("Background renderer must be cancelled", stopped, frames.get())
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { frames.get() >= stopped + 3 }
            scenario.onActivity { speed.value = 0 }
            Thread.sleep(250)
            val frozen = frames.get()
            Thread.sleep(250)
            assertEquals(frozen, frames.get())
            scenario.onActivity { scale.value = 120 }
            compose.waitUntil(10_000) { frames.get() == frozen + 1 }
            Thread.sleep(250)
            assertEquals("Changing a static image should draw only one new frame", frozen + 1, frames.get())
        }
    }

    @Test fun themedIconsRasterizeOffMainAndPackageRefreshRetainsUnrelatedIcons(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val loader = IconPackLoader(context, instrumentation.context.packageName)
        withContext(Dispatchers.IO) { loader.load() }
        val drawable = ThreadTrackingDrawable()
        val app = AppInfo("Test", "com.example.batterytest", drawable, "TestActivity")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    if (rememberAppIcon(app, loader) != null) Text("icon ready")
                }
            }
            compose.waitUntil(10_000) { drawable.draws.get() > 0 }
            compose.onNodeWithText("icon ready").assertExists()
            assertFalse("Fallback masking must not draw on the UI thread", drawable.drewOnMain.get())
            withContext(Dispatchers.Default) {
                val oldTheme = drawableToBitmap(ColorDrawable(android.graphics.Color.RED), "theme-update-test")
                val newTheme = drawableToBitmap(ColorDrawable(android.graphics.Color.BLUE), "theme-update-test")
                assertNotSame("An old theme decode must not poison the updated theme's cache", oldTheme, newTheme)
                assertEquals(android.graphics.Color.BLUE, newTheme.getPixel(0, 0))
            }

            val repository = AppRepository(context)
            val before = repository.getInstalledApps().getOrDefault(emptyList())
            val changed = before.first { it.packageName == "com.android.settings" }
            val retained = before.first { it.packageName != changed.packageName && !it.isPwa && it.icon != null }
            val retainedBitmap = withContext(Dispatchers.Default) { getAppImageBitmap(retained.packageName, retained.icon) }
            repository.invalidatePackages(setOf(changed.packageName))
            val after = repository.getInstalledApps().getOrDefault(emptyList())
            assertNotSame(changed, after.first { it.packageName == changed.packageName })
            assertSame(retained, after.first { it.packageName == retained.packageName })
            assertSame(retainedBitmap, withContext(Dispatchers.Default) { getAppImageBitmap(retained.packageName, retained.icon) })
            repository.invalidatePwaCache()
            assertSame(retained, repository.getInstalledApps().getOrDefault(emptyList()).first { it.packageName == retained.packageName })
        }
    }

    @Test fun packageBurstsAreBatchedAndListRefreshWaitsForForeground(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val delivered = CompletableDeferred<Set<String>>()
        val calls = AtomicInteger()
        withContext(Dispatchers.Main) {
            SecureBroadcastHelper.cancelPending()
            val receiver = SecureBroadcastHelper.createSecurePackageReceiver {
                calls.incrementAndGet()
                delivered.complete(it)
            }
            for (pkg in listOf("com.android.settings", "com.example.second")) {
                receiver.onReceive(context, Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:$pkg")))
            }
        }
        assertEquals(setOf("com.android.settings", "com.example.second"), withTimeout(3_000) { delivered.await() })
        assertEquals(1, calls.get())

        val store = ViewModelStore()
        try {
            val vm = withContext(Dispatchers.Main) {
                ViewModelProvider(store, LauncherViewModelFactory(AppRepository(context)))[LauncherViewModel::class.java]
                    .also { it.onLauncherVisibilityChanged(true) }
            }
            val before = withTimeout(15_000) { vm.uiState.first { !it.isLoading && it.installedApps.isNotEmpty() } }
            withContext(Dispatchers.Main) {
                vm.onLauncherVisibilityChanged(false)
                vm.onPackagesChanged(setOf("com.android.settings"))
            }
            delay(400)
            assertSame("A package event in background must not rebuild the list", before.installedApps, vm.uiState.value.installedApps)
            withContext(Dispatchers.Main) { vm.onLauncherVisibilityChanged(true) }
            withTimeout(15_000) { vm.uiState.first { !it.isLoading && it.installedApps !== before.installedApps } }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    private class ThreadTrackingDrawable : Drawable() {
        val draws = AtomicInteger()
        val drewOnMain = AtomicBoolean()
        override fun draw(canvas: Canvas) {
            if (Looper.myLooper() == Looper.getMainLooper()) drewOnMain.set(true)
            draws.incrementAndGet()
            canvas.drawColor(android.graphics.Color.CYAN)
        }
        override fun getIntrinsicWidth() = 96
        override fun getIntrinsicHeight() = 96
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    }
}
