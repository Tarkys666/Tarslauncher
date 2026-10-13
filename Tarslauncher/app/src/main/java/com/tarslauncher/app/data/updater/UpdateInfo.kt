package com.tarslauncher.app.data.updater

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName
import java.io.File

@Immutable
data class UpdateInfo(
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long,
    val hasUpdate: Boolean
)

@Immutable
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    object UpToDate : UpdateState()
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : UpdateState()
    data class ReadyToInstall(val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("body") val body: String? = null,
    @SerializedName("prerelease") val prerelease: Boolean = false,
    @SerializedName("draft") val draft: Boolean = false,
    @SerializedName("assets") val assets: List<GitHubAsset>? = null
)

data class GitHubAsset(
    @SerializedName("name") val name: String? = null,
    @SerializedName("size") val size: Long = 0L,
    @SerializedName("browser_download_url") val downloadUrl: String? = null
)
