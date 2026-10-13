package com.tarslauncher.app.data.model

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable

@Immutable
data class IconPackInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?
)
