package com.tarslauncher.app.data.model

data class FolderInfo(
    val id: String,
    val name: String,
    val apps: MutableList<AppInfo> = mutableListOf()
)
