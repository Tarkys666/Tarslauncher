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
        val KEY_DUAL_APP_DRAWER = booleanPreferencesKey("dual_app_drawer")
        val KEY_APP_DRAWER_ROWS = intPreferencesKey("app_drawer_rows")
        val KEY_GRID_SIZE = stringPreferencesKey("grid_size")
        val KEY_BUTTON_COLOR_MODE = stringPreferencesKey("button_color_mode")
        val KEY_SHOW_TARS_HEADER = booleanPreferencesKey("show_tars_header")
        val KEY_SHOW_CLOCK = booleanPreferencesKey("show_clock")
        val KEY_SELECTED_ICON_PACK = stringPreferencesKey("selected_icon_pack")
        val KEY_APP_DRAWER_ICON_SIZE = intPreferencesKey("app_drawer_icon_size")
        val KEY_FOLDERS_JSON = stringPreferencesKey("folders_json")
        val KEY_USAGE_STATS_JSON = stringPreferencesKey("usage_stats_json")
        val KEY_PWAS_JSON = stringPreferencesKey("pwas_json")
        val KEY_SHOW_ASCII_DIAMOND = booleanPreferencesKey("show_ascii_diamond")
        val KEY_ASCII_DIAMOND_SCALE = intPreferencesKey("ascii_diamond_scale")
        val KEY_ASCII_DIAMOND_OFFSET_Y = intPreferencesKey("ascii_diamond_offset_y")
        val KEY_ASCII_DIAMOND_FPS = intPreferencesKey("ascii_diamond_fps")
        val KEY_ASCII_DIAMOND_SPEED = intPreferencesKey("ascii_diamond_speed")
        val KEY_SHOW_FOLDER_GRID = booleanPreferencesKey("show_folder_grid")
        val KEY_APP_DRAWER_OFFSET_Y = intPreferencesKey("app_drawer_offset_y")
        val KEY_MIGRATED_FROM_SP = booleanPreferencesKey("migrated_from_sp")
    }

    private val safeData: Flow<Preferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading from DataStore", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    private suspend fun <T> getPreference(key: Preferences.Key<T>, defaultValue: T): T = try {
        safeData.first()[key] ?: defaultValue
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read preference ${key.name}", e)
        defaultValue
    }

    private suspend fun <T> getPreference(key: Preferences.Key<T>): T? = try {
        safeData.first()[key]
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read preference ${key.name}", e)
        null
    }

    private suspend fun <T> setPreference(key: Preferences.Key<T>, value: T?) {
        try {
            context.dataStore.edit { prefs ->
                if (value == null) prefs.remove(key) else prefs[key] = value
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update ${key.name}", e)
        }
    }

    val settingsFlow: Flow<LauncherSettings> = safeData
        .map { preferences ->
            val savedRows = preferences[KEY_APP_DRAWER_ROWS] ?: (if (preferences[KEY_DUAL_APP_DRAWER] == true) 2 else 1)
            LauncherSettings(
                showAppDrawer = preferences[KEY_SHOW_APP_DRAWER] ?: true,
                dualAppDrawer = savedRows > 1,
                appDrawerRows = savedRows.coerceIn(1, 20),
                gridSize = GridSize.fromString(preferences[KEY_GRID_SIZE]),
                buttonColorMode = preferences[KEY_BUTTON_COLOR_MODE] ?: "transparent",
                showTarsHeader = preferences[KEY_SHOW_TARS_HEADER] ?: true,
                showClock = preferences[KEY_SHOW_CLOCK] ?: true,
                selectedIconPack = preferences[KEY_SELECTED_ICON_PACK],
                appDrawerIconSize = preferences[KEY_APP_DRAWER_ICON_SIZE] ?: 32,
                showAsciiDiamond = preferences[KEY_SHOW_ASCII_DIAMOND] ?: false,
                asciiDiamondScale = preferences[KEY_ASCII_DIAMOND_SCALE] ?: 100,
                asciiDiamondOffsetY = preferences[KEY_ASCII_DIAMOND_OFFSET_Y] ?: 0,
                asciiDiamondFps = preferences[KEY_ASCII_DIAMOND_FPS] ?: 60,
                asciiDiamondSpeed = preferences[KEY_ASCII_DIAMOND_SPEED] ?: 100,
                showFolderGrid = preferences[KEY_SHOW_FOLDER_GRID] ?: true,
                appDrawerOffsetY = preferences[KEY_APP_DRAWER_OFFSET_Y] ?: 24
            )
        }
        .distinctUntilChanged()

    val pwasJsonFlow: Flow<String?> = safeData
        .map { preferences -> preferences[KEY_PWAS_JSON] }
        .distinctUntilChanged()

    suspend fun updateShowAppDrawer(show: Boolean) = setPreference(KEY_SHOW_APP_DRAWER, show)

    suspend fun updateDualAppDrawer(enabled: Boolean) {
        setPreference(KEY_DUAL_APP_DRAWER, enabled)
        setPreference(KEY_APP_DRAWER_ROWS, if (enabled) 2 else 1)
    }

    suspend fun updateAppDrawerRows(rows: Int) {
        val clamped = rows.coerceIn(1, 20)
        setPreference(KEY_APP_DRAWER_ROWS, clamped)
        setPreference(KEY_DUAL_APP_DRAWER, clamped > 1)
    }

    suspend fun updateGridSize(gridSize: GridSize) = setPreference(KEY_GRID_SIZE, gridSize.serialize())

    suspend fun updateButtonColorMode(mode: String) = setPreference(KEY_BUTTON_COLOR_MODE, mode)

    suspend fun updateShowTarsHeader(show: Boolean) = setPreference(KEY_SHOW_TARS_HEADER, show)

    suspend fun updateShowClock(show: Boolean) = setPreference(KEY_SHOW_CLOCK, show)
    suspend fun updateShowAsciiDiamond(enabled: Boolean) = setPreference(KEY_SHOW_ASCII_DIAMOND, enabled)
    suspend fun updateAsciiDiamondScale(scale: Int) = setPreference(KEY_ASCII_DIAMOND_SCALE, scale)
    suspend fun updateAsciiDiamondOffsetY(offsetY: Int) = setPreference(KEY_ASCII_DIAMOND_OFFSET_Y, offsetY)
    suspend fun updateAsciiDiamondFps(fps: Int) = setPreference(KEY_ASCII_DIAMOND_FPS, fps)
    suspend fun updateAsciiDiamondSpeed(speed: Int) = setPreference(KEY_ASCII_DIAMOND_SPEED, speed)
    suspend fun updateShowFolderGrid(show: Boolean) = setPreference(KEY_SHOW_FOLDER_GRID, show)
    suspend fun updateAppDrawerOffsetY(offsetY: Int) = setPreference(KEY_APP_DRAWER_OFFSET_Y, offsetY)

    suspend fun updateSelectedIconPack(iconPackPackage: String?) =
        setPreference(KEY_SELECTED_ICON_PACK, iconPackPackage.takeUnless { it.isNullOrBlank() })

    suspend fun updateAppDrawerIconSize(size: Int) = setPreference(KEY_APP_DRAWER_ICON_SIZE, size)

    suspend fun saveFoldersJson(json: String) = setPreference(KEY_FOLDERS_JSON, json)

    suspend fun getFoldersJson(): String? = getPreference(KEY_FOLDERS_JSON)

    suspend fun saveUsageStatsJson(json: String) = setPreference(KEY_USAGE_STATS_JSON, json)

    suspend fun getUsageStatsJson(): String? = getPreference(KEY_USAGE_STATS_JSON)

    suspend fun savePwasJson(json: String) = setPreference(KEY_PWAS_JSON, json)

    suspend fun getPwasJson(): String? = getPreference(KEY_PWAS_JSON)

    suspend fun saveAllSettings(settings: LauncherSettings) {
        try {
            context.dataStore.edit { prefs ->
                prefs[KEY_SHOW_APP_DRAWER] = settings.showAppDrawer
                prefs[KEY_DUAL_APP_DRAWER] = settings.appDrawerRows > 1
                prefs[KEY_APP_DRAWER_ROWS] = settings.appDrawerRows
                prefs[KEY_GRID_SIZE] = settings.gridSize.serialize()
                prefs[KEY_BUTTON_COLOR_MODE] = settings.buttonColorMode
                prefs[KEY_SHOW_TARS_HEADER] = settings.showTarsHeader
                prefs[KEY_SHOW_CLOCK] = settings.showClock
                prefs[KEY_SHOW_ASCII_DIAMOND] = settings.showAsciiDiamond
                prefs[KEY_ASCII_DIAMOND_SCALE] = settings.asciiDiamondScale
                prefs[KEY_ASCII_DIAMOND_OFFSET_Y] = settings.asciiDiamondOffsetY
                prefs[KEY_ASCII_DIAMOND_FPS] = settings.asciiDiamondFps
                prefs[KEY_ASCII_DIAMOND_SPEED] = settings.asciiDiamondSpeed
                prefs[KEY_SHOW_FOLDER_GRID] = settings.showFolderGrid
                prefs[KEY_APP_DRAWER_OFFSET_Y] = settings.appDrawerOffsetY
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
            val isMigrated = safeData.first()[KEY_MIGRATED_FROM_SP] ?: false
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
