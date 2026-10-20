package com.tarslauncher.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.tarslauncher.app.IconPackLoader
import com.tarslauncher.app.data.model.AppInfo
import com.tarslauncher.app.data.model.FolderInfo
import com.tarslauncher.app.data.model.GridSize

/**
 * Full-screen interactive App Drawer position adjustment overlay.
 * Renders the live HomeScreen with a blur effect in the background and allows the user
 * to freely drag the App Drawer across the full phone screen.
 * Shows ONLY the App Drawer, a center guide line across the phone's exact middle,
 * and OK and CANCEL buttons.
 */
@Composable
fun AppDrawerPositionAdjuster(
    installedApps: List<AppInfo>,
    folders: List<FolderInfo>,
    dualAppDrawer: Boolean = false,
    appDrawerRows: Int = if (dualAppDrawer) 2 else 1,
    gridSize: GridSize,
    iconPackLoader: IconPackLoader?,
    buttonColor: Color = Color.Transparent,
    showTarsHeader: Boolean,
    appDrawerIconSize: Int,
    showClock: Boolean,
    showAsciiDiamond: Boolean = false,
    asciiDiamondScale: Int = 100,
    asciiDiamondOffsetY: Int = 0,
    asciiDiamondFps: Int = 60,
    asciiDiamondSpeed: Int = 100,
    showFolderGrid: Boolean = true,
    initialOffsetY: Int = 24,
    onSave: (Int) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler {
        onCancel()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenHeight = maxHeight
        val screenWidth = maxWidth

        val maxOffsetDp = (screenHeight - 160.dp).coerceAtLeast(100.dp)
        var currentOffsetDp by remember {
            mutableFloatStateOf(initialOffsetY.coerceIn(0, maxOffsetDp.value.toInt()).toFloat())
        }

        // 1. Live Blurred HomeScreen in the background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(18.dp)
        ) {
            HomeScreen(
                installedApps = installedApps,
                folders = folders,
                onFoldersUpdate = {},
                onSettingsClick = {},
                onAppClick = {},
                showAppDrawer = false,
                dualAppDrawer = dualAppDrawer,
                gridSize = gridSize,
                iconPackLoader = iconPackLoader,
                buttonColor = buttonColor,
                showTarsHeader = showTarsHeader,
                appDrawerIconSize = appDrawerIconSize,
                showClock = showClock,
                showAsciiDiamond = showAsciiDiamond,
                asciiDiamondScale = asciiDiamondScale,
                asciiDiamondOffsetY = asciiDiamondOffsetY,
                asciiDiamondFps = asciiDiamondFps,
                asciiDiamondSpeed = asciiDiamondSpeed,
                showFolderGrid = showFolderGrid,
                appDrawerOffsetY = 0,
                pwaPresets = emptyList(),
                onAddPwa = { _, _ -> },
                onDeletePwa = {}
            )
        }

        // 2. Dark frosted scrim overlay that captures vertical dragging across the entire screen
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        // Dragging upwards (negative Y in screen coords) increases bottom offset
                        val deltaDp = -dragAmount / density.density
                        currentOffsetDp = (currentOffsetDp + deltaDp).coerceIn(0f, maxOffsetDp.value)
                    }
                }
        )

        // 3. Center Guide Line - exact middle of the phone
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.45f))
                .zIndex(20f)
        )

        // 4. Live Draggable App Drawer
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .zIndex(15f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val effectiveRows = if (appDrawerRows > 1) appDrawerRows.coerceIn(1, 5) else (if (dualAppDrawer) 2 else 1)
            val drawerHeight = if (effectiveRows > 1) {
                val singleH = (appDrawerIconSize + 16).dp
                (singleH * effectiveRows + 4.dp * (effectiveRows - 1)).coerceAtMost(maxOffsetDp)
            } else (appDrawerIconSize + 28).dp

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(drawerHeight),
                contentAlignment = Alignment.Center
            ) {
                if (effectiveRows > 1) {
                    MultiRowAppDrawer(
                        installedApps = installedApps,
                        rowCount = effectiveRows,
                        onAppClick = {},
                        onAppLongClick = {},
                        onAddPwaClick = {},
                        iconPackLoader = iconPackLoader,
                        iconSize = appDrawerIconSize,
                        screenWidth = screenWidth
                    )
                } else {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy((screenWidth * 0.03f).coerceAtLeast(8.dp).coerceAtMost(16.dp))
                    ) {
                        items(
                            items = installedApps.take(20),
                            key = { it.packageName },
                            contentType = { "app_icon" }
                        ) { app ->
                            AppIconWithoutName(
                                app = app,
                                onAppClick = {},
                                onAppLongClick = {},
                                iconPackLoader = iconPackLoader,
                                iconSize = appDrawerIconSize
                            )
                        }
                    }
                }
            }

            // Bottom dynamic spacer following current offset
            Spacer(
                modifier = Modifier
                    .navigationBarsPadding()
                    .height(currentOffsetDp.dp)
            )
        }

        // 5. Bottom Right: OK Button
        Button(
            onClick = { onSave(currentOffsetDp.toInt()) },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 20.dp)
                .zIndex(30f),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
        ) {
            Text(
                text = "OK",
                color = Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        // 6. Bottom Left: CANCEL Button
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Black,
                contentColor = Color.White
            ),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 20.dp, bottom = 20.dp)
                .zIndex(30f)
        ) {
            Text(
                text = "CANCEL",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            )
        }
    }
}
