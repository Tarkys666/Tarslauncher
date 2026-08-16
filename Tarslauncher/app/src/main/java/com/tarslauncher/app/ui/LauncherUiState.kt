package com.tarslauncher.app.ui

import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.data.updater.UpdateState

data class LauncherUiState(
    val installedApps: List<AppInfo> = emptyList(),
    val iconPacks: List<IconPackInfo> = emptyList(),
    val folders: List<FolderInfo> = emptyList(),
    val settings: LauncherSettings = LauncherSettings(),
    val currentScreen: String = "home",
    val showOnboarding: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val updateState: UpdateState = UpdateState.Idle
) {
    // Convenience properties mapping to type-safe settings
    val showAppDrawer: Boolean get() = settings.showAppDrawer
    val gridSize: GridSize get() = settings.gridSize
    val buttonColorMode: String get() = settings.buttonColorMode
    val showTarsHeader: Boolean get() = settings.showTarsHeader
    val showClock: Boolean get() = settings.showClock
    val selectedIconPack: String? get() = settings.selectedIconPack
    val appDrawerIconSize: Int get() = settings.appDrawerIconSize
}
