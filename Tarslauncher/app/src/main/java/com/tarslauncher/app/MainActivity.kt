package com.tarslauncher.app

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

import androidx.lifecycle.ViewModelProvider
import com.tarslauncher.app.repository.AppRepository
import com.tarslauncher.app.ui.LauncherUiState
import com.tarslauncher.app.ui.LauncherViewModel
import com.tarslauncher.app.ui.LauncherViewModelFactory
import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.onboarding.OnboardingManager
import com.tarslauncher.app.onboarding.OnboardingScreen
import com.tarslauncher.app.ui.screens.HomeScreen
import com.tarslauncher.app.ui.screens.SettingsScreen
import com.tarslauncher.app.ui.screens.UpdateDialog
import com.tarslauncher.app.ui.theme.TarslauncherTheme
import com.tarslauncher.app.utils.*

class MainActivity : ComponentActivity() {
    
    private lateinit var repository: AppRepository
    private lateinit var viewModel: LauncherViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Window'u Şeffaf yap ki sistem wallpaper'i görünsün
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        
        repository = AppRepository(applicationContext)
        val onboardingManager = OnboardingManager(this)
        
        viewModel = ViewModelProvider(
            this, 
            LauncherViewModelFactory(repository)
        )[LauncherViewModel::class.java]
        
        // Initial onboarding check
        if (!onboardingManager.isOnboardingComplete()) {
            viewModel.setShowOnboarding(true)
        }
        
        setContent {
            TarslauncherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    LauncherMain(viewModel = viewModel, onboardingManager = onboardingManager)
                }
            }
        }
    }
}

@Composable
fun LauncherMain(viewModel: LauncherViewModel, onboardingManager: OnboardingManager) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    
    // Güvenli broadcast receiver ile uygulama değişikliklerini dinle
    DisposableEffect(context) {
        val receiver = SecureBroadcastHelper.createSecurePackageReceiver {
            viewModel.refreshAppList()
        }
        
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addAction(android.content.Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w("TarsLauncher", "Failed to unregister receiver safely")
            }
        }
    }
    
    val iconPackLoader = remember(uiState.selectedIconPack) {
        uiState.selectedIconPack?.let { IconPackLoader(context, it) }
    }

    LaunchedEffect(iconPackLoader) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            iconPackLoader?.load()
        }
    }
    
    val getButtonColor = remember(uiState.buttonColorMode) {
        when (uiState.buttonColorMode) {
            "black" -> Color.Black.copy(alpha = 0.7f)
            else -> Color.Gray.copy(alpha = 0.3f)
        }
    }
    
    val motionScale = remember { StaticMotionDurationScale(0.5f) }

    val onFoldersUpdate = remember(viewModel) { { f: List<FolderInfo> -> viewModel.updateFolders(f) } }
    val onSettingsClick = remember(viewModel) { { viewModel.setScreen("settings") } }
    val onBackClick = remember(viewModel) { { viewModel.setScreen("home") } }
    val onAppClick = remember(context, viewModel) {
        { appInfo: AppInfo ->
            viewModel.incrementAppUsage(appInfo)
            val started = PwaLauncherHelper.launchApp(context, appInfo)
            if (!started) {
                Toast.makeText(context, "Uygulama başlatılamadı", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val onAddPwa = remember(viewModel) { { name: String, url: String -> viewModel.addPwa(name, url) } }
    val onDeletePwa = remember(viewModel) { { pwaId: String -> viewModel.deletePwa(pwaId) } }

    CompositionLocalProvider(LocalMotionDurationScale provides motionScale) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            if (uiState.currentScreen == "home") {
                HomeScreen(
                    installedApps = uiState.installedApps,
                    folders = uiState.folders,
                    onFoldersUpdate = onFoldersUpdate,
                    onSettingsClick = onSettingsClick,
                    onAppClick = onAppClick,
                    showAppDrawer = uiState.showAppDrawer,
                    gridSize = uiState.gridSize,
                    iconPackLoader = iconPackLoader,
                    buttonColor = getButtonColor,
                    showTarsHeader = uiState.showTarsHeader,
                    appDrawerIconSize = uiState.appDrawerIconSize,
                    showClock = uiState.showClock,
                    pwaPresets = uiState.pwaPresets,
                    onAddPwa = onAddPwa,
                    onDeletePwa = onDeletePwa
                )
            } else if (uiState.currentScreen == "settings") {
                SettingsScreen(
                    onBackClick = onBackClick,
                    showAppDrawer = uiState.showAppDrawer,
                    onShowAppDrawerChange = { viewModel.updateShowAppDrawer(it) },
                    gridSize = uiState.gridSize,
                    onGridSizeChange = { viewModel.updateGridSize(it) },
                    installedIconPacks = uiState.iconPacks,
                    selectedIconPack = uiState.selectedIconPack,
                    onIconPackSelected = { viewModel.updateSelectedIconPack(it) },
                    buttonColorMode = uiState.buttonColorMode,
                    onButtonColorModeChange = { viewModel.updateButtonColorMode(it) },
                    showTarsHeader = uiState.showTarsHeader,
                    onShowTarsHeaderChange = { viewModel.updateShowTarsHeader(it) },
                    showClock = uiState.showClock,
                    onShowClockChange = { viewModel.updateShowClock(it) },
                    appDrawerIconSize = uiState.appDrawerIconSize,
                    onAppDrawerIconSizeChange = { viewModel.updateAppDrawerIconSize(it) },
                    appVersion = BuildConfig.VERSION_NAME,
                    installedPwas = uiState.installedPwas,
                    pwaPresets = uiState.pwaPresets,
                    onAddPwa = onAddPwa,
                    onDeletePwa = onDeletePwa,
                    isSyncing = uiState.isSyncing,
                    lastBackupTime = uiState.lastBackupTime,
                    onBackupToCloud = {
                        viewModel.backupToCloud { _, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRestoreFromCloud = {
                        viewModel.restoreFromCloud { _, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCheckForUpdates = {
                        viewModel.checkForUpdates(BuildConfig.VERSION_NAME)
                    }
                )
            }

            // Onboarding Overlay
            if (uiState.showOnboarding) {
                OnboardingScreen(
                    onOnboardingComplete = {
                        onboardingManager.setOnboardingComplete()
                        viewModel.setOnboardingComplete()
                    }
                )
            }

            // In-App System Update Dialog
            UpdateDialog(
                updateState = uiState.updateState,
                currentVersion = BuildConfig.VERSION_NAME,
                onDismiss = { viewModel.dismissUpdateDialog() },
                onStartDownload = { info -> viewModel.startUpdateDownload(info) },
                onInstall = { file ->
                    if (!viewModel.updateManager.canInstallPackages()) {
                        Toast.makeText(context, "Lütfen harici kaynaklardan uygulama yükleme iznini açın.", Toast.LENGTH_LONG).show()
                        viewModel.updateManager.openInstallPermissionSettings()
                    } else {
                        val result = viewModel.updateManager.installApk(file)
                        if (result.isFailure) {
                            Toast.makeText(context, "Yükleme başlatılamadı: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onRetry = { viewModel.checkForUpdates(BuildConfig.VERSION_NAME) }
            )
        }
    }
}
