package com.tarslauncher.app.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tarslauncher.app.data.datastore.LauncherDataStore
import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.utils.SecurityHelper
import com.tarslauncher.app.utils.base64ToDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AppRepository(
    private val context: Context,
    private val dataStore: LauncherDataStore = LauncherDataStore(context)
) {
    private val packageManager: PackageManager = context.packageManager
    private val gson = Gson()
    val cloudSyncManager: com.tarslauncher.app.data.sync.CloudSyncManager = com.tarslauncher.app.data.sync.CloudSyncManager(context, dataStore)

    private var cachedInstalledApps: List<AppInfo>? = null

    companion object {
        private const val TAG = "AppRepository"
        const val REDGIFS_ICON_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAMAAAD04JH5AAAAaVBMVEUAAADXAAPbAAMLAAGgAAMEAAGFAAI/AAHIAAPeAANrAAJ/AALUAAPCAAOqAANaAAE7AAEXAACyAANyAAKRAAK6AAMSAAFPAAEcAAFmAAEgAAGLAAKYAAIjAABEAAEuAAEzAAEqAAFgAAEVFxf1AAAECUlEQVR4nO2YbZuqIBCGV7KEBG1Fctu0zP//I49a6YAI7Fk/nOtcc3/MZnh4mRf4+EAQBEEQBEEQBEEQBEEQ5L/jq02q7nI8Hi95dbp//sh23x1WSaXMky+fh7u8FpRHhDDGSBRxmh2673ABu5iRVQZ3IrtWDnuZiWj440xvx0XdhQvQrBcMOhhNT1bj9hoxYrMfbOR9EwEvf+JgMS2Fw5YQlW4moHcXZeaEcmGdPJRQtJsJiCJW6Apq7jckIt9OQEQyEF9NzIJs+GU7ARGTk1WrgsYftu64nQAipvkXphEMXf0Dvf1IAAdEC29yZXzSZ5/42qeuOhPcsCLqHC6A1EM+fZNeY/2gkfhpUxpDRPHxMfnLa2p8LsMFMOPI7CoFnZFsnExlJL642mlWba0LEPYsZhWwODE7mGtIMeT4RhdF5dJpyqECZkliwQI+pCag6X85atOj1kKRakHCd7b/BAq4gcmQbPB01XbAXqjO2jFlrmrmE5BAAeMh1I7g2upe4CYwV1HwCaiAJz7ORAJFaq3wPxSIaFL/QkA9f35F4Wk+l8RyAF/ccsAvtkDOC8DUqxpd2NuEOxyHoguQ+8+Zpq3Kaa6E183bplPPJEmuWwsgKgMUakyEfXdDuNL6skbGdOiFvKXupwKiRU/YQ7M0P5mt6b65XUo1i9q7CRawhDvT2OSljJ04lsongLYhAs7U0Vv3e+iIQ29XXHQ3e3v7lVTTxpyp04srEXgbEhJRlZVmJH8f40KIJFRAvF4NQjqi8apRtvOC5xkff5xkeQX8YgXePthc9+JXfMxhuN0WmDEIP4nXWSjftXbudXwCHE2RLoCKCUrN7u7VWIBCN6VinwBHOdQzYdqe3tyq7kC1voKo0SAjS8d9GJpAS+64nziL0bfe3fEh7B5a6n41o3uZmlDwN+G447ur4TmDS8uHO3erLfZ6OdI6qfXxfeVYawDH/uehNZzR2uJKsAdsvW3wNyRwuGc0aS0Z4faXiEQ7la5XFp8ACkcb80kFf+oV2E74XYG/uCpBgAB45p4JrdRjgxS5McP2qFm5b4d/IeBsPIyQqEjnMb67g/5y4embfALEUsBHbqadZ8EayArzdkqUqx3xC4D3sCmlSz0SnjthT+E/u577BEzdkWTO1AtMXEkwSEBhFTC8UAWNT72PRD4BMBXCoqZf3NfGF64rSZiA2nIIRx6l75mMkDjgrdIn4ACCnmTagb4VLgmEKFcGhgJA97oUcIHdrWq1b+cqI6tPtUI+Fs6sAmoKWPbviQCfl++OTVoMj9XG5Yar2v9C+eaezNyWt+3dCX63Taodn+v5MxP0l3EVp7n7YWx7vu59A5XnXX8RT9rG9SCDIAiCIAiCIAiCIAiCIMi/wR8lHTsSEuIu+wAAAABJRU5ErkJggg=="

        private val PWA_LIST_TYPE = object : TypeToken<List<PwaInfo>>() {}.type
        private val USAGE_STATS_LIST_TYPE = object : TypeToken<List<AppUsageStats>>() {}.type
        private val PERSIST_FOLDER_LIST_TYPE = object : TypeToken<List<PersistFolder>>() {}.type
    }

    val settingsFlow: Flow<LauncherSettings> = dataStore.settingsFlow

    fun invalidateAppCache() {
        cachedInstalledApps = null
    }

    suspend fun getLastBackupTime(): Long = dataStore.getLastBackupTime()

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            dataStore.migrateFromLegacyPrefsIfNeeded()
            seedRedGifsPwaIfNeeded()
        }
    }

    suspend fun seedRedGifsPwaIfNeeded() {
        if (!dataStore.isPwasSeeded()) {
            val currentPwas = (getInstalledPwas() as? RepositoryResult.Success)?.data?.toMutableList() ?: mutableListOf()
            if (currentPwas.none { it.url.contains("redgifs.com") }) {
                currentPwas.add(
                    PwaInfo(
                        id = "redgifs",
                        name = "RedGIFs",
                        url = "https://www.redgifs.com",
                        iconBase64 = REDGIFS_ICON_BASE64
                    )
                )
                val json = gson.toJson(currentPwas)
                dataStore.savePwasJson(json)
            }
            dataStore.setPwasSeeded(true)
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
                        packageName = "pwa_${pwa.id}",
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

            cachedInstalledApps = apps
            RepositoryResult.Success(apps)
        } catch (e: Exception) {
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
            Log.e(TAG, "Failed to load icon packs", e)
            RepositoryResult.Error(e, "İkon paketleri yüklenirken hata oluştu.")
        }
    }

    suspend fun getUsageStats(): RepositoryResult<List<AppUsageStats>> = withContext(Dispatchers.IO) {
        try {
            val json = dataStore.getUsageStatsJson()
            if (!json.isNullOrBlank()) {
                val list: List<AppUsageStats> = gson.fromJson(json, USAGE_STATS_LIST_TYPE) ?: emptyList()
                RepositoryResult.Success(list)
            } else {
                RepositoryResult.Success(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing usage stats", e)
            RepositoryResult.Error(e, "Kullanım istatistikleri okunamadı.")
        }
    }

    suspend fun saveUsageStats(stats: List<AppUsageStats>) = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(stats)
            dataStore.saveUsageStatsJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving usage stats", e)
        }
    }

    suspend fun getFolders(totalFolders: Int, installedApps: List<AppInfo>): RepositoryResult<List<FolderInfo>> = withContext(Dispatchers.IO) {
        try {
            val savedJson = dataStore.getFoldersJson()
            val saved: List<PersistFolder>? = if (!savedJson.isNullOrBlank()) {
                try {
                    gson.fromJson(savedJson, PERSIST_FOLDER_LIST_TYPE)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

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
        try {
            val persistList = folders.map { f ->
                PersistFolder(id = f.id, apps = f.apps.map { it.packageName })
            }
            val json = gson.toJson(persistList)
            dataStore.saveFoldersJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving folders", e)
        }
    }

    // PWA (Progressive Web Apps) Management
    suspend fun getInstalledPwas(): RepositoryResult<List<PwaInfo>> = withContext(Dispatchers.IO) {
        try {
            val json = dataStore.getPwasJson()
            if (!json.isNullOrBlank()) {
                val list: List<PwaInfo> = gson.fromJson(json, PWA_LIST_TYPE) ?: emptyList()
                RepositoryResult.Success(list)
            } else {
                RepositoryResult.Success(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading PWAs", e)
            RepositoryResult.Error(e, "Web uygulamaları yüklenemedi.")
        }
    }

    suspend fun savePwa(pwa: PwaInfo): RepositoryResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentList = (getInstalledPwas() as? RepositoryResult.Success)?.data?.toMutableList() ?: mutableListOf()
            currentList.removeAll { it.id == pwa.id || it.url == pwa.url }
            currentList.add(pwa)
            val json = gson.toJson(currentList)
            dataStore.savePwasJson(json)
            invalidateAppCache()
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving PWA", e)
            RepositoryResult.Error(e, "Web uygulaması kaydedilemedi.")
        }
    }

    suspend fun deletePwa(pwaId: String): RepositoryResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentList = (getInstalledPwas() as? RepositoryResult.Success)?.data?.toMutableList() ?: mutableListOf()
            currentList.removeAll { it.id == pwaId }
            val json = gson.toJson(currentList)
            dataStore.savePwasJson(json)
            invalidateAppCache()
            RepositoryResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting PWA", e)
            RepositoryResult.Error(e, "Web uygulaması silinemedi.")
        }
    }

    private val staticPresets: List<PwaInfo> by lazy {
        listOf(
            PwaInfo(id = "preset_redgifs", name = "RedGIFs", url = "https://www.redgifs.com", iconBase64 = REDGIFS_ICON_BASE64),
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
    suspend fun updateGridSize(gridSize: GridSize) = dataStore.updateGridSize(gridSize)
    suspend fun updateButtonColorMode(mode: String) = dataStore.updateButtonColorMode(mode)
    suspend fun updateShowTarsHeader(show: Boolean) = dataStore.updateShowTarsHeader(show)
    suspend fun updateShowClock(show: Boolean) = dataStore.updateShowClock(show)
    suspend fun updateSelectedIconPack(pack: String?) = dataStore.updateSelectedIconPack(pack)
    suspend fun updateAppDrawerIconSize(size: Int) = dataStore.updateAppDrawerIconSize(size)
}
