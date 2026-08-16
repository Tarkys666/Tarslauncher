package com.tarslauncher.app.data.updater

import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState
    data object NoUpdateAvailable : UpdateState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val info: UpdateInfo
    ) : UpdateState
    data class ReadyToInstall(val apkFile: File, val info: UpdateInfo) : UpdateState
    data class Error(val message: String) : UpdateState

    val isDownloading: Boolean
        get() = this is Downloading

    val isChecking: Boolean
        get() = this is Checking
}
