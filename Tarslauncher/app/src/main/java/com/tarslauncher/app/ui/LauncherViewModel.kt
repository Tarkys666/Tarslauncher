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

    private var refreshJob: kotlinx.coroutines.Job? = null

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
            // Observe PWA/shortcut additions from external activities (e.g. PinShortcutActivity)
            var isInitialPwaEmit = true
            launch {
                appRepository.pwasJsonFlow.collectLatest {
                    if (isInitialPwaEmit) {
                        isInitialPwaEmit = false
                    } else {
                        appRepository.invalidateAppCache()
                        refreshAppList()
                    }
                }
            }
            refreshAppList()
        }
    }

    companion object {
        private val APP_COMPARATOR = compareByDescending<AppInfo> { it.isFavorite }
            .thenByDescending { it.usageCount }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { (it.name as String?).takeIf { n -> !n.isNullOrBlank() } ?: "" }
    }

    fun refreshAppList() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
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
                    val usageMap = usageStats.associateBy {
                        it.packageName?.takeIf { pkg -> pkg.isNotBlank() } ?: "unknown"
                    }

                    val installedApps = baseApps.map { app ->
                        val stats = usageMap[app.packageName]
                        val count = stats?.usageCount ?: 0
                        app.copy(
                            usageCount = count,
                            isFavorite = (stats?.isFavorite ?: false) || count >= 10
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
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("LauncherViewModel", "Failed to refresh app list", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "An error occurred while loading apps."
                    )
                }
            }
        }
    }

    fun addPwa(name: String, url: String, iconBase64: String? = null) {
        viewModelScope.launch {
            val sanitized = PwaLauncherHelper.sanitizeWebUrl(url) ?: return@launch
            val title = if (name.isNotBlank()) name.trim() else PwaFetcherHelper.extractDefaultTitle(sanitized)
            val presetIcon = _uiState.value.pwaPresets
                .firstOrNull { it.url.equals(sanitized, ignoreCase = true) }
                ?.iconBase64
            val fetchedIcon = iconBase64 ?: presetIcon ?: PwaFetcherHelper.fetchIconBase64(sanitized)

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
            val cleanId = pwaId.removePrefix(PwaInfo.PACKAGE_PREFIX)
            val pwaPackageName = "${PwaInfo.PACKAGE_PREFIX}$cleanId"
            appRepository.deletePwa(cleanId)
            val updatedFolders = _uiState.value.folders.map { folder ->
                folder.copy(apps = folder.apps.filter {
                    it.packageName != pwaPackageName && it.packageName != pwaId && it.pwaId != cleanId && it.pwaId != pwaId
                })
            }
            _uiState.update { state ->
                state.copy(
                    installedApps = state.installedApps.filter {
                        it.packageName != pwaPackageName && it.packageName != pwaId && it.pwaId != cleanId && it.pwaId != pwaId
                    },
                    installedPwas = state.installedPwas.filter { it.id != cleanId && it.id != pwaId },
                    folders = updatedFolders
                )
            }
            appRepository.saveFolders(updatedFolders)
        }
    }

    private fun reloadFolders(gridSize: GridSize, apps: List<AppInfo>) {
        viewModelScope.launch {
            val foldersResult = appRepository.getFolders(gridSize.totalCells, apps)
            val folders = foldersResult.getOrDefault(emptyList())
            _uiState.update { it.copy(folders = folders) }
        }
    }

    private var usagePersistJob: kotlinx.coroutines.Job? = null

    fun incrementAppUsage(app: AppInfo) {
        val currentList = _uiState.value.installedApps
        val index = currentList.indexOfFirst { it.packageName == app.packageName }
        if (index == -1) return

        val newCount = currentList[index].usageCount + 1
        val updatedApp = currentList[index].copy(
            usageCount = newCount,
            isFavorite = currentList[index].isFavorite || newCount >= 10
        )
        val updatedList = currentList.toMutableList().apply { set(index, updatedApp) }
            .sortedWith(APP_COMPARATOR)

        _uiState.update { it.copy(installedApps = updatedList) }

        // Debounced background write to eliminate disk write amplification
        usagePersistJob?.cancel()
        usagePersistJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(1000L) // 1 second debounce window
            val stats = _uiState.value.installedApps
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

    fun updateDualAppDrawer(enabled: Boolean) {
        viewModelScope.launch { appRepository.updateDualAppDrawer(enabled) }
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

    fun updateShowAsciiDiamond(enabled: Boolean) {
        viewModelScope.launch {
            appRepository.updateShowAsciiDiamond(enabled)
            if (enabled) {
                appRepository.updateShowClock(false)
                appRepository.updateShowTarsHeader(false)
            }
        }
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
                    it.copy(updateState = com.tarslauncher.app.data.updater.UpdateState.Error(error.localizedMessage ?: "Update check failed."))
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
                expectedHash = info.expectedHash,
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
                    it.copy(updateState = com.tarslauncher.app.data.updater.UpdateState.Error(error.localizedMessage ?: "Download failed."))
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
