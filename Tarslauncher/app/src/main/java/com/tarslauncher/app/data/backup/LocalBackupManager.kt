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
import com.tarslauncher.app.data.model.WorkspaceItem
import com.tarslauncher.app.data.notes.LocalNotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Tamamen yerel yedekleme: düzen, ayarlar, PWA'lar ve notlar tek bir JSON dosyasına
 * (Storage Access Framework ile kullanıcının seçtiği konuma) yazılır / oradan okunur.
 * Ağ erişimi, hesap veya anahtar yoktur. İçe aktarma sıkı doğrulamadan geçer.
 */
class LocalBackupManager(
    context: Context,
    private val dataStore: LauncherDataStore = LauncherDataStore(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private val notes = LocalNotesRepository.get(appContext)
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
                add("notes", gson.toJsonTree(notes.snapshot()))
            }
            val out = appContext.contentResolver.openOutputStream(target, "wt")
                ?: return@withContext Result.failure(Exception("Hedef dosya açılamadı."))
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
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw SecurityException("Yedek dosyası çok büyük.")
                    buf.write(chunk, 0, n)
                }
                buf.toString(Charsets.UTF_8.name())
            } ?: return@withContext Result.failure(Exception("Yedek dosyası açılamadı."))

            val root = JsonParser.parseString(text).asJsonObject
            if (root.get("format")?.asString != FORMAT) {
                return@withContext Result.failure(Exception("Geçersiz yedek biçimi."))
            }

            root.stringOrNull("foldersJson")?.takeIf { isJsonArray(it) }?.let { dataStore.saveFoldersJson(it) }
            root.stringOrNull("usageStatsJson")?.takeIf { isJsonArray(it) }?.let { dataStore.saveUsageStatsJson(it) }
            root.stringOrNull("pwasJson")?.let { raw ->
                val pwas: List<PwaInfo> = gson.fromJson(raw, object : TypeToken<List<PwaInfo>>() {}.type) ?: emptyList()
                // Yalnızca https adresli PWA'lar içe aktarılır.
                val safe = pwas.filter { it.url.startsWith("https://", ignoreCase = true) }.take(MAX_PWAS)
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

            root.getAsJsonArray("notes")?.let { arr ->
                val items: List<WorkspaceItem> =
                    gson.fromJson(arr, object : TypeToken<List<WorkspaceItem>>() {}.type) ?: emptyList()
                notes.replaceAll(items)
            }

            val at = root.get("updatedAt")?.takeIf { !it.isJsonNull }?.asLong ?: System.currentTimeMillis()
            dataStore.setLastBackupTime(at)
            Result.success(at)
        } catch (e: Exception) {
            Result.failure(e)
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
