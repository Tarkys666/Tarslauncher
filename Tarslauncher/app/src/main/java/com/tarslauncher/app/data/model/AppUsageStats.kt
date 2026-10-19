package com.tarslauncher.app.data.model

// App usage statistics for persistence
data class AppUsageStats(
    val packageName: String,
    val usageCount: Int,
    val isFavorite: Boolean
) {
    companion object {
        const val AUTO_FAVORITE_THRESHOLD = 10
        fun calculateIsFavorite(isExplicitFavorite: Boolean, usageCount: Int): Boolean =
            isExplicitFavorite || usageCount >= AUTO_FAVORITE_THRESHOLD
    }
}
