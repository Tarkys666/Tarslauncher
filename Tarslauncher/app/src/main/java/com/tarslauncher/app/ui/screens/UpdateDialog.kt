package com.tarslauncher.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tarslauncher.app.data.updater.UpdateInfo
import com.tarslauncher.app.data.updater.UpdateState
import java.io.File
import java.util.Locale

@Composable
fun UpdateDialog(
    updateState: UpdateState,
    currentVersion: String,
    onDismiss: () -> Unit,
    onStartDownload: (UpdateInfo) -> Unit,
    onInstall: (File) -> Unit,
    onRetry: () -> Unit = {}
) {
    if (updateState is UpdateState.Idle) return

    Dialog(onDismissRequest = {
        if (updateState !is UpdateState.Downloading) {
            onDismiss()
        }
    }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // Header
                Text(
                    text = "SYSTEM UPDATE",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(16.dp))

                when (updateState) {
                    is UpdateState.Checking -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 16.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Checking for updates...",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 13.sp
                            )
                        }
                    }

                    is UpdateState.UpToDate -> {
                        Text(
                            text = "You are running the latest version.",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Current: v$currentVersion",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.1f),
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("OK", fontSize = 12.sp)
                        }
                    }

                    is UpdateState.Available -> {
                        val info = updateState.info
                        val sizeMb = if (info.apkSize > 0) {
                            String.format(Locale.US, "%.2f MB", info.apkSize / (1024f * 1024f))
                        } else {
                            "Unknown size"
                        }

                        Text(
                            text = "v$currentVersion → v${info.versionName}",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Download size: $sizeMb",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        if (info.releaseNotes.isNotBlank()) {
                            Text(
                                text = "CHANGELOG",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                letterSpacing = 1.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .background(
                                        Color.White.copy(alpha = 0.05f),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(10.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = info.releaseNotes.trim(),
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text(
                                    "LATER",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { onStartDownload(info) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    "UPDATE NOW",
                                    color = Color.Black,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    is UpdateState.Downloading -> {
                        val animatedProgress by animateFloatAsState(
                            targetValue = updateState.progress,
                            label = "downloadProgress"
                        )
                        val percent = (animatedProgress * 100).toInt().coerceIn(0, 100)
                        val downloadedMb = String.format(
                            Locale.US,
                            "%.1f",
                            updateState.bytesDownloaded / (1024f * 1024f)
                        )
                        val totalMb = if (updateState.totalBytes > 0) {
                            String.format(Locale.US, "%.1f MB", updateState.totalBytes / (1024f * 1024f))
                        } else {
                            "..."
                        }

                        Text(
                            text = "DOWNLOADING... $percent%",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$downloadedMb / $totalMb",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                    }

                    is UpdateState.ReadyToInstall -> {
                        Text(
                            text = "Update downloaded successfully.",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Ready to install TARS Launcher update.",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text(
                                    "CANCEL",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { onInstall(updateState.apkFile) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    "INSTALL",
                                    color = Color.Black,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    is UpdateState.Error -> {
                        Text(
                            text = "Update Check Failed",
                            color = Color(0xFFFF5252),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = updateState.message,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text(
                                    "CLOSE",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = onRetry,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.1f),
                                    contentColor = Color.White
                                ),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                            ) {
                                Text("RETRY", fontSize = 12.sp)
                            }
                        }
                    }

                    is UpdateState.Idle -> Unit
                }
            }
        }
    }
}
