package com.tarslauncher.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.repository.AppRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LauncherViewModel(
    private val appRepository: AppRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        refreshAppList()
    }

    private fun loadSettings() {
        _uiState.update { state ->
            state.copy(
                showAppDrawer = appRepository.getBooleanSetting("show_app_drawer", true),
                gridSize = appRepository.getStringSetting("grid_size", "4x3") ?: "4x3",
                buttonColorMode = appRepository.getStringSetting("button_color_mode", "black") ?: "black",
                showTarsHeader = appRepository.getBooleanSetting("show_tars_header", true),
                showClock = appRepository.getBooleanSetting("show_clock", true),
                selectedIconPack = appRepository.getStringSetting("icon_pack", null),
                appDrawerIconSize = appRepository.getIntSetting("app_drawer_icon_size", 32)
            )
        }
    }

    fun refreshAppList() {
        viewModelScope.launch {
            val baseApps = appRepository.getInstalledApps()
            val usageStats = appRepository.getUsageStats()
            
            val installedApps = baseApps.map { app ->
                val stats = usageStats.find { it.packageName == app.packageName }
                AppInfo(
                    name = app.name,
                    packageName = app.packageName,
                    icon = app.icon,
                    activityName = app.activityName,
                    usageCount = stats?.usageCount ?: 0,
                    isFavorite = stats?.isFavorite ?: false
                )
            }.sortedWith(compareByDescending<AppInfo> { it.isFavorite }
                .thenByDescending { it.usageCount }
                .thenBy { it.name })

            val iconPacks = appRepository.getInstalledIconPacks()
            val gridSize = _uiState.value.gridSize
            val totalFolders = try {
                val (cols, rows) = gridSize.split("x").map { it.toInt() }
                cols * rows
            } catch (e: Exception) {
                12
            }
            
            val folders = appRepository.getFolders(totalFolders, installedApps)

            _uiState.update { 
                it.copy(
                    installedApps = installedApps,
                    iconPacks = iconPacks,
                    folders = folders
                )
            }
        }
    }

    fun incrementAppUsage(app: AppInfo) {
        val updatedApps = _uiState.value.installedApps.map { existingApp ->
            if (existingApp.packageName == app.packageName) {
                val newCount = existingApp.usageCount + 1
                val isFavorite = existingApp.isFavorite || newCount >= 10
                AppInfo(
                    name = existingApp.name,
                    packageName = existingApp.packageName,
                    icon = existingApp.icon,
                    activityName = existingApp.activityName,
                    usageCount = newCount,
                    isFavorite = isFavorite
                )
            } else {
                existingApp
            }
        }.sortedWith(compareByDescending<AppInfo> { it.isFavorite }
            .thenByDescending { it.usageCount }
            .thenBy { it.name })

        _uiState.update { it.copy(installedApps = updatedApps) }
        
        // Persist on background thread
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val stats = updatedApps.map { 
                AppUsageStats(it.packageName, it.usageCount, it.isFavorite) 
            }
            appRepository.saveUsageStats(stats)
        }
    }

    fun updateFolders(newFolders: List<FolderInfo>) {
        _uiState.update { it.copy(folders = newFolders) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            appRepository.saveFolders(newFolders)
        }
    }

    fun setScreen(screen: String) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun updateSetting(key: String, value: Any) {
        when (value) {
            is Boolean -> {
                appRepository.saveBooleanSetting(key, value)
                when (key) {
                    "show_app_drawer" -> _uiState.update { it.copy(showAppDrawer = value) }
                    "show_tars_header" -> _uiState.update { it.copy(showTarsHeader = value) }
                    "show_clock" -> _uiState.update { it.copy(showClock = value) }
                }
            }
            is Float -> {
                appRepository.saveFloatSetting(key, value)
            }
            is String -> {
                appRepository.saveStringSetting(key, value)
                when (key) {
                    "grid_size" -> {
                        _uiState.update { it.copy(gridSize = value) }
                        refreshAppList() // Total folders might change
                    }
                    "icon_pack" -> _uiState.update { it.copy(selectedIconPack = value) }
                    "button_color_mode" -> _uiState.update { it.copy(buttonColorMode = value) }
                }
            }
            is Int -> {
                appRepository.saveIntSetting(key, value)
                if (key == "app_drawer_icon_size") {
                    _uiState.update { it.copy(appDrawerIconSize = value) }
                }
            }
        }
    }

    fun setOnboardingComplete() {
        _uiState.update { it.copy(showOnboarding = false) }
    }
    
    fun setShowOnboarding(show: Boolean) {
        _uiState.update { it.copy(showOnboarding = show) }
    }

    override fun onCleared() {
        super.onCleared()
    }
}

class LauncherViewModelFactory(
    private val appRepository: AppRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LauncherViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LauncherViewModel(appRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
