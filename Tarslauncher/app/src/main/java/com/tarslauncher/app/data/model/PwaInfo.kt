package com.tarslauncher.app.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class PwaInfo(
    val id: String,
    val name: String,
    val url: String = "",
    val iconBase64: String? = null,
    val iconUrl: String? = null,
    val shortcutPackage: String? = null,
    val shortcutId: String? = null,
    val addedAt: Long = System.currentTimeMillis()
) {
    val pseudoPackageName: String
        get() = "$PACKAGE_PREFIX$id"

    companion object {
        const val PACKAGE_PREFIX = "pwa_"
        fun generateId(): String = java.util.UUID.randomUUID().toString().take(8)
    }
}
