package com.tarslauncher.app.data.updater

data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val publishedAt: String = "",
    val apkSize: Long = 0L
)
