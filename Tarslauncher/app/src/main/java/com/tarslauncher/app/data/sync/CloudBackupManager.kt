package com.tarslauncher.app.data.sync

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tarslauncher.app.data.datastore.LauncherDataStore
import com.tarslauncher.app.data.model.GridSize
import com.tarslauncher.app.data.model.LauncherSettings
import com.tarslauncher.app.utils.PwaLauncherHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloud backup & restore manager powered by Supabase (`public.user_backups`).
 * Backs up all launcher configurations, folder structures, installed web apps (PWAs),
 * and usage statistics on a per-user basis with Row Level Security (RLS) isolation.
 *
 * Enforces payload size guards (<512 KB) and PWA URL sanitization to protect against injection/tampering.
 */
class CloudBackupManager(
    private val context: Context,
    private val dataStore: LauncherDataStore = LauncherDataStore(context),
    private val authManager: GoogleAuthManager = GoogleAuthManager(context)
) {

    companion object {
        private const val TAG = "CloudBackupManager"
        private const val MAX_BACKUP_BYTES = 512 * 1024 // 512 KB payload guard
    }

    suspend fun getCurrentUser(): TarsAuthUser? = withContext(Dispatchers.IO) {
        authManager.currentUser
    }

    suspend fun backupLayout(currentSettings: LauncherSettings): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val user = authManager.currentUser
            if (user == null || user.isAnonymous || user.uid.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Buluta yedekleme için Google ile giriş yapmalısınız."))
            }

            val now = System.currentTimeMillis()
            val foldersJson = dataStore.getFoldersJson() ?: "[]"
            val pwasJson = dataStore.getPwasJson() ?: "[]"
            val usageStatsJson = dataStore.getUsageStatsJson() ?: "[]"

            val settingsObj = JsonObject().apply {
                addProperty("showAppDrawer", currentSettings.showAppDrawer)
                addProperty("dualAppDrawer", currentSettings.dualAppDrawer)
                addProperty("appDrawerRows", currentSettings.appDrawerRows)
                addProperty("gridSize", currentSettings.gridSize.serialize())
                addProperty("buttonColorMode", currentSettings.buttonColorMode)
                addProperty("showTarsHeader", currentSettings.showTarsHeader)
                addProperty("showClock", currentSettings.showClock)
                if (currentSettings.selectedIconPack != null) {
                    addProperty("selectedIconPack", currentSettings.selectedIconPack)
                }
                addProperty("appDrawerIconSize", currentSettings.appDrawerIconSize)
                addProperty("showAsciiDiamond", currentSettings.showAsciiDiamond)
                addProperty("asciiDiamondScale", currentSettings.asciiDiamondScale)
                addProperty("asciiDiamondOffsetY", currentSettings.asciiDiamondOffsetY)
                addProperty("asciiDiamondFps", currentSettings.asciiDiamondFps)
                addProperty("asciiDiamondSpeed", currentSettings.asciiDiamondSpeed)
                addProperty("showFolderGrid", currentSettings.showFolderGrid)
                addProperty("appDrawerOffsetY", currentSettings.appDrawerOffsetY)
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

            val rowString = rowObj.toString()
            val rowBytes = rowString.toByteArray(Charsets.UTF_8)
            if (rowBytes.size > MAX_BACKUP_BYTES) {
                return@withContext Result.failure(IllegalStateException("Yedek boyutu 512 KB sınırını aşıyor."))
            }

            SupabaseConfig.restRequest(
                pathAndQuery = "user_backups?on_conflict=user_id",
                method = "POST",
                bodyJson = rowString,
                preferHeader = "resolution=merge-duplicates,return=minimal",
                userIdHeader = user.uid
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
            val user = authManager.currentUser
            if (user == null || user.isAnonymous || user.uid.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Buluttan geri yüklemek için Google ile giriş yapmalısınız."))
            }

            val encodedUid = SupabaseConfig.urlEncode("eq.${user.uid}")
            val responseText = SupabaseConfig.restRequest(
                pathAndQuery = "user_backups?user_id=$encodedUid&select=*&limit=1",
                method = "GET",
                userIdHeader = user.uid
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
                val sanitizedPwas = sanitizePwasJson(pwasJson)
                dataStore.savePwasJson(sanitizedPwas)
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
                    dualAppDrawer = settingsObj.get("dualAppDrawer")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    appDrawerRows = settingsObj.get("appDrawerRows")?.takeIf { !it.isJsonNull }?.asInt ?: 1,
                    gridSize = GridSize.fromString(settingsObj.get("gridSize")?.takeIf { !it.isJsonNull }?.asString),
                    buttonColorMode = settingsObj.get("buttonColorMode")?.takeIf { !it.isJsonNull }?.asString ?: "transparent",
                    showTarsHeader = settingsObj.get("showTarsHeader")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                    showClock = settingsObj.get("showClock")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                    selectedIconPack = settingsObj.get("selectedIconPack")?.takeIf { !it.isJsonNull }?.asString,
                    appDrawerIconSize = settingsObj.get("appDrawerIconSize")?.takeIf { !it.isJsonNull }?.asInt ?: 32,
                    showAsciiDiamond = settingsObj.get("showAsciiDiamond")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    asciiDiamondScale = settingsObj.get("asciiDiamondScale")?.takeIf { !it.isJsonNull }?.asInt ?: 100,
                    asciiDiamondOffsetY = settingsObj.get("asciiDiamondOffsetY")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                    asciiDiamondFps = settingsObj.get("asciiDiamondFps")?.takeIf { !it.isJsonNull }?.asInt ?: 60,
                    asciiDiamondSpeed = settingsObj.get("asciiDiamondSpeed")?.takeIf { !it.isJsonNull }?.asInt ?: 100,
                    showFolderGrid = settingsObj.get("showFolderGrid")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                    appDrawerOffsetY = settingsObj.get("appDrawerOffsetY")?.takeIf { !it.isJsonNull }?.asInt ?: 24
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

    private fun sanitizePwasJson(json: String): String {
        return try {
            val element = JsonParser.parseString(json)
            if (!element.isJsonArray) return "[]"
            val array = element.asJsonArray
            val safeArray = JsonArray()
            for (item in array) {
                if (item.isJsonObject) {
                    val obj = item.asJsonObject
                    val rawUrl = obj.get("url")?.takeIf { !it.isJsonNull }?.asString
                    if (!rawUrl.isNullOrBlank()) {
                        val cleanUrl = PwaLauncherHelper.sanitizeWebUrl(rawUrl)
                        if (cleanUrl != null && (cleanUrl.startsWith("http://", ignoreCase = true) || cleanUrl.startsWith("https://", ignoreCase = true))) {
                            obj.addProperty("url", cleanUrl)
                            safeArray.add(obj)
                        }
                    }
                }
            }
            safeArray.toString()
        } catch (_: Exception) {
            "[]"
        }
    }
}
