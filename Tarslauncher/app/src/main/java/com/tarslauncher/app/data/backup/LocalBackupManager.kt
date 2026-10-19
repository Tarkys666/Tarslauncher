package com.tarslauncher.app.data.backup

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.tarslauncher.app.data.datastore.LauncherDataStore
import com.tarslauncher.app.data.model.GridSize
import com.tarslauncher.app.data.model.LauncherSettings
import com.tarslauncher.app.data.model.PwaInfo
import com.tarslauncher.app.utils.PwaLauncherHelper
import com.tarslauncher.app.utils.SecurityHelper
import com.tarslauncher.app.utils.readTextWithLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Completely local backup: layout, settings, and PWAs are written to / read from
 * a single JSON file via Storage Access Framework (SAF).
 * No network, account, or external permissions required.
 */
class LocalBackupManager(
    context: Context,
    private val dataStore: LauncherDataStore = LauncherDataStore(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private val gson = Gson()

    suspend fun export(target: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val s = dataStore.settingsFlow.first()
            val settings = JsonObject().apply {
                addProperty("showAppDrawer", s.showAppDrawer)
                addProperty("gridSize", s.gridSize.serialize())
                addProperty("buttonColorMode", s.buttonColorMode)
                addProperty("showTarsHeader", s.showTarsHeader)
                addProperty("showClock", s.showClock)
                s.selectedIconPack?.let { addProperty("selectedIconPack", it) }
                addProperty("appDrawerIconSize", s.appDrawerIconSize)
            }
            val root = JsonObject().apply {
                addProperty("format", FORMAT)
                addProperty("updatedAt", now)
                addProperty("appVersion", com.tarslauncher.app.BuildConfig.VERSION_NAME)
                addProperty("foldersJson", dataStore.getFoldersJson() ?: "[]")
                addProperty("pwasJson", dataStore.getPwasJson() ?: "[]")
                addProperty("usageStatsJson", dataStore.getUsageStatsJson() ?: "[]")
                add("settings", settings)
            }
            val out = appContext.contentResolver.openOutputStream(target, "w")
                ?: return@withContext Result.failure(Exception("Unable to open destination file."))
            out.bufferedWriter(Charsets.UTF_8).use { it.write(gson.toJson(root)) }
            dataStore.setLastBackupTime(now)
            Result.success(now)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun import(source: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val text = appContext.contentResolver.openInputStream(source)?.use { input ->
                input.readTextWithLimit(MAX_BYTES)
            } ?: return@withContext Result.failure(Exception("Unable to open backup file."))

            val root = JsonParser.parseString(text).asJsonObject
            if (root.get("format")?.asString != FORMAT) {
                return@withContext Result.failure(Exception("Invalid backup format."))
            }

            root.stringOrNull("foldersJson")?.takeIf { isJsonArray(it) }?.let { dataStore.saveFoldersJson(it) }
            root.stringOrNull("usageStatsJson")?.takeIf { isJsonArray(it) }?.let { dataStore.saveUsageStatsJson(it) }
            root.stringOrNull("pwasJson")?.let { raw ->
                val pwas: List<PwaInfo> = gson.fromJson(raw, object : TypeToken<List<PwaInfo>>() {}.type) ?: emptyList()
                val pm = appContext.packageManager
                val safe = pwas.mapNotNull { pwa ->
                    val cleanUrl = pwa.url?.let { PwaLauncherHelper.sanitizeWebUrl(it) }
                    val isValidPackage = !pwa.shortcutPackage.isNullOrBlank() &&
                        SecurityHelper.validatePackageName(pwa.shortcutPackage) &&
                        runCatching { pm.getPackageInfo(pwa.shortcutPackage, 0) }.isSuccess

                    val safePackage = if (isValidPackage) pwa.shortcutPackage else null
                    val safeShortcutId = if (isValidPackage) pwa.shortcutId else null

                    if (cleanUrl != null || (safePackage != null && !safeShortcutId.isNullOrBlank())) {
                        pwa.copy(
                            url = cleanUrl ?: "",
                            shortcutPackage = safePackage,
                            shortcutId = safeShortcutId
                        )
                    } else {
                        null
                    }
                }.take(MAX_PWAS)
                dataStore.savePwasJson(gson.toJson(safe))
            }

            root.getAsJsonObject("settings")?.let { o ->
                val iconSize = (o.get("appDrawerIconSize")?.takeIf { !it.isJsonNull }?.asInt ?: 32).coerceIn(20, 36)
                val mode = o.stringOrNull("buttonColorMode")?.takeIf { it == "black" || it == "gray" } ?: "black"
                dataStore.saveAllSettings(
                    LauncherSettings(
                        showAppDrawer = o.get("showAppDrawer")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                        gridSize = GridSize.fromString(o.stringOrNull("gridSize")),
                        buttonColorMode = mode,
                        showTarsHeader = o.get("showTarsHeader")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                        showClock = o.get("showClock")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                        selectedIconPack = o.stringOrNull("selectedIconPack"),
                        appDrawerIconSize = iconSize
                    )
                )
            }

            val at = root.get("updatedAt")?.takeIf { !it.isJsonNull }?.asLong ?: System.currentTimeMillis()
            dataStore.setLastBackupTime(at)
            Result.success(at)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (t is OutOfMemoryError || t is StackOverflowError) {
                Result.failure(Exception("Backup file could not be read or is too large/complex."))
            } else if (t is Exception) {
                Result.failure(t)
            } else {
                Result.failure(Exception(t))
            }
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

    private fun isJsonArray(raw: String): Boolean =
        try { JsonParser.parseString(raw) is JsonArray } catch (e: Exception) { false }

    companion object {
        const val FORMAT = "tars-launcher-backup-v2"
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_PWAS = 200
    }
}
