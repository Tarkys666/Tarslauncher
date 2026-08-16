package com.tarslauncher.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.data.updater.UpdateInfo
import com.tarslauncher.app.data.updater.UpdateManager
import com.tarslauncher.app.data.updater.UpdateState
import com.tarslauncher.app.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LauncherViewModel(
    private val appRepository: AppRepository,
    private val updateManager: UpdateManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(LauncherUiState(isLoading = true))
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appRepository.initialize()
            // Observe settings flow reactively
            launch {
                appRepository.settingsFlow.collectLatest { settings ->
                    val oldGridSize = _uiState.value.settings.gridSize
                    _uiState.update { it.copy(settings = settings) }
                    // If grid dimensions changed, refresh folder slots
                    if (oldGridSize != settings.gridSize && _uiState.value.installedApps.isNotEmpty()) {
                        reloadFolders(settings.gridSize, _uiState.value.installedApps)
                    }
                }
            }
            refreshAppList()
        }
    }

    fun refreshAppList() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val appsResult = appRepository.getInstalledApps()
            val usageResult = appRepository.getUsageStats()
            val iconPacksResult = appRepository.getInstalledIconPacks()

            if (appsResult is RepositoryResult.Error) {
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        errorMessage = appsResult.message
                    ) 
                }
                return@launch
            }

            val baseApps = appsResult.getOrDefault(emptyList())
            val usageStats = usageResult.getOrDefault(emptyList())
            val usageMap = usageStats.associateBy { it.packageName }

            val installedApps = baseApps.map { app ->
                val stats = usageMap[app.packageName]
                AppInfo(
                    name = app.name,
                    packageName = app.packageName,
                    icon = app.icon,
                    activityName = app.activityName,
                    usageCount = stats?.usageCount ?: 0,
                    isFavorite = stats?.isFavorite ?: false
                )
            }.sortedWith(
                compareByDescending<AppInfo> { it.isFavorite }
                    .thenByDescending { it.usageCount }
                    .thenBy { it.name.lowercase() }
            )

            val currentGridSize = _uiState.value.settings.gridSize
            val foldersResult = appRepository.getFolders(currentGridSize.totalCells, installedApps)
            val folders = foldersResult.getOrDefault(emptyList())
            val iconPacks = iconPacksResult.getOrDefault(emptyList())

            _uiState.update {
                it.copy(
                    installedApps = installedApps,
                    iconPacks = iconPacks,
                    folders = folders,
                    isLoading = false,
                    errorMessage = null
                )
            }
        }
    }

    private fun reloadFolders(gridSize: GridSize, apps: List<AppInfo>) {
        viewModelScope.launch {
            val foldersResult = appRepository.getFolders(gridSize.totalCells, apps)
            val folders = foldersResult.getOrDefault(emptyList())
            _uiState.update { it.copy(folders = folders) }
        }
    }

    fun incrementAppUsage(app: AppInfo) {
        val updatedApps = _uiState.value.installedApps.map { existingApp ->
            if (existingApp.packageName == app.packageName) {
                existingApp.copy(usageCount = existingApp.usageCount + 1)
            } else {
                existingApp
            }
        }.sortedWith(
            compareByDescending<AppInfo> { it.isFavorite }
                .thenByDescending { it.usageCount }
                .thenBy { it.name.lowercase() }
        )

        _uiState.update { it.copy(installedApps = updatedApps) }

        viewModelScope.launch(Dispatchers.IO) {
            val stats = updatedApps.map {
                AppUsageStats(it.packageName, it.usageCount, it.isFavorite)
            }
            appRepository.saveUsageStats(stats)
        }
    }

    fun toggleFavorite(app: AppInfo) {
        val updatedApps = _uiState.value.installedApps.map { existingApp ->
            if (existingApp.packageName == app.packageName) {
                existingApp.copy(isFavorite = !existingApp.isFavorite)
            } else {
                existingApp
            }
        }.sortedWith(
            compareByDescending<AppInfo> { it.isFavorite }
                .thenByDescending { it.usageCount }
                .thenBy { it.name.lowercase() }
        )

        _uiState.update { it.copy(installedApps = updatedApps) }

        viewModelScope.launch(Dispatchers.IO) {
            val stats = updatedApps.map {
                AppUsageStats(it.packageName, it.usageCount, it.isFavorite)
            }
            appRepository.saveUsageStats(stats)
        }
    }

    fun updateFolders(newFolders: List<FolderInfo>) {
        _uiState.update { it.copy(folders = newFolders) }
        viewModelScope.launch(Dispatchers.IO) {
            appRepository.saveFolders(newFolders)
        }
    }

    fun setScreen(screen: String) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    // Type-safe setting updates
    fun updateShowAppDrawer(show: Boolean) {
        viewModelScope.launch { appRepository.updateShowAppDrawer(show) }
    }

    fun updateGridSize(gridSize: GridSize) {
        viewModelScope.launch { appRepository.updateGridSize(gridSize) }
    }

    fun updateButtonColorMode(mode: String) {
        viewModelScope.launch { appRepository.updateButtonColorMode(mode) }
    }

    fun updateShowTarsHeader(show: Boolean) {
        viewModelScope.launch { appRepository.updateShowTarsHeader(show) }
    }

    fun updateShowClock(show: Boolean) {
        viewModelScope.launch { appRepository.updateShowClock(show) }
    }

    fun updateSelectedIconPack(iconPackPackage: String?) {
        viewModelScope.launch { appRepository.updateSelectedIconPack(iconPackPackage) }
    }

    fun updateAppDrawerIconSize(size: Int) {
        viewModelScope.launch { appRepository.updateAppDrawerIconSize(size) }
    }

    fun setOnboardingComplete() {
        _uiState.update { it.copy(showOnboarding = false) }
    }

    fun setShowOnboarding(show: Boolean) {
        _uiState.update { it.copy(showOnboarding = show) }
    }

    // In-app Update actions
    fun checkForUpdates(currentVersion: String) {
        if (updateManager == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(updateState = UpdateState.Checking) }
            val result = updateManager.checkForUpdates(currentVersion)
            when (result) {
                is RepositoryResult.Success -> {
                    val info = result.data
                    if (info != null) {
                        _uiState.update { it.copy(updateState = UpdateState.UpdateAvailable(info)) }
                    } else {
                        _uiState.update { it.copy(updateState = UpdateState.NoUpdateAvailable) }
                    }
                }
                is RepositoryResult.Error -> {
                    _uiState.update { it.copy(updateState = UpdateState.Error(result.message)) }
                }
                else -> Unit
            }
        }
    }

    fun startUpdateDownload(info: UpdateInfo) {
        if (updateManager == null) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(updateState = UpdateState.Downloading(0f, 0L, info.apkSize, info))
            }
            val result = updateManager.downloadApk(info) { progress, downloaded, total ->
                _uiState.update { current ->
                    if (current.updateState is UpdateState.Downloading) {
                        current.copy(updateState = UpdateState.Downloading(progress, downloaded, total, info))
                    } else {
                        current
                    }
                }
            }
            when (result) {
                is RepositoryResult.Success -> {
                    _uiState.update { it.copy(updateState = UpdateState.ReadyToInstall(result.data, info)) }
                }
                is RepositoryResult.Error -> {
                    _uiState.update { it.copy(updateState = UpdateState.Error(result.message)) }
                }
                else -> Unit
            }
        }
    }

    fun installDownloadedApk(): Boolean {
        val state = _uiState.value.updateState
        if (state is UpdateState.ReadyToInstall && updateManager != null) {
            return updateManager.installApk(state.apkFile)
        }
        return false
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(updateState = UpdateState.Idle) }
    }
}

class LauncherViewModelFactory(
    private val appRepository: AppRepository,
    private val updateManager: UpdateManager? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LauncherViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LauncherViewModel(appRepository, updateManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
