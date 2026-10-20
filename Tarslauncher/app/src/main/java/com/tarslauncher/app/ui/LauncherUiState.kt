package com.tarslauncher.app.ui

import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.data.sync.TarsAuthUser
import com.tarslauncher.app.data.updater.UpdateState
import androidx.compose.runtime.Immutable

@Immutable
data class LauncherUiState(
    val installedApps: List<AppInfo> = emptyList(),
    val iconPacks: List<IconPackInfo> = emptyList(),
    val folders: List<FolderInfo> = emptyList(),
    val settings: LauncherSettings = LauncherSettings(),
    val currentScreen: String = "home",
    val showOnboarding: Boolean = false,
    val installedPwas: List<PwaInfo> = emptyList(),
    val pwaPresets: List<PwaInfo> = emptyList(),
    val isLoading: Boolean = false,
    val updateState: UpdateState = UpdateState.Idle,
    val errorMessage: String? = null,
    val authUser: TarsAuthUser? = null,
    val isSyncing: Boolean = false,
    val syncStatusMessage: String? = null,
    val lastBackupTime: Long? = null
) {
    // Convenience properties mapping to type-safe settings
    val showAppDrawer: Boolean get() = settings.showAppDrawer
    val dualAppDrawer: Boolean get() = settings.dualAppDrawer
    val appDrawerRows: Int get() = settings.appDrawerRows
    val gridSize: GridSize get() = settings.gridSize
    val buttonColorMode: String get() = settings.buttonColorMode
    val showTarsHeader: Boolean get() = settings.showTarsHeader
    val showClock: Boolean get() = settings.showClock
    val selectedIconPack: String? get() = settings.selectedIconPack
    val appDrawerIconSize: Int get() = settings.appDrawerIconSize
    val showAsciiDiamond: Boolean get() = settings.showAsciiDiamond
    val asciiDiamondScale: Int get() = settings.asciiDiamondScale
    val asciiDiamondOffsetY: Int get() = settings.asciiDiamondOffsetY
    val asciiDiamondFps: Int get() = settings.asciiDiamondFps
    val asciiDiamondSpeed: Int get() = settings.asciiDiamondSpeed
    val showFolderGrid: Boolean get() = settings.showFolderGrid
    val appDrawerOffsetY: Int get() = settings.appDrawerOffsetY
}
