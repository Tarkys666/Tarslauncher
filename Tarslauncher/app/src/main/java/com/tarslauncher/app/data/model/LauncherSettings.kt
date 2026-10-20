package com.tarslauncher.app.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class LauncherSettings(
    val showAppDrawer: Boolean = true,
    val dualAppDrawer: Boolean = false,
    val gridSize: GridSize = GridSize.DEFAULT,
    val buttonColorMode: String = "transparent",
    val showTarsHeader: Boolean = true,
    val showClock: Boolean = true,
    val selectedIconPack: String? = null,
    val appDrawerIconSize: Int = 32,
    val showAsciiDiamond: Boolean = false,
    val asciiDiamondScale: Int = 100,
    val asciiDiamondOffsetY: Int = 0,
    val asciiDiamondFps: Int = 60,
    val asciiDiamondSpeed: Int = 100,
    val showFolderGrid: Boolean = true,
    val appDrawerOffsetY: Int = 24
)
