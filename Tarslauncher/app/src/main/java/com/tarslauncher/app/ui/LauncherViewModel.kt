package com.tarslauncher.app.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.repository.AppRepository
import com.tarslauncher.app.repository.LocationRepository
import com.tarslauncher.app.repository.TarsLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LauncherViewModel(
    private val repository: AppRepository,
    private val locationRepository: LocationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private var trackingJob: Job? = null
    private var cellTrackingJob: Job? = null
    private var wifiTrackingJob: Job? = null

    init {
        loadSettings()
        refreshAppList()
    }

    private fun loadSettings() {
// ... (omitted for brevity, will be kept in full write)
        _uiState.update { state ->
            state.copy(
                showAppDrawer = repository.getBooleanSetting("show_app_drawer", true),
                gridSize = repository.getStringSetting("grid_size", "4x3") ?: "4x3",
                buttonColorMode = repository.getStringSetting("button_color_mode", "black") ?: "black",
                folderLocked = repository.getBooleanSetting("folder_locked", false),
                showTarsHeader = repository.getBooleanSetting("show_tars_header", true),
                showClock = repository.getBooleanSetting("show_clock", true),
                doubleTapLockEnabled = repository.getBooleanSetting("double_tap_lock", true),
                batteryFriendly = repository.getBooleanSetting("battery_friendly", false),
                selectedIconPack = repository.getStringSetting("icon_pack", null),
                appDrawerIconSize = repository.getIntSetting("app_drawer_icon_size", 32)
            )
        }
    }

    fun refreshAppList() {
        viewModelScope.launch {
            val baseApps = repository.getInstalledApps()
            val usageStats = repository.getUsageStats()
            
            val installedApps = baseApps.map { app ->
                val stats = usageStats.find { it.packageName == app.packageName }
                AppInfo(
                    name = app.name,
                    packageName = app.packageName,
                    icon = app.icon,
                    usageCount = stats?.usageCount ?: 0,
                    isFavorite = stats?.isFavorite ?: false
                )
            }.sortedWith(compareByDescending<AppInfo> { it.isFavorite }
                .thenByDescending { it.usageCount }
                .thenBy { it.name })

            val iconPacks = repository.getInstalledIconPacks()
            val gridSize = _uiState.value.gridSize
            val totalFolders = try {
                val (cols, rows) = gridSize.split("x").map { it.toInt() }
                cols * rows
            } catch (e: Exception) {
                12
            }
            
            val folders = repository.getFolders(totalFolders, installedApps)

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
        
        // Persist
        val stats = updatedApps.map { 
            AppUsageStats(it.packageName, it.usageCount, it.isFavorite) 
        }
        repository.saveUsageStats(stats)
    }

    fun updateFolders(newFolders: List<FolderInfo>) {
        _uiState.update { it.copy(folders = newFolders) }
        repository.saveFolders(newFolders)
    }

    fun setScreen(screen: String) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun updateSetting(key: String, value: Any) {
        when (value) {
            is Boolean -> {
                repository.saveBooleanSetting(key, value)
                when (key) {
                    "show_app_drawer" -> _uiState.update { it.copy(showAppDrawer = value) }
                    "battery_friendly" -> _uiState.update { it.copy(batteryFriendly = value) }
                    "folder_locked" -> _uiState.update { it.copy(folderLocked = value) }
                    "show_tars_header" -> _uiState.update { it.copy(showTarsHeader = value) }
                    "show_clock" -> _uiState.update { it.copy(showClock = value) }
                    "double_tap_lock" -> _uiState.update { it.copy(doubleTapLockEnabled = value) }
                }
            }
            is String -> {
                repository.saveStringSetting(key, value)
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
                repository.saveIntSetting(key, value)
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

    fun startTracking() {
        if (trackingJob != null) return
        
        trackingJob = viewModelScope.launch {
            locationRepository.getLocationFlow().collectLatest { location ->
                _uiState.update { state ->
                    state.copy(
                        tarsLocation = state.tarsLocation.copy(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy
                        )
                    )
                }
            }
        }

        cellTrackingJob = viewModelScope.launch {
            locationRepository.getCellTowerFlow().collectLatest { cellData ->
                _uiState.update { state ->
                    state.copy(
                        tarsLocation = state.tarsLocation.copy(
                            cellData = cellData
                        )
                    )
                }
            }
        }

        wifiTrackingJob = viewModelScope.launch {
            locationRepository.getWifiCountFlow().collectLatest { count ->
                _uiState.update { state ->
                    state.copy(
                        tarsLocation = state.tarsLocation.copy(
                            wifiCount = count
                        )
                    )
                }
            }
        }
    }

    fun stopTracking() {
        trackingJob?.cancel()
        cellTrackingJob?.cancel()
        wifiTrackingJob?.cancel()
        trackingJob = null
        cellTrackingJob = null
        wifiTrackingJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopTracking()
    }
}

class LauncherViewModelFactory(
    private val repository: AppRepository,
    private val locationRepository: LocationRepository
) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LauncherViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LauncherViewModel(repository, locationRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
