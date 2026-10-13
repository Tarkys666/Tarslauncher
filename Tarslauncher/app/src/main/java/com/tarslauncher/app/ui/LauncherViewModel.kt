package com.tarslauncher.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.repository.AppRepository
import com.tarslauncher.app.utils.PwaFetcherHelper
import com.tarslauncher.app.utils.PwaLauncherHelper
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LauncherViewModel(
    private val appRepository: AppRepository
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
            val lastBackup = appRepository.getLastBackupTime()
            _uiState.update { it.copy(lastBackupTime = lastBackup) }
        }
    }

    companion object {
        private val APP_COMPARATOR = compareByDescending<AppInfo> { it.isFavorite }
            .thenByDescending { it.usageCount }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }

    fun refreshAppList() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            coroutineScope {
                val appsDeferred = async { appRepository.getInstalledApps() }
                val usageDeferred = async { appRepository.getUsageStats() }
                val iconPacksDeferred = async { appRepository.getInstalledIconPacks() }
                val pwasDeferred = async { appRepository.getInstalledPwas() }

                val appsResult = appsDeferred.await()
                if (appsResult is RepositoryResult.Error) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = appsResult.message
                        )
                    }
                    return@coroutineScope
                }

                val baseApps = appsResult.getOrDefault(emptyList())
                val usageStats = usageDeferred.await().getOrDefault(emptyList())
                val usageMap = usageStats.associateBy { it.packageName }

                val installedApps = baseApps.map { app ->
                    val stats = usageMap[app.packageName]
                    app.copy(
                        usageCount = stats?.usageCount ?: 0,
                        isFavorite = stats?.isFavorite ?: false
                    )
                }.sortedWith(APP_COMPARATOR)

                val currentGridSize = _uiState.value.settings.gridSize
                val foldersResult = appRepository.getFolders(currentGridSize.totalCells, installedApps)
                val folders = foldersResult.getOrDefault(emptyList())
                val iconPacks = iconPacksDeferred.await().getOrDefault(emptyList())
                val installedPwas = pwasDeferred.await().getOrDefault(emptyList())
                val pwaPresets = appRepository.getPopularPwaPresets()

                _uiState.update {
                    it.copy(
                        installedApps = installedApps,
                        iconPacks = iconPacks,
                        folders = folders,
                        installedPwas = installedPwas,
                        pwaPresets = pwaPresets,
                        isLoading = false,
                        errorMessage = null
                    )
                }
            }
        }
    }

    fun addPwa(name: String, url: String, iconBase64: String? = null) {
        viewModelScope.launch {
            val sanitized = PwaLauncherHelper.sanitizeWebUrl(url) ?: return@launch
            val title = if (name.isNotBlank()) name.trim() else PwaFetcherHelper.extractDefaultTitle(sanitized)
            val fetchedIcon = iconBase64 ?: PwaFetcherHelper.fetchIconBase64(sanitized)

            val pwa = PwaInfo(
                id = UUID.randomUUID().toString().take(8),
                name = title,
                url = sanitized,
                iconBase64 = fetchedIcon
            )
            appRepository.savePwa(pwa)
            refreshAppList()
        }
    }

    fun deletePwa(pwaId: String) {
        viewModelScope.launch {
            appRepository.deletePwa(pwaId)
            val pwaPackageName = "pwa_$pwaId"
            val updatedFolders = _uiState.value.folders.map { folder ->
                folder.copy(apps = folder.apps.filter { it.packageName != pwaPackageName })
            }
            _uiState.update { state ->
                state.copy(
                    installedApps = state.installedApps.filter { it.packageName != pwaPackageName },
                    installedPwas = state.installedPwas.filter { it.id != pwaId },
                    folders = updatedFolders
                )
            }
            appRepository.saveFolders(updatedFolders)
        }
    }

    fun addRedGifsPwa() {
        addPwa(
            name = "RedGIFs",
            url = "https://www.redgifs.com",
            iconBase64 = AppRepository.REDGIFS_ICON_BASE64
        )
    }

    private fun reloadFolders(gridSize: GridSize, apps: List<AppInfo>) {
        viewModelScope.launch {
            val foldersResult = appRepository.getFolders(gridSize.totalCells, apps)
            val folders = foldersResult.getOrDefault(emptyList())
            _uiState.update { it.copy(folders = folders) }
        }
    }

    fun incrementAppUsage(app: AppInfo) {
        val currentList = _uiState.value.installedApps
        val index = currentList.indexOfFirst { it.packageName == app.packageName }
        if (index == -1) return

        val updatedApp = currentList[index].copy(usageCount = currentList[index].usageCount + 1)
        val updatedList = currentList.toMutableList().apply { set(index, updatedApp) }
            .sortedWith(APP_COMPARATOR)

        _uiState.update { it.copy(installedApps = updatedList) }

        // Persist only active items to eliminate disk write amplification
        viewModelScope.launch(Dispatchers.IO) {
            val stats = updatedList
                .filter { it.usageCount > 0 || it.isFavorite }
                .map { AppUsageStats(it.packageName, it.usageCount, it.isFavorite) }
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

    fun backupToCloud(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val currentSettings = _uiState.value.settings
            val result = appRepository.cloudSyncManager.backupLayout(currentSettings)
            if (result.isSuccess) {
                val time = result.getOrNull() ?: System.currentTimeMillis()
                _uiState.update { it.copy(isSyncing = false, lastBackupTime = time) }
                onResult(true, "Buluta yedekleme başarılı!")
            } else {
                _uiState.update { it.copy(isSyncing = false) }
                val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Yedekleme başarısız oldu."
                onResult(false, errorMsg)
            }
        }
    }

    fun restoreFromCloud(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val result = appRepository.cloudSyncManager.restoreLayout()
            if (result.isSuccess) {
                val lastTime = appRepository.getLastBackupTime()
                _uiState.update { it.copy(isSyncing = false, lastBackupTime = lastTime) }
                appRepository.invalidateAppCache()
                refreshAppList()
                onResult(true, "Buluttan geri yükleme başarılı!")
            } else {
                _uiState.update { it.copy(isSyncing = false) }
                val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Geri yükleme başarısız oldu."
                onResult(false, errorMsg)
            }
        }
    }

    fun setOnboardingComplete() {
        _uiState.update { it.copy(showOnboarding = false) }
    }

    fun setShowOnboarding(show: Boolean) {
        _uiState.update { it.copy(showOnboarding = show) }
    }

    val updateManager get() = appRepository.updateManager

    fun checkForUpdates(currentVersion: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(updateState = com.tarslauncher.app.data.updater.UpdateState.Checking) }
            val result = appRepository.updateManager.checkForUpdate(currentVersion)
            result.onSuccess { updateInfo ->
                if (updateInfo.hasUpdate) {
                    _uiState.update { it.copy(updateState = com.tarslauncher.app.data.updater.UpdateState.Available(updateInfo)) }
                } else {
                    _uiState.update { it.copy(updateState = com.tarslauncher.app.data.updater.UpdateState.UpToDate) }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(updateState = com.tarslauncher.app.data.updater.UpdateState.Error(error.localizedMessage ?: "Güncelleme kontrolü başarısız oldu."))
                }
            }
        }
    }

    fun startUpdateDownload(info: com.tarslauncher.app.data.updater.UpdateInfo) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(updateState = com.tarslauncher.app.data.updater.UpdateState.Downloading(progress = 0f, bytesDownloaded = 0L, totalBytes = info.apkSize))
            }
            val result = appRepository.updateManager.downloadApk(
                downloadUrl = info.downloadUrl,
                versionName = info.versionName,
                onProgress = { progress, downloaded, total ->
                    _uiState.update {
                        it.copy(updateState = com.tarslauncher.app.data.updater.UpdateState.Downloading(progress, downloaded, total))
                    }
                }
            )
            result.onSuccess { file ->
                _uiState.update { it.copy(updateState = com.tarslauncher.app.data.updater.UpdateState.ReadyToInstall(file)) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(updateState = com.tarslauncher.app.data.updater.UpdateState.Error(error.localizedMessage ?: "İndirme başarısız oldu."))
                }
            }
        }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(updateState = com.tarslauncher.app.data.updater.UpdateState.Idle) }
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
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
