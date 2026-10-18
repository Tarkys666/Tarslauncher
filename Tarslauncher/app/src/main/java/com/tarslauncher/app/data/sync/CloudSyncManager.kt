package com.tarslauncher.app.data.sync

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tarslauncher.app.data.datastore.LauncherDataStore
import com.tarslauncher.app.data.model.GridSize
import com.tarslauncher.app.data.model.LauncherSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloud backup & restore manager powered by Supabase (`public.user_backups` & `public.agent_secrets`).
 */
class CloudSyncManager(
    private val context: Context,
    private val dataStore: LauncherDataStore = LauncherDataStore(context),
    private val authManager: GoogleAuthManager = GoogleAuthManager(context)
) {

    companion object {
        private const val TAG = "CloudSyncManager"
    }

    suspend fun getCurrentUser(): TarsAuthUser? = withContext(Dispatchers.IO) {
        authManager.currentUser
    }

    suspend fun ensureAuthenticated(): Result<TarsAuthUser> = withContext(Dispatchers.IO) {
        val uidResult = authManager.ensureSessionUid()
        val user = authManager.currentUser
        if (uidResult.isSuccess && user != null) {
            Result.success(user)
        } else {
            Result.failure(uidResult.exceptionOrNull() ?: Exception("Kullanıcı oturumu başlatılamadı."))
        }
    }

    suspend fun backupLayout(currentSettings: LauncherSettings): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val authResult = ensureAuthenticated()
            val user = authResult.getOrNull()
                ?: return@withContext Result.failure(authResult.exceptionOrNull() ?: Exception("Giriş yapılamadı."))

            val now = System.currentTimeMillis()
            val foldersJson = dataStore.getFoldersJson() ?: "[]"
            val pwasJson = dataStore.getPwasJson() ?: "[]"
            val usageStatsJson = dataStore.getUsageStatsJson() ?: "[]"

            val settingsObj = JsonObject().apply {
                addProperty("showAppDrawer", currentSettings.showAppDrawer)
                addProperty("gridSize", currentSettings.gridSize.serialize())
                addProperty("buttonColorMode", currentSettings.buttonColorMode)
                addProperty("showTarsHeader", currentSettings.showTarsHeader)
                addProperty("showClock", currentSettings.showClock)
                if (currentSettings.selectedIconPack != null) {
                    addProperty("selectedIconPack", currentSettings.selectedIconPack)
                }
                addProperty("appDrawerIconSize", currentSettings.appDrawerIconSize)
            }

            val payloadObj = JsonObject().apply {
                addProperty("updatedAt", now)
                addProperty("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
                addProperty("appVersion", com.tarslauncher.app.BuildConfig.VERSION_NAME)
                addProperty("foldersJson", foldersJson)
                addProperty("pwasJson", pwasJson)
                addProperty("usageStatsJson", usageStatsJson)
                add("settings", settingsObj)
            }

            val rowObj = JsonObject().apply {
                addProperty("user_id", user.uid)
                add("payload", payloadObj)
                addProperty("updated_at", now)
            }

            SupabaseConfig.restRequest(
                pathAndQuery = "user_backups?on_conflict=user_id",
                method = "POST",
                bodyJson = rowObj.toString(),
                preferHeader = "resolution=merge-duplicates,return=minimal"
            ).getOrThrow()

            dataStore.setLastBackupTime(now)
            Log.i(TAG, "Backup uploaded to Supabase for uid: ${user.uid}")
            Result.success(now)
        } catch (e: Exception) {
            Log.e(TAG, "Supabase backup failed", e)
            Result.failure(e)
        }
    }

    suspend fun restoreLayout(): Result<LauncherSettings> = withContext(Dispatchers.IO) {
        try {
            val authResult = ensureAuthenticated()
            val user = authResult.getOrNull()
                ?: return@withContext Result.failure(authResult.exceptionOrNull() ?: Exception("Giriş yapılamadı."))

            val encodedUid = SupabaseConfig.urlEncode("eq.${user.uid}")
            val responseText = SupabaseConfig.restRequest(
                pathAndQuery = "user_backups?user_id=$encodedUid&select=*&limit=1",
                method = "GET"
            ).getOrThrow()

            val arr = JsonParser.parseString(responseText).asJsonArray
            if (arr.size() == 0) {
                return@withContext Result.failure(Exception("Supabase bulutunda bu hesaba ait kayıtlı bir yedek bulunamadı."))
            }

            val row = arr[0].asJsonObject
            val payload = row.getAsJsonObject("payload")
                ?: return@withContext Result.failure(Exception("Yedek verisi okunamadı."))

            val foldersJson = payload.get("foldersJson")?.takeIf { !it.isJsonNull }?.asString
            if (!foldersJson.isNullOrBlank()) {
                dataStore.saveFoldersJson(foldersJson)
            }

            val pwasJson = payload.get("pwasJson")?.takeIf { !it.isJsonNull }?.asString
            if (!pwasJson.isNullOrBlank()) {
                dataStore.savePwasJson(pwasJson)
            }

            val usageStatsJson = payload.get("usageStatsJson")?.takeIf { !it.isJsonNull }?.asString
            if (!usageStatsJson.isNullOrBlank()) {
                dataStore.saveUsageStatsJson(usageStatsJson)
            }

            val updatedAt = row.get("updated_at")?.takeIf { !it.isJsonNull }?.asLong
                ?: payload.get("updatedAt")?.takeIf { !it.isJsonNull }?.asLong
                ?: System.currentTimeMillis()
            dataStore.setLastBackupTime(updatedAt)

            val settingsObj = payload.getAsJsonObject("settings")
            val restoredSettings = if (settingsObj != null) {
                val settings = LauncherSettings(
                    showAppDrawer = settingsObj.get("showAppDrawer")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                    gridSize = GridSize.fromString(settingsObj.get("gridSize")?.takeIf { !it.isJsonNull }?.asString),
                    buttonColorMode = settingsObj.get("buttonColorMode")?.takeIf { !it.isJsonNull }?.asString ?: "black",
                    showTarsHeader = settingsObj.get("showTarsHeader")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                    showClock = settingsObj.get("showClock")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                    selectedIconPack = settingsObj.get("selectedIconPack")?.takeIf { !it.isJsonNull }?.asString,
                    appDrawerIconSize = settingsObj.get("appDrawerIconSize")?.takeIf { !it.isJsonNull }?.asInt ?: 32
                )
                dataStore.saveAllSettings(settings)
                settings
            } else {
                LauncherSettings()
            }

            Log.i(TAG, "Layout restored from Supabase for uid: ${user.uid}")
            Result.success(restoredSettings)
        } catch (e: Exception) {
            Log.e(TAG, "Supabase restore failed", e)
            Result.failure(e)
        }
    }
}
