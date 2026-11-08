package com.tarslauncher.app.data.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tarslauncher.app.data.model.GridSize
import com.tarslauncher.app.data.model.LauncherSettings

/** Fully parsed before any local backup data is replaced. */
internal data class BackupPayload(
    val settings: LauncherSettings,
    val foldersJson: String?,
    val pwasJson: String?,
    val usageStatsJson: String?,
    val updatedAt: Long
) {
    companion object {
        fun fromRow(row: JsonObject): BackupPayload {
            val payload = row.getAsJsonObject("payload") ?: error("Yedek verisi okunamadı.")
            val settings = payload.getAsJsonObject("settings") ?: JsonObject()
            fun JsonObject.text(key: String): String? {
                val value = get(key)?.takeUnless { it.isJsonNull } ?: return null
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Geçersiz yedek alanı: $key" }
                return value.asString
            }
            fun number(obj: JsonObject, key: String, default: Int, range: IntRange): Int {
                val value = obj.get(key)?.takeUnless { it.isJsonNull } ?: return default
                require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Geçersiz yedek alanı: $key" }
                val result = value.asBigDecimal.intValueExact()
                require(result in range) { "Yedek ayarı izin verilen aralık dışında: $key" }
                return result
            }
            fun flag(key: String, default: Boolean): Boolean {
                val value = settings.get(key)?.takeUnless { it.isJsonNull } ?: return default
                require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Geçersiz yedek alanı: $key" }
                return value.asBoolean
            }
            fun array(key: String, identity: String, requiredStrings: List<String>): String? {
                val json = payload.text(key)?.takeIf { it.isNotBlank() } ?: return null
                val items = JsonParser.parseString(json).asJsonArray
                val ids = mutableSetOf<String>()
                if (key == "foldersJson") require(items.size() <= GridSize.MAX_COLUMNS * GridSize.MAX_ROWS)
                for (element in items) {
                    val item = element.asJsonObject
                    for (field in requiredStrings) require(item.text(field) != null) { "Yedek alanı eksik: $field" }
                    val id = item.text(identity)
                    require(!id.isNullOrBlank() && ids.add(id)) { "Geçersiz veya yinelenen yedek kimliği." }
                    when (key) {
                        "foldersJson" -> {
                            val apps = item.getAsJsonArray("apps") ?: error("Klasör uygulamaları eksik.")
                            require(apps.all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
                        }
                        "pwasJson" -> {
                            for (field in listOf("url", "iconBase64", "iconUrl", "shortcutPackage", "shortcutId")) item.text(field)
                        }
                        "usageStatsJson" -> {
                            number(item, "usageCount", 0, 0..Int.MAX_VALUE)
                            item.get("isFavorite")?.takeUnless { it.isJsonNull }?.let {
                                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean)
                            }
                        }
                    }
                }
                return items.toString()
            }

            val rawGrid = settings.text("gridSize")
            val grid = if (rawGrid == null) GridSize.DEFAULT else
                GridSize.parse(rawGrid) ?: error("Yedekteki grid ölçüleri geçersiz.")
            val restoredSettings = LauncherSettings(
                showAppDrawer = flag("showAppDrawer", true),
                dualAppDrawer = flag("dualAppDrawer", false),
                appDrawerRows = number(settings, "appDrawerRows", 1, 1..20),
                gridSize = grid,
                buttonColorMode = settings.text("buttonColorMode") ?: "transparent",
                showTarsHeader = flag("showTarsHeader", true),
                showClock = flag("showClock", true),
                showWidgets = flag("showWidgets", false),
                selectedIconPack = settings.text("selectedIconPack"),
                appDrawerIconSize = number(settings, "appDrawerIconSize", 32, 20..36),
                showAsciiDiamond = flag("showAsciiDiamond", false),
                asciiDiamondScale = number(settings, "asciiDiamondScale", 100, 40..180),
                asciiDiamondOffsetY = number(settings, "asciiDiamondOffsetY", 0, -40..40),
                asciiDiamondFps = number(settings, "asciiDiamondFps", 60, 15..120),
                asciiDiamondSpeed = number(settings, "asciiDiamondSpeed", 100, 0..200),
                showFolderGrid = flag("showFolderGrid", true),
                appDrawerOffsetY = number(settings, "appDrawerOffsetY", 24, 0..10000)
            )
            val updatedAt = (row.get("updated_at") ?: payload.get("updatedAt"))
                ?.takeUnless { it.isJsonNull }?.asBigDecimal?.longValueExact() ?: System.currentTimeMillis()
            require(updatedAt >= 0) { "Yedek zamanı geçersiz." }
            return BackupPayload(
                restoredSettings,
                array("foldersJson", "id", listOf("id")),
                array("pwasJson", "id", listOf("id", "name")),
                array("usageStatsJson", "packageName", listOf("packageName")),
                updatedAt
            )
        }
    }
}
