package com.tarslauncher.app.data.model

// Minimal persistent representation of a folder (id + list of app package names)
data class PersistFolder(
    val id: String,
    val apps: List<String>
)
