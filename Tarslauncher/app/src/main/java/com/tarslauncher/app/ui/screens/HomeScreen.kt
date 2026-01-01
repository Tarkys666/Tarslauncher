package com.tarslauncher.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.tarslauncher.app.IconPackLoader
import com.tarslauncher.app.data.model.AppInfo
import com.tarslauncher.app.data.model.FolderInfo
import com.tarslauncher.app.utils.drawableToBitmap
import com.tarslauncher.app.utils.turnOffDevice
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.gestures.detectTapGestures

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    installedApps: List<AppInfo>,
    folders: List<FolderInfo>,
    onFoldersUpdate: (List<FolderInfo>) -> Unit,
    onSettingsClick: () -> Unit,
    onAppClick: (AppInfo) -> Unit,
    showAppDrawer: Boolean,
    gridSize: String,
    iconPackLoader: IconPackLoader?,
    buttonColor: Color,
    folderLocked: Boolean,
    showTarsHeader: Boolean,
    appDrawerIconSize: Int,
    showClock: Boolean,
    doubleTapLockEnabled: Boolean
) {
    // Stable state variables to prevent recomposition issues
    var showFolderSelectDialog by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    
    val context = LocalContext.current
    
    // Stable time state with key to prevent unnecessary recompositions
    var currentTime by remember { mutableStateOf("") }
    
    // Update time every second with stable key
    LaunchedEffect("time_updater") {
        while (true) {
            currentTime = try {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            } catch (e: Exception) {
                "00:00"
            }
            kotlinx.coroutines.delay(1000) // Update every second
        }
    }
    
    // Stable scroll state
    val scrollState = rememberScrollState()

    // Stable feedback list
    val feedbackList = remember { mutableStateListOf<String>() }
    
    // Responsive layout calculations
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val screenWidth = configuration.screenWidthDp.dp
    
    // Responsive padding values based on screen size
    val clockTopPadding = (screenHeight * 0.08f).coerceAtLeast(32.dp).coerceAtMost(64.dp)
    val commandBarTopPadding = (screenHeight * 0.18f).coerceAtLeast(120.dp).coerceAtMost(200.dp)
    val commandBarSpacerHeight = (screenHeight * 0.25f).coerceAtLeast(150.dp).coerceAtMost(250.dp)
    
    // Responsive font sizes
    val clockFontSize = when {
        screenWidth < 360.dp -> 36.sp
        screenWidth < 480.dp -> 42.sp
        else -> 48.sp
    }
    
    val tarsFontSize = when {
        screenWidth < 360.dp -> 12.sp
        screenWidth < 480.dp -> 14.sp
        else -> 16.sp
    }
    
    // Responsive button sizes
    val buttonHeight = when {
        screenHeight < 600.dp -> 28.dp
        screenHeight < 800.dp -> 32.dp
        else -> 36.dp
    }
    
    val buttonFontSize = when {
        screenWidth < 360.dp -> 7.sp
        screenWidth < 480.dp -> 8.sp
        else -> 9.sp
    }
    
    // Responsive app drawer height
    val appDrawerHeight = when {
        screenHeight < 600.dp -> 80.dp
        screenHeight < 800.dp -> 100.dp
        else -> 120.dp
    }
    
    // Responsive padding
    val mainPadding = (screenWidth * 0.04f).coerceAtLeast(12.dp).coerceAtMost(20.dp)

    // Ana Box container - tüm overlay'ler için
    val baseModifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
    val gestureModifier = if (doubleTapLockEnabled) {
        baseModifier.pointerInput("double_tap_detector") {
            detectTapGestures(onDoubleTap = {
                turnOffDevice(context)
            })
        }
    } else baseModifier

    Box(
        modifier = gestureModifier
    ) {
        // Main content column - sadece butonlar ve command bar için
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = mainPadding), // Responsive padding
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Command bar overlay için responsive boş alan
            Spacer(modifier = Modifier.height(commandBarSpacerHeight))
            
            // Stable command bar section - overlay olarak konumlandırılacak
            
            // App drawer ile butonlar arasında esnek boşluk - daha küçük weight
            Spacer(modifier = Modifier.weight(0.15f))
            
            // Responsive Ayarlar Butonu
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    // App drawer kapalıyken sistem butonları için otomatik padding ekle
                    .padding(bottom = if (!showAppDrawer) 48.dp else 0.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy((screenWidth * 0.02f).coerceAtLeast(6.dp).coerceAtMost(12.dp))
            ) {
                Button(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .padding(bottom = (screenHeight * 0.01f).coerceAtLeast(6.dp).coerceAtMost(12.dp))
                        .height(buttonHeight), // Responsive height
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor
                    ),
                    contentPadding = PaddingValues(
                        horizontal = (screenWidth * 0.02f).coerceAtLeast(6.dp).coerceAtMost(12.dp),
                        vertical = (screenHeight * 0.005f).coerceAtLeast(3.dp).coerceAtMost(6.dp)
                    ),
                    interactionSource = remember { MutableInteractionSource() } // Remove ripple animation
                ) {
                    Text(
                        text = "SETTINGS",
                        color = Color.White,
                        fontSize = buttonFontSize, // Responsive font size
                        fontWeight = FontWeight.ExtraLight,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp)) // App drawer'ı settings butonuna yaklaştırmak için minimal spacer
            
            // Responsive Alt Uygulama Listesi
            if (showAppDrawer) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .fillMaxWidth()
                        .height(appDrawerHeight) // Responsive height
                        .navigationBarsPadding() // Alt navigation bar için padding
                        .padding(top = 4.dp) // Settings butonuna yaklaşmak için minimal boşluk
                        .padding(bottom = 24.dp) // Alt navigation bar ile arasında boşluk
                        .padding(horizontal = mainPadding), // Responsive padding
                    contentAlignment = Alignment.Center
                ) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = mainPadding), // Responsive padding
                        horizontalArrangement = Arrangement.spacedBy((screenWidth * 0.03f).coerceAtLeast(8.dp).coerceAtMost(16.dp))
                    ) {
                        items(installedApps) { app ->
                            AppIconWithoutName(
                                app = app,
                                onAppClick = onAppClick,
                                onAppLongClick = { longClickedApp ->
                                    if (!folderLocked && longClickedApp.packageName.isNotBlank()) {
                                        selectedApp = longClickedApp
                                        showFolderSelectDialog = true
                                    }
                                },
                                iconPackLoader = iconPackLoader,
                                iconSize = appDrawerIconSize
                            )
                        }
                    }
                }
            }
        }
        
        // ✅ RESPONSIVE SAAT OVERLAY - Her cihaza uyumlu
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = clockTopPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (showClock) {
                    Text(
                        text = currentTime,
                        color = Color.White,
                        fontSize = clockFontSize, // Responsive font size
                        fontWeight = FontWeight.Light,
                        letterSpacing = 2.sp
                    )
                }
                if (showTarsHeader) {
                    Text(
                        text = "T A R S",
                        color = Color.White,
                        fontSize = tarsFontSize,
                        fontWeight = FontWeight.ExtraLight,
                        letterSpacing = 4.sp
                    )
                }
            }
        }
        
        // ✅ RESPONSIVE FOLDER GRID OVERLAY - Her cihaza uyumlu
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = mainPadding,
                    vertical = (screenHeight * 0.05f).coerceAtLeast(20.dp).coerceAtMost(40.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            FolderGrid(
                folders = folders,
                onFoldersUpdate = onFoldersUpdate,
                onAppClick = onAppClick,
                gridSize = gridSize,
                iconPackLoader = iconPackLoader,
                buttonColor = buttonColor,
                folderLocked = folderLocked
            )
        }
    }
    
    // Folder seçim dialog'u - Güvenli
    if (showFolderSelectDialog && selectedApp != null) {
        FolderSelectDialog(
            app = selectedApp!!,
            folders = folders,
            gridSize = gridSize,
            onDismiss = { 
                showFolderSelectDialog = false
                selectedApp = null
            },
            onFolderSelected = { folder ->
                // Çift kontrol: Folder hala uygun mu?
                if (selectedApp != null && 
                    folder.apps.size < 8 && 
                    !folder.apps.any { it.packageName == selectedApp!!.packageName }) {
                    
                    val safeFolder = runCatching {
                        folder.copy(
                            apps = folder.apps.toMutableList().apply { 
                                add(selectedApp!!) 
                            }
                        )
                    }.getOrNull()
                    
                    if (safeFolder != null) {
                        val updatedFolders = folders.map { 
                            if (it.id == safeFolder.id) safeFolder else it 
                        }
                        onFoldersUpdate(updatedFolders)
                    }
                }
                showFolderSelectDialog = false
                selectedApp = null
            },
            iconPackLoader = iconPackLoader,
            buttonColor = buttonColor
        )
    }

}

@Composable
fun FolderGrid(
    folders: List<FolderInfo>,
    onFoldersUpdate: (List<FolderInfo>) -> Unit,
    onAppClick: (AppInfo) -> Unit,
    gridSize: String,
    iconPackLoader: IconPackLoader?,
    buttonColor: Color,
    folderLocked: Boolean
) {
    // Stable state variables with keys to prevent recomposition issues
    var showFolderAppsDialog by remember("folder_dialog_state") { mutableStateOf(false) }
    var selectedFolder by remember("selected_folder_state") { mutableStateOf<FolderInfo?>(null) }
    
    // Stable grid dimensions
    val (cols, rows) = remember(gridSize) { gridSize.split("x").map { it.toInt() } }
    
    // Stable column layout with fixed positioning
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Dinamik grid layout
        repeat(rows) { rowIndex ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                repeat(cols) { colIndex ->
                    val folderIndex = rowIndex * cols + colIndex
                    if (folderIndex < folders.size) {
                        val folder = folders[folderIndex]
                        FolderItem(
                            folder = folder,
                            onClick = {
                                selectedFolder = folder
                                showFolderAppsDialog = true
                            },
                            iconPackLoader = iconPackLoader,
                            buttonColor = buttonColor
                        )
                    } else {
                        // Boş alan için invisible placeholder
                        Spacer(modifier = Modifier.size(45.dp))
                    }
                }
            }
        }
    }
    
    // Folder uygulamaları dialog'u
    if (showFolderAppsDialog && selectedFolder != null) {
        FolderAppsDialog(
            folder = selectedFolder!!,
            onDismiss = {
                showFolderAppsDialog = false
                selectedFolder = null
            },
            onAppClick = { app ->
                onAppClick(app)
                showFolderAppsDialog = false
                selectedFolder = null
            },
            onAppRemove = { app ->
                if (!folderLocked) {
                    val updatedFolder = selectedFolder!!.copy(
                        apps = selectedFolder!!.apps.filter { it.packageName != app.packageName }.toMutableList()
                    )
                    val updatedFolders = folders.map {
                        if (it.id == updatedFolder.id) updatedFolder else it
                    }
                    onFoldersUpdate(updatedFolders)
                }
            },
            iconPackLoader = iconPackLoader,
            folderLocked = folderLocked
        )
    }
}

@Composable
fun FolderItem(
    folder: FolderInfo,
    onClick: () -> Unit,
    iconPackLoader: IconPackLoader?,
    buttonColor: Color
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .size(45.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = buttonColor.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(2) { rowIndex ->
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(2) { colIndex ->
                        val appIndex = rowIndex * 2 + colIndex
                        Box(
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (appIndex < folder.apps.size) {
                                val app = folder.apps[appIndex]
                                val appIcon = remember(app.packageName, iconPackLoader) {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                                    val activityName = launchIntent?.component?.className ?: ""
                                    val themedIcon = iconPackLoader?.getIconForApp(app.packageName, activityName)
                                    (themedIcon ?: app.icon)?.let {
                                        drawableToBitmap(it).asImageBitmap()
                                    }
                                }

                                if (appIcon != null) {
                                    Image(
                                        bitmap = appIcon,
                                        contentDescription = app.name,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppDrawer(
    installedApps: List<AppInfo>,
    onAppClick: (AppInfo) -> Unit,
    onAppLongClick: (AppInfo) -> Unit,
    iconPackLoader: IconPackLoader?
) {
    if (installedApps.isNotEmpty()) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(installedApps) { app ->
                AppIconWithoutName(
                    app = app,
                    onAppClick = onAppClick,
                    onAppLongClick = onAppLongClick,
                    iconPackLoader = iconPackLoader
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIconWithoutName(
    app: AppInfo,
    onAppClick: (AppInfo) -> Unit,
    onAppLongClick: (AppInfo) -> Unit,
    iconPackLoader: IconPackLoader?,
    iconSize: Int = 32
) {
    val context = LocalContext.current
    
    // Icon'u composable dışında hazırlıyoruz
    val appIcon = remember(app.packageName, iconPackLoader) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        val activityName = launchIntent?.component?.className ?: ""

        val themedIcon = iconPackLoader?.getIconForApp(app.packageName, activityName)

        (themedIcon ?: app.icon)?.toBitmap()?.asImageBitmap()
    }
    
    Card(
        modifier = Modifier
            .size((iconSize + 18).dp) // Card boyutu icon boyutuna göre ayarlanır
            .combinedClickable(
                onClick = { onAppClick(app) },
                onLongClick = { onAppLongClick(app) }
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = app.name,
                    modifier = Modifier.size(iconSize.dp)
                )
            } else {
                // Fallback icon
                Box(
                    modifier = Modifier
                        .size(iconSize.dp)
                        .background(Color.Gray, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.name.take(2).uppercase(),
                        color = Color.White,
                        fontSize = (iconSize * 0.3f).sp // Font boyutu icon boyutuna göre
                    )
                }
            }
        }
    }
}

@Composable
fun FolderSelectDialog(
    app: AppInfo,
    folders: List<FolderInfo>,
    onDismiss: () -> Unit,
    onFolderSelected: (FolderInfo) -> Unit,
    gridSize: String,
    iconPackLoader: IconPackLoader? = null,
    buttonColor: Color = Color.Gray
) {
    val context = LocalContext.current
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.9f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            // Grid layout matching home screen settings
            val gridParts = gridSize.split("x")
            val columns = gridParts[0].toIntOrNull() ?: 4
            val rows = gridParts[1].toIntOrNull() ?: 2
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                repeat(rows) { rowIndex ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        repeat(columns) { colIndex ->
                            val folderIndex = rowIndex * columns + colIndex
                            if (folderIndex < folders.size) {
                                val folder = folders[folderIndex]
                                val isSelectable = folder.apps.size < 8 && !folder.apps.any { it.packageName == app.packageName }
                                FolderSelectItem(
                                    folder = folder,
                                    onClick = {
                                        // Only allow selection if folder has space and doesn't contain the app
                                        if (isSelectable) {
                                            onFolderSelected(folder)
                                            onDismiss()
                                        }
                                    },
                                    iconPackLoader = iconPackLoader,
                                    buttonColor = buttonColor,
                                    isSelectable = isSelectable
                                )
                            } else {
                                // Empty placeholder to maintain spacing
                                Spacer(modifier = Modifier.size(45.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderSelectItem(
    folder: FolderInfo,
    onClick: () -> Unit,
    iconPackLoader: IconPackLoader? = null,
    buttonColor: Color = Color.Gray,
    isSelectable: Boolean = true
) {
    val context = LocalContext.current
    
    val cardColor = if (isSelectable) {
        buttonColor.copy(alpha = 0.1f)
    } else {
        Color.Red.copy(alpha = 0.1f)
    }
    
    Card(
        modifier = Modifier
            .size(45.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(2) { rowIndex ->
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(2) { colIndex ->
                        val appIndex = rowIndex * 2 + colIndex
                        Box(
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (appIndex < folder.apps.size) {
                                val app = folder.apps[appIndex]
                                val appIcon = remember(app.packageName, iconPackLoader) {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                                    val activityName = launchIntent?.component?.className ?: ""
                                    val themedIcon = iconPackLoader?.getIconForApp(app.packageName, activityName)
                                    (themedIcon ?: app.icon)?.let {
                                        drawableToBitmap(it).asImageBitmap()
                                    }
                                }

                                if (appIcon != null) {
                                    Image(
                                        bitmap = appIcon,
                                        contentDescription = app.name,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderAppsDialog(
    folder: FolderInfo,
    onDismiss: () -> Unit,
    onAppClick: (AppInfo) -> Unit,
    onAppRemove: (AppInfo) -> Unit,
    iconPackLoader: IconPackLoader?,
    folderLocked: Boolean
) {
    val context = LocalContext.current
    // Long-press removal confirmation state
    var appToRemove by remember { mutableStateOf<AppInfo?>(null) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent
            ),
            shape = RoundedCornerShape(0.dp)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .wrapContentHeight()
                    .wrapContentWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(folder.apps) { app ->
                    FolderAppItem(
                        app = app,
                        onClick = { onAppClick(app) },
                        onLongClick = {
                            if (!folderLocked) {
                                appToRemove = app
                                showRemoveConfirm = true
                            }
                        },
                        iconPackLoader = iconPackLoader
                    )
                }
            }
        }
    }

    // Confirmation dialog to remove app from folder
    if (showRemoveConfirm && appToRemove != null) {
        Dialog(onDismissRequest = { showRemoveConfirm = false }) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.9f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .wrapContentWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Remove from folder?",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    appToRemove?.let { app ->
                        Text(
                            text = app.name,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        text = "Sistemden de silelim mi?",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                showRemoveConfirm = false
                                appToRemove = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .size(width = 80.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("CANCEL", color = Color.White, fontWeight = FontWeight.Light, fontSize = 10.sp)
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Button(
                            onClick = {
                                // Only remove from folder
                                appToRemove?.let { onAppRemove(it) }
                                showRemoveConfirm = false
                                appToRemove = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.1f),
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .size(width = 80.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("REMOVE", color = Color.White, fontWeight = FontWeight.Light, fontSize = 10.sp)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                // Klasörden çıkar + sistemden kaldır
                                appToRemove?.let { app ->
                                    onAppRemove(app)
                                    try {
                                        val intent = Intent(Intent.ACTION_DELETE).apply {
                                            data = Uri.parse("package:${app.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Uygulama kaldırılamıyor", Toast.LENGTH_LONG).show()
                                    }
                                }
                                showRemoveConfirm = false
                                appToRemove = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Red.copy(alpha = 0.2f),
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .size(width = 80.dp, height = 32.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("DELETE", color = Color.White, fontWeight = FontWeight.Light, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderAppItem(
    app: AppInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    iconPackLoader: IconPackLoader?
) {
    val context = LocalContext.current
    val appIcon = remember(app.packageName, iconPackLoader) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            val activityName = launchIntent?.component?.className ?: ""
            val themedIcon = iconPackLoader?.getIconForApp(app.packageName, activityName)
            (themedIcon ?: app.icon)?.let {
                drawableToBitmap(it).asImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (appIcon != null) {
            Image(
                bitmap = appIcon,
                contentDescription = app.name,
                modifier = Modifier.size(32.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color.Gray, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.name.take(2).uppercase(),
                    color = Color.White,
                    fontSize = 10.sp
                )
            }
        }
    }
}
