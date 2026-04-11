package com.tarslauncher.app.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.SecurePreferences
import com.tarslauncher.app.utils.SecurityHelper

class AppRepository(private val context: Context) {
    private val packageManager = context.packageManager
    private val sharedPrefs = SecurePreferences.getEncryptedPreferences(context)
    private val gson = Gson()

    fun getInstalledApps(): List<AppInfo> {
        return try {
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            
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
                    
                    apps.add(AppInfo(name = appName.take(50).trim(), packageName = packageName, icon = icon))
                } catch (e: Exception) {
                    Log.w("AppRepository", "Error loading app: $e")
                }
            }
            apps.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getInstalledIconPacks(): List<IconPackInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME")
        return packageManager.queryIntentActivities(intent, 0).mapNotNull {
            try {
                val appName = it.loadLabel(packageManager).toString()
                val packageName = it.activityInfo.packageName
                val icon = it.loadIcon(packageManager)
                IconPackInfo(name = appName, packageName = packageName, icon = icon)
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.name }
    }

    fun getUsageStats(): List<AppUsageStats> {
        return try {
            val json = sharedPrefs.getString("app_usage_stats", null)
            if (json != null) {
                val type = object : TypeToken<List<AppUsageStats>>() {}.type
                gson.fromJson(json, type) ?: emptyList<AppUsageStats>()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveUsageStats(stats: List<AppUsageStats>) {
        try {
            val json = gson.toJson(stats)
            sharedPrefs.edit().putString("app_usage_stats", json).apply()
        } catch (e: Exception) {
            Log.e("AppRepository", "Error saving usage stats", e)
        }
    }

    fun getFolders(totalFolders: Int, installedApps: List<AppInfo>): List<FolderInfo> {
        val savedJson = sharedPrefs.getString("folders", null)
        val listType = object : TypeToken<List<PersistFolder>>() {}.type
        val saved: List<PersistFolder>? = try {
            if (savedJson != null) gson.fromJson<List<PersistFolder>>(savedJson, listType) else null
        } catch (e: Exception) {
            null
        }

        return (1..totalFolders).map { index ->
            val id = index.toString()
            val match = saved?.find { it.id == id }
            val appList = if (installedApps.isNotEmpty() && match?.apps != null) {
                match.apps.mapNotNull { pkg -> 
                    installedApps.find { it.packageName == pkg }
                }.toMutableList()
            } else {
                mutableListOf()
            }
            FolderInfo(id, "", appList)
        }
    }

    fun saveFolders(folders: List<FolderInfo>) {
        try {
            val existingJson = sharedPrefs.getString("folders", null)
            val listType = object : TypeToken<List<PersistFolder>>() {}.type
            val existing: MutableMap<String, PersistFolder> = try {
                val list: List<PersistFolder>? = if (existingJson != null) gson.fromJson(existingJson, listType) else null
                list?.associateBy { it.id }?.toMutableMap() ?: mutableMapOf()
            } catch (_: Exception) {
                mutableMapOf()
            }

            folders.forEach { f ->
                existing[f.id] = PersistFolder(f.id, f.apps.map { a -> a.packageName })
            }

            val mergedList = existing.values.sortedBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
            val json = gson.toJson(mergedList)
            sharedPrefs.edit().putString("folders", json).apply()
        } catch (e: Exception) {
            Log.e("AppRepository", "Error saving folders", e)
        }
    }

    fun getBooleanSetting(key: String, defaultValue: Boolean): Boolean = sharedPrefs.getBoolean(key, defaultValue)
    fun getStringSetting(key: String, defaultValue: String?): String? = sharedPrefs.getString(key, defaultValue)
    fun getIntSetting(key: String, defaultValue: Int): Int = sharedPrefs.getInt(key, defaultValue)

    fun saveBooleanSetting(key: String, value: Boolean) = sharedPrefs.edit().putBoolean(key, value).apply()
    fun saveStringSetting(key: String, value: String?) = sharedPrefs.edit().putString(key, value).apply()
    fun saveIntSetting(key: String, value: Int) = sharedPrefs.edit().putInt(key, value).apply()
}
