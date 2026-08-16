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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AppRepository(
    private val context: Context,
    private val dataStore: LauncherDataStore = LauncherDataStore(context)
) {
    private val packageManager: PackageManager = context.packageManager
    private val gson = Gson()

    companion object {
        private const val TAG = "AppRepository"
    }

    val settingsFlow: Flow<LauncherSettings> = dataStore.settingsFlow

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            dataStore.migrateFromLegacyPrefsIfNeeded()
        }
    }

    suspend fun getInstalledApps(): RepositoryResult<List<AppInfo>> = withContext(Dispatchers.IO) {
        try {
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfoList = try {
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            } catch (e: Exception) {
                try {
                    packageManager.queryIntentActivities(intent, 0)
                } catch (e2: Exception) {
                    packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                }
            }

            val apps = mutableListOf<AppInfo>()
            resolveInfoList.forEach { resolveInfo ->
                try {
                    val activityInfo = resolveInfo.activityInfo ?: return@forEach
                    val packageName = activityInfo.packageName

                    if (packageName == context.packageName) return@forEach

                    if (!SecurityHelper.validatePackageName(packageName)) {
                        return@forEach
                    }

                    val appName = try {
                        activityInfo.loadLabel(packageManager).toString()
                    } catch (e: Exception) {
                        packageName.substringAfterLast(".")
                    }

                    if (appName.isBlank()) return@forEach

                    val icon = try {
                        activityInfo.loadIcon(packageManager)
                    } catch (e: Exception) {
                        try {
                            packageManager.getApplicationIcon(packageName)
                        } catch (e2: Exception) {
                            null
                        }
                    }

                    apps.add(
                        AppInfo(
                            name = appName.take(50).trim(),
                            packageName = packageName,
                            icon = icon,
                            activityName = activityInfo.name
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error loading app info: ${e.message}")
                }
            }

            val result = apps.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
            RepositoryResult.Success(result)
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
                val type = object : TypeToken<List<AppUsageStats>>() {}.type
                val list: List<AppUsageStats> = gson.fromJson(json, type) ?: emptyList()
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
            val listType = object : TypeToken<List<PersistFolder>>() {}.type
            val saved: List<PersistFolder>? = if (!savedJson.isNullOrBlank()) {
                try {
                    gson.fromJson(savedJson, listType)
                } catch (e: Exception) {
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
                    match.apps.mapNotNull { pkg -> appMap[pkg] }.toMutableList()
                } else {
                    mutableListOf()
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
            val existingJson = dataStore.getFoldersJson()
            val listType = object : TypeToken<List<PersistFolder>>() {}.type
            val existingMap: MutableMap<String, PersistFolder> = if (!existingJson.isNullOrBlank()) {
                try {
                    val list: List<PersistFolder>? = gson.fromJson(existingJson, listType)
                    list?.associateBy { it.id }?.toMutableMap() ?: mutableMapOf()
                } catch (_: Exception) {
                    mutableMapOf()
                }
            } else {
                mutableMapOf()
            }

            folders.forEach { f ->
                existingMap[f.id] = PersistFolder(f.id, f.apps.map { a -> a.packageName })
            }

            val mergedList = existingMap.values.sortedBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
            val json = gson.toJson(mergedList)
            dataStore.saveFoldersJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving folders", e)
        }
    }

    // Type-safe setting updates
    suspend fun updateShowAppDrawer(show: Boolean) = dataStore.updateShowAppDrawer(show)
    suspend fun updateGridSize(gridSize: GridSize) = dataStore.updateGridSize(gridSize)
    suspend fun updateButtonColorMode(mode: String) = dataStore.updateButtonColorMode(mode)
    suspend fun updateShowTarsHeader(show: Boolean) = dataStore.updateShowTarsHeader(show)
    suspend fun updateShowClock(show: Boolean) = dataStore.updateShowClock(show)
    suspend fun updateSelectedIconPack(pack: String?) = dataStore.updateSelectedIconPack(pack)
    suspend fun updateAppDrawerIconSize(size: Int) = dataStore.updateAppDrawerIconSize(size)
}
