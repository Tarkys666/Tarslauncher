package com.tarslauncher.app.data.model

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable

@Immutable
data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    val activityName: String = "",
    val usageCount: Int = 0,
    val isFavorite: Boolean = false,
    val isPwa: Boolean = false,
    val webUrl: String? = null,
    val pwaId: String? = null,
    val shortcutPackage: String? = null,
    val shortcutId: String? = null,
    val iconBase64: String? = null,
    val iconUrl: String? = null
)
