package com.tarslauncher.app.data.model

// App usage statistics for persistence
data class AppUsageStats(
    val packageName: String,
    val usageCount: Int,
    val isFavorite: Boolean
)
