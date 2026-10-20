package com.tarslauncher.app.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.tarslauncher.app.data.datastore.LauncherDataStore
import com.tarslauncher.app.data.json.parseJson
import com.tarslauncher.app.data.json.toJson
import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.utils.SecurityHelper
import com.tarslauncher.app.utils.base64ToDrawable
import com.tarslauncher.app.utils.getAppImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AppRepository(
    context: Context,
    private val dataStore: LauncherDataStore = LauncherDataStore(context.applicationContext)
) {
    private val context: Context = context.applicationContext
    private val packageManager: PackageManager = this.context.packageManager
    val updateManager: com.tarslauncher.app.data.updater.UpdateManager = com.tarslauncher.app.data.updater.UpdateManager(this.context)

    @Volatile
    private var cachedInstalledApps: List<AppInfo>? = null

    @Volatile
    private var cachedPwas: List<PwaInfo>? = null

    private val pwaMutex = Mutex()
    private val foldersMutex = Mutex()
    private val usageStatsMutex = Mutex()

    companion object {
        private const val TAG = "AppRepository"
    }

    val settingsFlow: Flow<LauncherSettings> = dataStore.settingsFlow
    val pwasJsonFlow: Flow<String?> = dataStore.pwasJsonFlow

    fun invalidateAppCache() {
        cachedInstalledApps = null
        cachedPwas = null
        com.tarslauncher.app.utils.clearIconBitmapCache()
    }

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            dataStore.migrateFromLegacyPrefsIfNeeded()
            cleanupLegacyRedGifsPwaIfNeeded()
        }
    }

    private suspend fun cleanupLegacyRedGifsPwaIfNeeded() {
        try {
            val pwasJson = dataStore.getPwasJson()
            if (!pwasJson.isNullOrBlank() && pwasJson.contains("redgifs", ignoreCase = true)) {
                val currentList = pwasJson.parseJson<List<PwaInfo>>()?.toMutableList()
                if (currentList != null) {
                    val removed = currentList.removeAll {
                        it.id.contains("redgifs", ignoreCase = true) || it.url.contains("redgifs.com", ignoreCase = true)
                    }
                    if (removed) {
                        dataStore.savePwasJson(currentList.toJson())
                        cachedPwas = currentList
                        invalidateAppCache()
                    }
                }
            }

            val foldersJson = dataStore.getFoldersJson()
            if (!foldersJson.isNullOrBlank() && foldersJson.contains("redgifs", ignoreCase = true)) {
                val folders = foldersJson.parseJson<List<PersistFolder>>()?.map { folder ->
                    folder.copy(apps = folder.apps.filterNot { it.contains("redgifs", ignoreCase = true) })
                }
                if (folders != null) {
                    dataStore.saveFoldersJson(folders.toJson())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup legacy redgifs PWA: ${e.message}")
        }
    }

    suspend fun getInstalledApps(forceRefresh: Boolean = false): RepositoryResult<List<AppInfo>> = withContext(Dispatchers.IO) {
        try {
            if (!forceRefresh && cachedInstalledApps != null) {
                return@withContext RepositoryResult.Success(cachedInstalledApps!!)
            }

            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfoList = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                try {
                    packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                } catch (e: Exception) {
                    try {
                        packageManager.queryIntentActivities(intent, 0)
                    } catch (e2: Exception) {
                        packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    }
                }
            }

            val apps = ArrayList<AppInfo>(resolveInfoList.size)
            val seenPackages = HashSet<String>(resolveInfoList.size)

            for (resolveInfo in resolveInfoList) {
                try {
                    val activityInfo = resolveInfo.activityInfo ?: continue
                    val packageName = activityInfo.packageName

                    if (packageName == context.packageName || !seenPackages.add(packageName)) continue
                    if (!SecurityHelper.validatePackageName(packageName)) continue

                    val appName = try {
                        activityInfo.loadLabel(packageManager).toString()
                    } catch (_: Exception) {
                        packageName.substringAfterLast(".")
                    }
                    if (appName.isBlank()) continue

                    val icon = try {
                        activityInfo.loadIcon(packageManager)
                    } catch (_: Exception) {
                        null
                    }

                    val isWebApk = packageName.startsWith("org.chromium.webapk") || packageName.contains(".webapk")
                    apps.add(
                        AppInfo(
                            name = appName.take(50).trim(),
                            packageName = packageName,
                            icon = icon,
                            activityName = activityInfo.name,
                            isPwa = isWebApk
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error loading app info: ${e.message}")
                }
            }

            // Load saved PWAs (reuse parsed list without duplicate deserialization)
            val pwas = (getInstalledPwas() as? RepositoryResult.Success)?.data.orEmpty()
            for (pwa in pwas) {
                val pwaIcon = if (!pwa.iconBase64.isNullOrBlank()) {
                    base64ToDrawable(context, pwa.iconBase64)
                } else null

                apps.add(
                    AppInfo(
                        name = pwa.name.take(50).trim(),
                        packageName = "${PwaInfo.PACKAGE_PREFIX}${pwa.id}",
                        icon = pwaIcon,
                        activityName = "",
                        isPwa = true,
                        webUrl = pwa.url,
                        pwaId = pwa.id,
                        shortcutPackage = pwa.shortcutPackage,
                        shortcutId = pwa.shortcutId,
                        iconBase64 = pwa.iconBase64,
                        iconUrl = pwa.iconUrl
                    )
                )
            }

            // Pre-warm icons in background to prevent first-render UI jank
            for (app in apps) {
                getAppImageBitmap(app.packageName, app.icon)
            }

            cachedInstalledApps = apps
            RepositoryResult.Success(apps)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to load installed apps", e)
            RepositoryResult.Error(e, "Uygulamalar yüklenirken bir hata oluştu.")
        }
    }

    suspend fun getInstalledIconPacks(): RepositoryResult<List<IconPackInfo>> = withContext(Dispatchers.IO) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME")
            val list = packageManager.queryIntentActivities(intent, 0).mapNotNull {
                try {
                    val appName = it.loadLabel(packageManager).toString()
                    val packageName = it.activityInfo.packageName
                    val icon = it.loadIcon(packageManager)
                    IconPackInfo(name = appName, packageName = packageName, icon = icon)
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.name }
            RepositoryResult.Success(list)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to load icon packs", e)
            RepositoryResult.Error(e, "İkon paketleri yüklenirken hata oluştu.")
        }
    }

    suspend fun getUsageStats(): RepositoryResult<List<AppUsageStats>> = withContext(Dispatchers.IO) {
        try {
            val json = dataStore.getUsageStatsJson()
            val list = json.parseJson<List<AppUsageStats>>().orEmpty()
            RepositoryResult.Success(list)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error parsing usage stats", e)
            RepositoryResult.Error(e, "Kullanım istatistikleri okunamadı.")
        }
    }

    suspend fun saveUsageStats(stats: List<AppUsageStats>) = withContext(Dispatchers.IO) {
        usageStatsMutex.withLock {
            try {
                dataStore.saveUsageStatsJson(stats.toJson())
            } catch (e: Exception) {
                Log.e(TAG, "Error saving usage stats", e)
            }
        }
    }

    suspend fun getFolders(totalFolders: Int, installedApps: List<AppInfo>): RepositoryResult<List<FolderInfo>> = withContext(Dispatchers.IO) {
        try {
            val savedJson = dataStore.getFoldersJson()
            val saved: List<PersistFolder>? = savedJson.parseJson<List<PersistFolder>>()

            // O(1) map lookup for mapping packages to installed apps
            val appMap = installedApps.associateBy { it.packageName }
            val savedMap = saved?.associateBy { it.id } ?: emptyMap()

            val folderList = (1..totalFolders).map { index ->
                val id = index.toString()
                val match = savedMap[id]
                val appList = if (match?.apps != null) {
                    match.apps.mapNotNull { pkg -> appMap[pkg] }
                } else {
                    emptyList()
                }
                FolderInfo(id = id, name = "", apps = appList)
            }
            RepositoryResult.Success(folderList)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading folders", e)
            RepositoryResult.Error(e, "Klasörler yüklenirken hata oluştu.")
        }
    }

    suspend fun saveFolders(folders: List<FolderInfo>) = withContext(Dispatchers.IO) {
        foldersMutex.withLock {
            try {
                val persistList = folders.map { f ->
                    PersistFolder(id = f.id, apps = f.apps.map { it.packageName })
                }
                dataStore.saveFoldersJson(persistList.toJson())
            } catch (e: Exception) {
                Log.e(TAG, "Error saving folders", e)
            }
        }
    }

    // PWA (Progressive Web Apps) Management
    suspend fun getInstalledPwas(forceRefresh: Boolean = false): RepositoryResult<List<PwaInfo>> = withContext(Dispatchers.IO) {
        try {
            if (!forceRefresh && cachedPwas != null) {
                return@withContext RepositoryResult.Success(cachedPwas!!)
            }
            val json = dataStore.getPwasJson()
            val list = json.parseJson<List<PwaInfo>>().orEmpty()
            cachedPwas = list
            RepositoryResult.Success(list)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error loading PWAs", e)
            RepositoryResult.Error(e, "Web uygulamaları yüklenemedi.")
        }
    }

    suspend fun savePwa(pwa: PwaInfo): RepositoryResult<Unit> = withContext(Dispatchers.IO) {
        pwaMutex.withLock {
            try {
                val currentList = (getInstalledPwas() as? RepositoryResult.Success)?.data?.toMutableList() ?: mutableListOf()
                currentList.removeAll { it.id == pwa.id || (pwa.url.isNotBlank() && it.url == pwa.url) }
                currentList.add(pwa)
                dataStore.savePwasJson(currentList.toJson())
                cachedPwas = currentList
                invalidateAppCache()
                RepositoryResult.Success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving PWA", e)
                RepositoryResult.Error(e, "Web uygulaması kaydedilemedi.")
            }
        }
    }

    suspend fun deletePwa(pwaId: String): RepositoryResult<Unit> = withContext(Dispatchers.IO) {
        pwaMutex.withLock {
            try {
                val currentList = (getInstalledPwas() as? RepositoryResult.Success)?.data?.toMutableList() ?: mutableListOf()
                currentList.removeAll { it.id == pwaId }
                dataStore.savePwasJson(currentList.toJson())
                cachedPwas = currentList
                invalidateAppCache()
                RepositoryResult.Success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting PWA", e)
                RepositoryResult.Error(e, "Web uygulaması silinemedi.")
            }
        }
    }

    private val staticPresets: List<PwaInfo> by lazy {
        listOf(
            PwaInfo(id = "preset_chatgpt", name = "ChatGPT", url = "https://chatgpt.com"),
            PwaInfo(id = "preset_x", name = "X", url = "https://x.com"),
            PwaInfo(id = "preset_youtube", name = "YouTube", url = "https://m.youtube.com"),
            PwaInfo(id = "preset_instagram", name = "Instagram", url = "https://www.instagram.com"),
            PwaInfo(id = "preset_github", name = "GitHub", url = "https://github.com"),
            PwaInfo(id = "preset_notion", name = "Notion", url = "https://www.notion.so"),
            PwaInfo(id = "preset_reddit", name = "Reddit", url = "https://www.reddit.com"),
            PwaInfo(id = "preset_spotify", name = "Spotify", url = "https://open.spotify.com"),
            PwaInfo(id = "preset_telegram", name = "Telegram", url = "https://web.telegram.org"),
            PwaInfo(id = "preset_wikipedia", name = "Wikipedia", url = "https://en.m.wikipedia.org"),
            PwaInfo(id = "preset_discord", name = "Discord", url = "https://discord.com/app"),
            PwaInfo(id = "preset_threads", name = "Threads", url = "https://www.threads.net")
        )
    }

    fun getPopularPwaPresets(): List<PwaInfo> = staticPresets

    // Type-safe setting updates
    suspend fun updateShowAppDrawer(show: Boolean) = dataStore.updateShowAppDrawer(show)
    suspend fun updateDualAppDrawer(enabled: Boolean) = dataStore.updateDualAppDrawer(enabled)
    suspend fun updateAppDrawerRows(rows: Int) = dataStore.updateAppDrawerRows(rows)
    suspend fun updateGridSize(gridSize: GridSize) = dataStore.updateGridSize(gridSize)
    suspend fun updateButtonColorMode(mode: String) = dataStore.updateButtonColorMode(mode)
    suspend fun updateShowTarsHeader(show: Boolean) = dataStore.updateShowTarsHeader(show)
    suspend fun updateShowClock(show: Boolean) = dataStore.updateShowClock(show)
    suspend fun updateShowAsciiDiamond(enabled: Boolean) = dataStore.updateShowAsciiDiamond(enabled)
    suspend fun updateAsciiDiamondScale(scale: Int) = dataStore.updateAsciiDiamondScale(scale)
    suspend fun updateAsciiDiamondOffsetY(offsetY: Int) = dataStore.updateAsciiDiamondOffsetY(offsetY)
    suspend fun updateAsciiDiamondFps(fps: Int) = dataStore.updateAsciiDiamondFps(fps)
    suspend fun updateAsciiDiamondSpeed(speed: Int) = dataStore.updateAsciiDiamondSpeed(speed)
    suspend fun updateSelectedIconPack(pack: String?) = dataStore.updateSelectedIconPack(pack)
    suspend fun updateAppDrawerIconSize(size: Int) = dataStore.updateAppDrawerIconSize(size)
    suspend fun updateShowFolderGrid(show: Boolean) = dataStore.updateShowFolderGrid(show)
    suspend fun updateAppDrawerOffsetY(offsetY: Int) = dataStore.updateAppDrawerOffsetY(offsetY)
}
