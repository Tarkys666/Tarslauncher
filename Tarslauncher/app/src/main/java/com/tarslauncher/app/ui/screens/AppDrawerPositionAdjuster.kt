package com.tarslauncher.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
 * to freely drag the App Drawer across the full phone screen (no 200px limit).
 */
@Composable
fun AppDrawerPositionAdjuster(
    installedApps: List<AppInfo>,
    folders: List<FolderInfo>,
    dualAppDrawer: Boolean = false,
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

        // No 200px limit! Maximum offset allows drawer to travel up to near the top of the phone
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
                showAppDrawer = false, // Background doesn't render static drawer
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

        // 3. Top Info & Instructions Badge
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp)
                .background(Color(0xEE161616), RoundedCornerShape(20.dp))
                .border(BorderStroke(1.dp, Color(0xFF333333)), RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .zIndex(25f)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "APP DRAWER POSITION",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${currentOffsetDp.toInt()} dp • Drag anywhere up or down",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 11.sp
                )
            }
        }

        // 4. Live Draggable Controls & App Drawer
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .zIndex(15f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag handle with glowing badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .background(Color(0xEE222222), RoundedCornerShape(14.dp))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "↕ DRAG ME",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${currentOffsetDp.toInt()} dp",
                    color = Color(0xFF90CAF9),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Settings button
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                    .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)), RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "SETTINGS",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraLight,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // App Drawer preview with real icons
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (dualAppDrawer) 155.dp else 95.dp),
                contentAlignment = Alignment.Center
            ) {
                if (dualAppDrawer) {
                    DualRowAppDrawer(
                        installedApps = installedApps,
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

        // 5. Bottom Right: OK / SAVE Button (Requested by user)
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
                text = "OK  ✔",
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
                containerColor = Color(0xDD181818),
                contentColor = Color.White
            ),
            border = BorderStroke(1.dp, Color(0xFF444444)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 20.dp, bottom = 20.dp)
                .zIndex(30f)
        ) {
            Text(
                text = "CANCEL",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
