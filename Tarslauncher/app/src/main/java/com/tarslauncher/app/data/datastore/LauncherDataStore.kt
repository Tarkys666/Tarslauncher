package com.tarslauncher.app.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
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

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tars_settings")

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

    suspend fun updateShowAppDrawer(show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_APP_DRAWER] = show }
    }

    suspend fun updateGridSize(gridSize: GridSize) {
        context.dataStore.edit { it[KEY_GRID_SIZE] = gridSize.serialize() }
    }

    suspend fun updateButtonColorMode(mode: String) {
        context.dataStore.edit { it[KEY_BUTTON_COLOR_MODE] = mode }
    }

    suspend fun updateShowTarsHeader(show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_TARS_HEADER] = show }
    }

    suspend fun updateShowClock(show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_CLOCK] = show }
    }

    suspend fun updateSelectedIconPack(iconPackPackage: String?) {
        context.dataStore.edit {
            if (iconPackPackage.isNullOrBlank()) {
                it.remove(KEY_SELECTED_ICON_PACK)
            } else {
                it[KEY_SELECTED_ICON_PACK] = iconPackPackage
            }
        }
    }

    suspend fun updateAppDrawerIconSize(size: Int) {
        context.dataStore.edit { it[KEY_APP_DRAWER_ICON_SIZE] = size }
    }

    suspend fun saveFoldersJson(json: String) {
        context.dataStore.edit { it[KEY_FOLDERS_JSON] = json }
    }

    suspend fun getFoldersJson(): String? {
        return context.dataStore.data.first()[KEY_FOLDERS_JSON]
    }

    suspend fun saveUsageStatsJson(json: String) {
        context.dataStore.edit { it[KEY_USAGE_STATS_JSON] = json }
    }

    suspend fun getUsageStatsJson(): String? {
        return context.dataStore.data.first()[KEY_USAGE_STATS_JSON]
    }

    suspend fun savePwasJson(json: String) {
        context.dataStore.edit { it[KEY_PWAS_JSON] = json }
    }

    suspend fun getPwasJson(): String? {
        return context.dataStore.data.first()[KEY_PWAS_JSON]
    }

    suspend fun isPwasSeeded(): Boolean {
        return context.dataStore.data.first()[KEY_PWAS_SEEDED] ?: false
    }

    suspend fun setPwasSeeded(seeded: Boolean) {
        context.dataStore.edit { it[KEY_PWAS_SEEDED] = seeded }
    }

    suspend fun getLastBackupTime(): Long {
        return context.dataStore.data.first()[KEY_LAST_BACKUP_TIME] ?: 0L
    }

    suspend fun setLastBackupTime(time: Long) {
        context.dataStore.edit { it[KEY_LAST_BACKUP_TIME] = time }
    }

    suspend fun saveAllSettings(settings: LauncherSettings) {
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
