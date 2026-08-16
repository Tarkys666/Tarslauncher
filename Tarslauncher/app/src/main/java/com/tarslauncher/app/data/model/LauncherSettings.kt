package com.tarslauncher.app.data.model

data class LauncherSettings(
    val showAppDrawer: Boolean = true,
    val gridSize: GridSize = GridSize.DEFAULT,
    val buttonColorMode: String = "black",
    val showTarsHeader: Boolean = true,
    val showClock: Boolean = true,
    val selectedIconPack: String? = null,
    val appDrawerIconSize: Int = 32
)
