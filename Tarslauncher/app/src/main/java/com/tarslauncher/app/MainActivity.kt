package com.tarslauncher.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.tarslauncher.app.ui.LauncherViewModel
import com.tarslauncher.app.ui.LauncherViewModelFactory
import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.onboarding.OnboardingManager
import com.tarslauncher.app.onboarding.OnboardingScreen
import com.tarslauncher.app.ui.screens.HomeScreen
import com.tarslauncher.app.ui.screens.SettingsScreen
import com.tarslauncher.app.ui.screens.LocationScreen
import com.tarslauncher.app.repository.LocationRepository
import com.tarslauncher.app.repository.ConnectivityRepository
import com.tarslauncher.app.ui.theme.TarslauncherTheme
import com.tarslauncher.app.utils.*

class MainActivity : ComponentActivity() {
    
    private lateinit var repository: AppRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var connectivityRepository: ConnectivityRepository
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
        
        repository = AppRepository(this)
        locationRepository = LocationRepository(this)
        connectivityRepository = ConnectivityRepository(this)
        val onboardingManager = OnboardingManager(this)
        
        viewModel = ViewModelProvider(
            this, 
            LauncherViewModelFactory(repository, locationRepository, connectivityRepository)
        )[LauncherViewModel::class.java]
        
        // Initial onboarding check
        if (!onboardingManager.isOnboardingComplete()) {
            viewModel.setShowOnboarding(true)
        }
        
        // Permission handling for location
        checkLocationPermissions()
        
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

    private fun checkLocationPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE
        )
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        
        val missingPermissions = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 1001)
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
            addDataScheme("package")
        }
        
        context.registerReceiver(receiver, filter)
        
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
        iconPackLoader?.load()
    }

    // Adaptive Volume Service Lifecycle
    LaunchedEffect(uiState.adaptiveVolumeEnabled) {
        val serviceIntent = Intent(context, com.tarslauncher.app.service.AdaptiveVolumeService::class.java)
        if (uiState.adaptiveVolumeEnabled) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            context.stopService(serviceIntent)
        }
    }
    
    val getButtonColor = remember(uiState.buttonColorMode) {
        when (uiState.buttonColorMode) {
            "black" -> Color.Black.copy(alpha = 0.7f)
            else -> Color.Gray.copy(alpha = 0.3f)
        }
    }
    
    val scaleFactor = if (uiState.batteryFriendly) 0.3f else 0.5f

    CompositionLocalProvider(LocalMotionDurationScale provides StaticMotionDurationScale(scaleFactor)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (uiState.currentScreen == "home") {
                    HomeScreen(
                        installedApps = uiState.installedApps,
                        folders = uiState.folders,
                        onFoldersUpdate = { viewModel.updateFolders(it) },
                        onSettingsClick = { viewModel.setScreen("settings") },
                        onAppClick = { appInfo ->
                            viewModel.incrementAppUsage(appInfo)
                            if (SecurityHelper.validatePackageName(appInfo.packageName)) {
                                val safeIntent = runCatching {
                                    context.packageManager.getLaunchIntentForPackage(appInfo.packageName)
                                }.getOrNull()
                                safeIntent?.let { intent ->
                                    if (!SecurityHelper.safeStartActivity(context, intent)) {
                                        Log.w("TarsLauncher", "Failed to start app safely: ${appInfo.packageName}")
                                    }
                                }
                            } else {
                                Log.w("TarsLauncher", "Invalid package name blocked: ${appInfo.packageName}")
                            }
                        },
                        showAppDrawer = uiState.showAppDrawer,
                        gridSize = uiState.gridSize,
                        iconPackLoader = iconPackLoader,
                        buttonColor = getButtonColor,
                        folderLocked = uiState.folderLocked,
                        showTarsHeader = uiState.showTarsHeader,
                        appDrawerIconSize = uiState.appDrawerIconSize,
                        showClock = uiState.showClock,
                        doubleTapLockEnabled = uiState.doubleTapLockEnabled,
                        isInternetAvailable = uiState.isInternetAvailable,
                        internetCheckEnabled = uiState.internetCheckEnabled
                    )
                } else if (uiState.currentScreen == "settings") {
                    SettingsScreen(
                        onBackClick = { viewModel.setScreen("home") },
                        onLocationTrackingClick = { viewModel.setScreen("location") },
                        showAppDrawer = uiState.showAppDrawer,
                        batteryFriendly = uiState.batteryFriendly,
                        onShowAppDrawerChange = {
                            viewModel.updateSetting("show_app_drawer", it)
                        },
                        onBatteryFriendlyChange = {
                            viewModel.updateSetting("battery_friendly", it)
                        },
                        gridSize = uiState.gridSize,
                        onGridSizeChange = {
                            viewModel.updateSetting("grid_size", it)
                        },
                        installedIconPacks = uiState.iconPacks,
                        selectedIconPack = uiState.selectedIconPack,
                        onIconPackSelected = {
                            viewModel.updateSetting("icon_pack", it ?: "")
                        },
                        buttonColorMode = uiState.buttonColorMode,
                        onButtonColorModeChange = {
                            viewModel.updateSetting("button_color_mode", it)
                        },
                        folderLocked = uiState.folderLocked,
                        onFolderLockedChange = {
                            viewModel.updateSetting("folder_locked", it)
                        },
                        doubleTapLockEnabled = uiState.doubleTapLockEnabled,
                        onDoubleTapLockEnabledChange = {
                            viewModel.updateSetting("double_tap_lock", it)
                        },
                        showTarsHeader = uiState.showTarsHeader,
                        onShowTarsHeaderChange = {
                            viewModel.updateSetting("show_tars_header", it)
                        },
                        showClock = uiState.showClock,
                        onShowClockChange = {
                            viewModel.updateSetting("show_clock", it)
                        },
                        appDrawerIconSize = uiState.appDrawerIconSize,
                        onAppDrawerIconSizeChange = {
                            viewModel.updateSetting("app_drawer_icon_size", it)
                        },
                        internetCheckEnabled = uiState.internetCheckEnabled,
                        onInternetCheckEnabledChange = {
                            viewModel.updateSetting("internet_check_enabled", it)
                        },
                        onMuteAll = {
                            viewModel.muteAllSounds(context)
                        },
                        onRestoreSounds = {
                            viewModel.restoreAllSounds(context)
                        },
                        adaptiveVolumeEnabled = uiState.adaptiveVolumeEnabled,
                        onAdaptiveVolumeEnabledChange = {
                            viewModel.updateSetting("adaptive_enabled", it)
                        },
                        adaptiveVolumeSensitivity = uiState.adaptiveVolumeSensitivity,
                        onAdaptiveVolumeSensitivityChange = {
                            viewModel.updateSetting("adaptive_sensitivity", it)
                        },
                        adaptiveVolumeApps = uiState.adaptiveVolumeApps,
                        onAdaptiveAppChecked = { pkg, checked ->
                            viewModel.updateAdaptiveApps(pkg, checked)
                        },
                        installedApps = uiState.installedApps
                    )
                } else if (uiState.currentScreen == "location") {
                    LocationScreen(
                        locationState = uiState.tarsLocation,
                        onBackClick = { viewModel.setScreen("settings") },
                        onStartTracking = { viewModel.startTracking() },
                        onStopTracking = { viewModel.stopTracking() }
                    )
                }
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
        }
    }
}
