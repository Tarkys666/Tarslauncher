package com.tarslauncher.app.data.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    val activityName: String = "",
    var usageCount: Int = 0,
    var isFavorite: Boolean = false
)
