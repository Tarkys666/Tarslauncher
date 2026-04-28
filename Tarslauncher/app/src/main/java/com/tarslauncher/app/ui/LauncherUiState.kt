package com.tarslauncher.app.ui

import com.tarslauncher.app.data.model.*

data class LauncherUiState(
    val installedApps: List<AppInfo> = emptyList(),
    val iconPacks: List<IconPackInfo> = emptyList(),
    val folders: List<FolderInfo> = emptyList(),
    val currentScreen: String = "home",
    val showAppDrawer: Boolean = true,
    val gridSize: String = "4x3",
    val showClock: Boolean = true,
    val selectedIconPack: String? = null,
    val buttonColorMode: String = "black",
    val showTarsHeader: Boolean = true,
    val appDrawerIconSize: Int = 32,
    val showOnboarding: Boolean = false
)
