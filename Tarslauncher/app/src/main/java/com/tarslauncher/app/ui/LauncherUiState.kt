package com.tarslauncher.app.ui

import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.repository.TarsLocation

data class LauncherUiState(
    val installedApps: List<AppInfo> = emptyList(),
    val iconPacks: List<IconPackInfo> = emptyList(),
    val folders: List<FolderInfo> = emptyList(),
    val currentScreen: String = "home",
    val showAppDrawer: Boolean = true,
    val gridSize: String = "4x3",
    val showClock: Boolean = true,
    val doubleTapLockEnabled: Boolean = true,
    val batteryFriendly: Boolean = false,
    val selectedIconPack: String? = null,
    val buttonColorMode: String = "black",
    val folderLocked: Boolean = false,
    val showTarsHeader: Boolean = true,
    val appDrawerIconSize: Int = 32,
    val showOnboarding: Boolean = false,
    val tarsLocation: TarsLocation = TarsLocation(),
    val isInternetAvailable: Boolean? = null,
    val internetCheckEnabled: Boolean = true,
    
    // Adaptive Volume
    val adaptiveVolumeEnabled: Boolean = false,
    val adaptiveVolumeSensitivity: Float = 0.5f,
    val adaptiveVolumeApps: Set<String> = emptySet()
)
