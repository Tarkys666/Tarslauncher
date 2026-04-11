package com.tarslauncher.app.ui

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.repository.AppRepository
import com.tarslauncher.app.repository.LocationRepository
import com.tarslauncher.app.repository.ConnectivityRepository
import com.tarslauncher.app.repository.TarsLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LauncherViewModel(
    private val appRepository: AppRepository,
    private val locationRepository: LocationRepository,
    private val connectivityRepository: ConnectivityRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private var trackingJob: Job? = null
    private var cellTrackingJob: Job? = null
    private var wifiTrackingJob: Job? = null
    private var connectivityJob: Job? = null

    init {
        loadSettings()
        startConnectivityCheck()
        refreshAppList()
    }

    private fun startConnectivityCheck() {
        connectivityJob?.cancel()
        if (_uiState.value.internetCheckEnabled) {
            connectivityJob = viewModelScope.launch {
                connectivityRepository.getInternetStatusFlow().collectLatest { isAvailable: Boolean ->
                    _uiState.update { it.copy(isInternetAvailable = isAvailable) }
                }
            }
        } else {
            _uiState.update { it.copy(isInternetAvailable = null) }
        }
    }

    private fun loadSettings() {
        _uiState.update { state ->
            state.copy(
                showAppDrawer = appRepository.getBooleanSetting("show_app_drawer", true),
                gridSize = appRepository.getStringSetting("grid_size", "4x3") ?: "4x3",
                buttonColorMode = appRepository.getStringSetting("button_color_mode", "black") ?: "black",
                folderLocked = appRepository.getBooleanSetting("folder_locked", false),
                showTarsHeader = appRepository.getBooleanSetting("show_tars_header", true),
                showClock = appRepository.getBooleanSetting("show_clock", true),
                doubleTapLockEnabled = appRepository.getBooleanSetting("double_tap_lock", true),
                batteryFriendly = appRepository.getBooleanSetting("battery_friendly", false),
                selectedIconPack = appRepository.getStringSetting("icon_pack", null),
                appDrawerIconSize = appRepository.getIntSetting("app_drawer_icon_size", 32),
                internetCheckEnabled = appRepository.getBooleanSetting("internet_check_enabled", false),
                adaptiveVolumeEnabled = appRepository.getBooleanSetting("adaptive_enabled", false),
                adaptiveVolumeSensitivity = appRepository.getFloatSetting("adaptive_sensitivity", 0.5f),
                adaptiveVolumeApps = appRepository.getAdaptiveApps()
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
        appRepository.saveUsageStats(stats)
    }

    fun updateFolders(newFolders: List<FolderInfo>) {
        _uiState.update { it.copy(folders = newFolders) }
        appRepository.saveFolders(newFolders)
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
                    "battery_friendly" -> _uiState.update { it.copy(batteryFriendly = value) }
                    "folder_locked" -> _uiState.update { it.copy(folderLocked = value) }
                    "show_tars_header" -> _uiState.update { it.copy(showTarsHeader = value) }
                    "show_clock" -> _uiState.update { it.copy(showClock = value) }
                    "double_tap_lock" -> _uiState.update { it.copy(doubleTapLockEnabled = value) }
                    "internet_check_enabled" -> {
                        _uiState.update { it.copy(internetCheckEnabled = value) }
                        startConnectivityCheck()
                    }
                    "adaptive_enabled" -> _uiState.update { it.copy(adaptiveVolumeEnabled = value) }
                }
            }
            is Float -> {
                appRepository.saveFloatSetting(key, value)
                when (key) {
                    "adaptive_sensitivity" -> _uiState.update { it.copy(adaptiveVolumeSensitivity = value) }
                }
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

    fun muteAllSounds(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Check if we have DND access
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !notificationManager.isNotificationPolicyAccessGranted) {
            val intent = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }

        // Save current volumes
        val ring = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val music = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val notif = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val system = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
        
        appRepository.saveVolumes(ring, music, notif, system)

        // Set to silent
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        
        // Also manually mute music just in case SILENT doesn't cover all apps
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    }

    fun restoreAllSounds(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val saved = appRepository.getSavedVolumes()
        
        val ring = saved["ring"] ?: -1
        val music = saved["music"] ?: -1
        val notif = saved["notif"] ?: -1
        val system = saved["system"] ?: -1

        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        
        if (ring != -1) audioManager.setStreamVolume(AudioManager.STREAM_RING, ring, 0)
        if (music != -1) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, music, 0)
        if (notif != -1) audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, notif, 0)
        if (system != -1) audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, system, 0)
    }

    fun updateAdaptiveApps(packageName: String, isChecked: Boolean) {
        val currentApps = _uiState.value.adaptiveVolumeApps.toMutableSet()
        if (isChecked) currentApps.add(packageName) else currentApps.remove(packageName)
        
        _uiState.update { it.copy(adaptiveVolumeApps = currentApps) }
        appRepository.saveAdaptiveApps(currentApps)
    }

    override fun onCleared() {
        super.onCleared()
        stopTracking()
    }
}

class LauncherViewModelFactory(
    private val appRepository: AppRepository,
    private val locationRepository: LocationRepository,
    private val connectivityRepository: ConnectivityRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LauncherViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LauncherViewModel(appRepository, locationRepository, connectivityRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
