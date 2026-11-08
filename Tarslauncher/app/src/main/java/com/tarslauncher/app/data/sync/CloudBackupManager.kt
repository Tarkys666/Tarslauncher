package com.tarslauncher.app.data.sync

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tarslauncher.app.data.datastore.LauncherDataStore
import com.tarslauncher.app.data.model.LauncherSettings
import com.tarslauncher.app.utils.PwaLauncherHelper
import com.tarslauncher.app.utils.SecurityHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
            val session = authManager.requireSession()
            val user = session.user

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
                addProperty("showWidgets", currentSettings.showWidgets)
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
                accessToken = session.accessToken
            ).getOrThrow()

            dataStore.setLastBackupTime(now)
            Log.i(TAG, "Backup uploaded to Supabase for uid: ${user.uid}")
            Result.success(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Supabase backup failed", e)
            Result.failure(e)
        }
    }

    suspend fun restoreLayout(): Result<LauncherSettings> = withContext(Dispatchers.IO) {
        try {
            val session = authManager.requireSession()
            val user = session.user

            val encodedUid = SupabaseConfig.urlEncode("eq.${user.uid}")
            val responseText = SupabaseConfig.restRequest(
                pathAndQuery = "user_backups?user_id=$encodedUid&select=*&limit=1",
                method = "GET",
                accessToken = session.accessToken
            ).getOrThrow()

            val arr = JsonParser.parseString(responseText).asJsonArray
            if (arr.size() == 0) {
                return@withContext Result.failure(Exception("Supabase bulutunda bu hesaba ait kayıtlı bir yedek bulunamadı."))
            }

            val backup = BackupPayload.fromRow(arr[0].asJsonObject)
            val pwasJson = backup.pwasJson?.let { sanitizePwasJson(it) }
            dataStore.saveAllSettings(
                settings = backup.settings,
                foldersJson = backup.foldersJson,
                pwasJson = pwasJson,
                usageStatsJson = backup.usageStatsJson,
                lastBackupTime = backup.updatedAt
            )

            Log.i(TAG, "Layout restored from Supabase for uid: ${user.uid}")
            Result.success(backup.settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Supabase restore failed", e)
            Result.failure(e)
        }
    }

    private fun sanitizePwasJson(json: String): String {
        val array = JsonParser.parseString(json).asJsonArray
        for (item in array) {
            val obj = item.asJsonObject
            val rawUrl = obj.get("url")?.takeUnless { it.isJsonNull }?.asString
            if (!rawUrl.isNullOrBlank()) {
                val cleanUrl = PwaLauncherHelper.sanitizeWebUrl(rawUrl) ?: error("Yedekteki web adresi geçersiz.")
                obj.addProperty("url", cleanUrl)
            } else {
                val shortcutPackage = obj.get("shortcutPackage")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val shortcutId = obj.get("shortcutId")?.takeUnless { it.isJsonNull }?.asString
                require(SecurityHelper.validatePackageName(shortcutPackage) && !shortcutId.isNullOrBlank()) {
                    "Yedekteki kısayol geçersiz."
                }
                obj.addProperty("url", "")
            }
        }
        return array.toString()
    }
}
