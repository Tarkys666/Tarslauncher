package com.tarslauncher.app.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class FolderInfo(
    val id: String,
    val name: String,
    val apps: List<AppInfo> = emptyList()
)
