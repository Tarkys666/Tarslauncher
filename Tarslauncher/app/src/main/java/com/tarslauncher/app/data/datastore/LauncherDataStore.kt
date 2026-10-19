package com.tarslauncher.app.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.tarslauncher.app.data.model.GridSize
import com.tarslauncher.app.data.model.LauncherSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tars_settings",
    corruptionHandler = ReplaceFileCorruptionHandler {
        emptyPreferences()
    }
)

class LauncherDataStore(private val context: Context) {

    companion object {
        private const val TAG = "LauncherDataStore"

        val KEY_SHOW_APP_DRAWER = booleanPreferencesKey("show_app_drawer")
        val KEY_GRID_SIZE = stringPreferencesKey("grid_size")
        val KEY_BUTTON_COLOR_MODE = stringPreferencesKey("button_color_mode")
        val KEY_SHOW_TARS_HEADER = booleanPreferencesKey("show_tars_header")
        val KEY_SHOW_CLOCK = booleanPreferencesKey("show_clock")
        val KEY_SELECTED_ICON_PACK = stringPreferencesKey("selected_icon_pack")
        val KEY_APP_DRAWER_ICON_SIZE = intPreferencesKey("app_drawer_icon_size")
        val KEY_FOLDERS_JSON = stringPreferencesKey("folders_json")
        val KEY_USAGE_STATS_JSON = stringPreferencesKey("usage_stats_json")
        val KEY_PWAS_JSON = stringPreferencesKey("pwas_json")
        val KEY_PWAS_SEEDED = booleanPreferencesKey("pwas_seeded")
        val KEY_MIGRATED_FROM_SP = booleanPreferencesKey("migrated_from_sp")
        val KEY_LAST_BACKUP_TIME = longPreferencesKey("last_backup_time")
    }

    val settingsFlow: Flow<LauncherSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading settings from DataStore", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            LauncherSettings(
                showAppDrawer = preferences[KEY_SHOW_APP_DRAWER] ?: true,
                gridSize = GridSize.fromString(preferences[KEY_GRID_SIZE]),
                buttonColorMode = preferences[KEY_BUTTON_COLOR_MODE] ?: "black",
                showTarsHeader = preferences[KEY_SHOW_TARS_HEADER] ?: true,
                showClock = preferences[KEY_SHOW_CLOCK] ?: true,
                selectedIconPack = preferences[KEY_SELECTED_ICON_PACK],
                appDrawerIconSize = preferences[KEY_APP_DRAWER_ICON_SIZE] ?: 32
            )
        }
        .distinctUntilChanged()

    val pwasJsonFlow: Flow<String?> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences -> preferences[KEY_PWAS_JSON] }
        .distinctUntilChanged()

    suspend fun updateShowAppDrawer(show: Boolean) {
        try {
            context.dataStore.edit { it[KEY_SHOW_APP_DRAWER] = show }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update showAppDrawer", e)
        }
    }

    suspend fun updateGridSize(gridSize: GridSize) {
        try {
            context.dataStore.edit { it[KEY_GRID_SIZE] = gridSize.serialize() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update gridSize", e)
        }
    }

    suspend fun updateButtonColorMode(mode: String) {
        try {
            context.dataStore.edit { it[KEY_BUTTON_COLOR_MODE] = mode }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update buttonColorMode", e)
        }
    }

    suspend fun updateShowTarsHeader(show: Boolean) {
        try {
            context.dataStore.edit { it[KEY_SHOW_TARS_HEADER] = show }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update showTarsHeader", e)
        }
    }

    suspend fun updateShowClock(show: Boolean) {
        try {
            context.dataStore.edit { it[KEY_SHOW_CLOCK] = show }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update showClock", e)
        }
    }

    suspend fun updateSelectedIconPack(iconPackPackage: String?) {
        try {
            context.dataStore.edit {
                if (iconPackPackage.isNullOrBlank()) {
                    it.remove(KEY_SELECTED_ICON_PACK)
                } else {
                    it[KEY_SELECTED_ICON_PACK] = iconPackPackage
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update selectedIconPack", e)
        }
    }

    suspend fun updateAppDrawerIconSize(size: Int) {
        try {
            context.dataStore.edit { it[KEY_APP_DRAWER_ICON_SIZE] = size }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update appDrawerIconSize", e)
        }
    }

    suspend fun saveFoldersJson(json: String) {
        try {
            context.dataStore.edit { it[KEY_FOLDERS_JSON] = json }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save folders json", e)
        }
    }

    suspend fun getFoldersJson(): String? = try {
        context.dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    Log.e(TAG, "Error reading folders from DataStore", exception)
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .first()[KEY_FOLDERS_JSON]
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get folders json", e)
        null
    }

    suspend fun saveUsageStatsJson(json: String) {
        try {
            context.dataStore.edit { it[KEY_USAGE_STATS_JSON] = json }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save usage stats json", e)
        }
    }

    suspend fun getUsageStatsJson(): String? = try {
        context.dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    Log.e(TAG, "Error reading usage stats from DataStore", exception)
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .first()[KEY_USAGE_STATS_JSON]
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get usage stats json", e)
        null
    }

    suspend fun savePwasJson(json: String) {
        try {
            context.dataStore.edit { it[KEY_PWAS_JSON] = json }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save pwas json", e)
        }
    }

    suspend fun getPwasJson(): String? = try {
        context.dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    Log.e(TAG, "Error reading pwas from DataStore", exception)
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .first()[KEY_PWAS_JSON]
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get pwas json", e)
        null
    }

    suspend fun isPwasSeeded(): Boolean = try {
        context.dataStore.data
            .catch { exception ->
                if (exception is IOException) emit(emptyPreferences()) else throw exception
            }
            .first()[KEY_PWAS_SEEDED] ?: false
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read pwas seeded status", e)
        false
    }

    suspend fun setPwasSeeded(seeded: Boolean) {
        try {
            context.dataStore.edit { it[KEY_PWAS_SEEDED] = seeded }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set pwas seeded", e)
        }
    }

    suspend fun getLastBackupTime(): Long = try {
        context.dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    Log.e(TAG, "Error reading last backup time from DataStore", exception)
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .first()[KEY_LAST_BACKUP_TIME] ?: 0L
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get last backup time", e)
        0L
    }

    suspend fun setLastBackupTime(time: Long) {
        try {
            context.dataStore.edit { it[KEY_LAST_BACKUP_TIME] = time }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set last backup time", e)
        }
    }

    suspend fun saveAllSettings(settings: LauncherSettings) {
        try {
            context.dataStore.edit { prefs ->
                prefs[KEY_SHOW_APP_DRAWER] = settings.showAppDrawer
                prefs[KEY_GRID_SIZE] = settings.gridSize.serialize()
                prefs[KEY_BUTTON_COLOR_MODE] = settings.buttonColorMode
                prefs[KEY_SHOW_TARS_HEADER] = settings.showTarsHeader
                prefs[KEY_SHOW_CLOCK] = settings.showClock
                if (settings.selectedIconPack.isNullOrBlank()) {
                    prefs.remove(KEY_SELECTED_ICON_PACK)
                } else {
                    prefs[KEY_SELECTED_ICON_PACK] = settings.selectedIconPack
                }
                prefs[KEY_APP_DRAWER_ICON_SIZE] = settings.appDrawerIconSize
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save all settings", e)
        }
    }

    /**
     * Migrates legacy SharedPreferences to Jetpack DataStore safely if not already migrated.
     */
    suspend fun migrateFromLegacyPrefsIfNeeded() {
        try {
            val isMigrated = context.dataStore.data.first()[KEY_MIGRATED_FROM_SP] ?: false
            if (isMigrated) return

            val legacyPrefs = context.getSharedPreferences("tarslauncher_secure_prefs", Context.MODE_PRIVATE)
            if (legacyPrefs.all.isEmpty()) {
                context.dataStore.edit { it[KEY_MIGRATED_FROM_SP] = true }
                return
            }

            context.dataStore.edit { prefs ->
                if (legacyPrefs.contains("show_app_drawer")) {
                    prefs[KEY_SHOW_APP_DRAWER] = legacyPrefs.getBoolean("show_app_drawer", true)
                }
                if (legacyPrefs.contains("grid_size")) {
                    prefs[KEY_GRID_SIZE] = legacyPrefs.getString("grid_size", "4x3") ?: "4x3"
                }
                if (legacyPrefs.contains("button_color_mode")) {
                    prefs[KEY_BUTTON_COLOR_MODE] = legacyPrefs.getString("button_color_mode", "black") ?: "black"
                }
                if (legacyPrefs.contains("show_tars_header")) {
                    prefs[KEY_SHOW_TARS_HEADER] = legacyPrefs.getBoolean("show_tars_header", true)
                }
                if (legacyPrefs.contains("show_clock")) {
                    prefs[KEY_SHOW_CLOCK] = legacyPrefs.getBoolean("show_clock", true)
                }
                if (legacyPrefs.contains("icon_pack")) {
                    val pack = legacyPrefs.getString("icon_pack", null)
                    if (!pack.isNullOrBlank()) {
                        prefs[KEY_SELECTED_ICON_PACK] = pack
                    }
                }
                if (legacyPrefs.contains("app_drawer_icon_size")) {
                    prefs[KEY_APP_DRAWER_ICON_SIZE] = legacyPrefs.getInt("app_drawer_icon_size", 32)
                }
                if (legacyPrefs.contains("folders")) {
                    val folders = legacyPrefs.getString("folders", null)
                    if (!folders.isNullOrBlank()) {
                        prefs[KEY_FOLDERS_JSON] = folders
                    }
                }
                if (legacyPrefs.contains("app_usage_stats")) {
                    val usage = legacyPrefs.getString("app_usage_stats", null)
                    if (!usage.isNullOrBlank()) {
                        prefs[KEY_USAGE_STATS_JSON] = usage
                    }
                }
                prefs[KEY_MIGRATED_FROM_SP] = true
            }
            Log.i(TAG, "Successfully migrated legacy SharedPreferences to DataStore")
        } catch (e: Exception) {
            Log.w(TAG, "SharedPreferences migration skipped or failed: ${e.message}")
        }
    }
}
