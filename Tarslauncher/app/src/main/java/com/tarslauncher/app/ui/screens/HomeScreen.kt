package com.tarslauncher.app.ui.screens

import android.app.Application
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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.tarslauncher.app.utils.getAppImageBitmap
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.gestures.detectTapGestures

import androidx.core.content.ContextCompat
import com.tarslauncher.app.data.model.GridSize
import com.tarslauncher.app.data.model.PwaInfo

private val AppInfo.componentName: String?
    get() = activityName.ifBlank { pwaId ?: shortcutId }

private val FolderShape = RoundedCornerShape(8.dp)
private val SpacedBy4Dp = Arrangement.spacedBy(4.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    installedApps: List<AppInfo>,
    folders: List<FolderInfo>,
    onFoldersUpdate: (List<FolderInfo>) -> Unit,
    onSettingsClick: () -> Unit,
    onAppClick: (AppInfo) -> Unit,
    showAppDrawer: Boolean,
    gridSize: GridSize,
    iconPackLoader: IconPackLoader?,
    buttonColor: Color,
    showTarsHeader: Boolean,
    appDrawerIconSize: Int,
    showClock: Boolean,
    pwaPresets: List<PwaInfo> = emptyList(),
    onAddPwa: (name: String, url: String) -> Unit = { _, _ -> },
    onDeletePwa: (pwaId: String) -> Unit = {}
) {
    // Stable state variables to prevent recomposition issues
    var showFolderSelectDialog by remember { mutableStateOf(false) }
    var showAddPwaDialog by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    
    val context = LocalContext.current
    
    // Responsive layout calculations
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val screenWidth = configuration.screenWidthDp.dp
    
    // Responsive padding values based on screen size (memoized)
    val clockTopPadding = remember(screenHeight) { (screenHeight * 0.08f).coerceAtLeast(32.dp).coerceAtMost(64.dp) }
    val commandBarSpacerHeight = remember(screenHeight) { (screenHeight * 0.25f).coerceAtLeast(150.dp).coerceAtMost(250.dp) }
    
    // Responsive font sizes (memoized)
    val clockFontSize = remember(screenWidth) {
        when {
            screenWidth < 360.dp -> 36.sp
            screenWidth < 480.dp -> 42.sp
            else -> 48.sp
        }
    }
    
    val tarsFontSize = remember(screenWidth) {
        when {
            screenWidth < 360.dp -> 12.sp
            screenWidth < 480.dp -> 14.sp
            else -> 16.sp
        }
    }
    
    // Responsive button sizes (memoized)
    val buttonHeight = remember(screenHeight) {
        when {
            screenHeight < 600.dp -> 28.dp
            screenHeight < 800.dp -> 32.dp
            else -> 36.dp
        }
    }
    
    val buttonFontSize = remember(screenWidth) {
        when {
            screenWidth < 360.dp -> 7.sp
            screenWidth < 480.dp -> 8.sp
            else -> 9.sp
        }
    }
    
    // Responsive app drawer height (memoized)
    val appDrawerHeight = remember(screenHeight) {
        when {
            screenHeight < 600.dp -> 80.dp
            screenHeight < 800.dp -> 100.dp
            else -> 120.dp
        }
    }
    
    // Responsive padding (memoized)
    val mainPadding = remember(screenWidth) { (screenWidth * 0.04f).coerceAtLeast(12.dp).coerceAtMost(20.dp) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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
            
            // App drawer ile butonlar arasında esnek boşluk - daha küçük weight
            Spacer(modifier = Modifier.weight(0.15f))
            
            // Responsive Ayarlar Butonu
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .then(
                        if (!showAppDrawer) Modifier.navigationBarsPadding().padding(bottom = 16.dp)
                        else Modifier
                    ),
                horizontalArrangement = Arrangement.spacedBy((screenWidth * 0.02f).coerceAtLeast(6.dp).coerceAtMost(12.dp))
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = (screenHeight * 0.01f).coerceAtLeast(6.dp).coerceAtMost(12.dp))
                        .height(buttonHeight)
                        .background(buttonColor, RoundedCornerShape(4.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onSettingsClick()
                        }
                        .padding(
                            horizontal = (screenWidth * 0.02f).coerceAtLeast(6.dp).coerceAtMost(12.dp),
                            vertical = (screenHeight * 0.005f).coerceAtLeast(3.dp).coerceAtMost(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "SETTINGS",
                        color = Color.White,
                        fontSize = buttonFontSize,
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
                    val onAppLongClickMemo: (AppInfo) -> Unit = remember {
                        { longClickedApp ->
                            if (longClickedApp.packageName.isNotBlank()) {
                                selectedApp = longClickedApp
                                showFolderSelectDialog = true
                            }
                        }
                    }

                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = mainPadding), // Responsive padding
                        horizontalArrangement = Arrangement.spacedBy((screenWidth * 0.03f).coerceAtLeast(8.dp).coerceAtMost(16.dp))
                    ) {
                        items(
                            items = installedApps,
                            key = { it.packageName },
                            contentType = { "app_icon" }
                        ) { app ->
                            AppIconWithoutName(
                                app = app,
                                onAppClick = onAppClick,
                                onAppLongClick = onAppLongClickMemo,
                                iconPackLoader = iconPackLoader,
                                iconSize = appDrawerIconSize
                            )
                        }

                        item(key = "add_pwa_button", contentType = "add_pwa") {
                            Box(
                                modifier = Modifier
                                    .size((appDrawerIconSize + 18).dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { showAddPwaDialog = true }
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(appDrawerIconSize.dp)
                                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                        .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+",
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = (appDrawerIconSize * 0.5f).sp,
                                        fontWeight = FontWeight.Light
                                    )
                                }
                            }
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
            ClockHeader(
                showClock = showClock,
                showTarsHeader = showTarsHeader,
                clockFontSize = clockFontSize,
                tarsFontSize = tarsFontSize
            )
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
                onDeletePwa = onDeletePwa
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

    if (showAddPwaDialog) {
        AddPwaDialog(
            presets = pwaPresets,
            onDismiss = { showAddPwaDialog = false },
            onAddPwa = onAddPwa
        )
    }

}

@Composable
fun FolderGrid(
    folders: List<FolderInfo>,
    onFoldersUpdate: (List<FolderInfo>) -> Unit,
    onAppClick: (AppInfo) -> Unit,
    gridSize: GridSize,
    iconPackLoader: IconPackLoader?,
    buttonColor: Color,
    onDeletePwa: (String) -> Unit = {}
) {
    // Track selected folder by ID so updates to folders list reflect immediately in the open dialog
    var showFolderAppsDialog by remember("folder_dialog_state") { mutableStateOf(false) }
    var selectedFolderId by remember("selected_folder_id_state") { mutableStateOf<String?>(null) }
    val selectedFolder by remember(selectedFolderId, folders) {
        derivedStateOf { folders.find { it.id == selectedFolderId } }
    }
    
    // Stable grid dimensions
    val cols = gridSize.columns
    val rows = gridSize.rows
    
    val onFolderClick: (String) -> Unit = remember {
        { folderId ->
            selectedFolderId = folderId
            showFolderAppsDialog = true
        }
    }

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
                        key(folder.id) {
                            FolderItem(
                                folder = folder,
                                onFolderClick = onFolderClick,
                                iconPackLoader = iconPackLoader,
                                buttonColor = buttonColor
                            )
                        }
                    } else {
                        // Boş alan için invisible placeholder
                        Spacer(modifier = Modifier.size(45.dp))
                    }
                }
            }
        }
    }
    
    // Folder uygulamaları dialog'u
    val currentFolder = selectedFolder
    if (showFolderAppsDialog && currentFolder != null) {
        FolderAppsDialog(
            folder = currentFolder,
            onDismiss = {
                showFolderAppsDialog = false
                selectedFolderId = null
            },
            onAppClick = { app ->
                onAppClick(app)
                showFolderAppsDialog = false
                selectedFolderId = null
            },
            onAppRemove = { app ->
                val updatedFolder = currentFolder.copy(
                    apps = currentFolder.apps.filter { it.packageName != app.packageName }
                )
                val updatedFolders = folders.map {
                    if (it.id == updatedFolder.id) updatedFolder else it
                }
                onFoldersUpdate(updatedFolders)
            },
            onDeletePwa = onDeletePwa,
            iconPackLoader = iconPackLoader
        )
    }
}

@Composable
fun FolderItem(
    folder: FolderInfo,
    onFolderClick: (String) -> Unit,
    iconPackLoader: IconPackLoader?,
    buttonColor: Color
) {
    val isPackLoaded = iconPackLoader?.isLoaded == true
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(45.dp)
            .background(buttonColor.copy(alpha = 0.1f), FolderShape)
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = { onFolderClick(folder.id) }
            ),
        contentAlignment = Alignment.Center
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
                    horizontalArrangement = SpacedBy4Dp
                ) {
                    repeat(2) { colIndex ->
                        val appIndex = rowIndex * 2 + colIndex
                        Box(
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (appIndex < folder.apps.size) {
                                val app = folder.apps[appIndex]
                                key(app.packageName) {
                                    val appIcon = remember(app.packageName, iconPackLoader, isPackLoaded) {
                                        val activityName = app.activityName
                                        iconPackLoader?.getImageBitmapForApp(app.packageName, activityName, app.icon)
                                            ?: getAppImageBitmap(app.packageName, app.icon)
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
    val isPackLoaded = iconPackLoader?.isLoaded == true
    
    // Icon'u composable dışında hazırlıyoruz
    val appIcon = remember(app.packageName, iconPackLoader, isPackLoaded) {
        val activityName = app.activityName
        iconPackLoader?.getImageBitmapForApp(app.packageName, activityName, app.icon)
            ?: getAppImageBitmap(app.packageName, app.icon)
    }
    
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size((iconSize + 18).dp)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onAppClick(app) },
                onLongClick = { onAppLongClick(app) }
            )
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

@Composable
fun FolderSelectDialog(
    app: AppInfo,
    folders: List<FolderInfo>,
    onDismiss: () -> Unit,
    onFolderSelected: (FolderInfo) -> Unit,
    gridSize: GridSize,
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
            val columns = gridSize.columns
            val rows = gridSize.rows
            
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
    
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(45.dp)
            .background(cardColor, FolderShape)
            .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.8f)), FolderShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
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
                    horizontalArrangement = SpacedBy4Dp
                ) {
                    repeat(2) { colIndex ->
                        val appIndex = rowIndex * 2 + colIndex
                        Box(
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (appIndex < folder.apps.size) {
                                val app = folder.apps[appIndex]
                                val isPackLoaded = iconPackLoader?.isLoaded == true
                                val appIcon = remember(app.packageName, iconPackLoader, isPackLoaded) {
                                    iconPackLoader?.getImageBitmapForApp(app.packageName, app.activityName, app.icon)
                                        ?: getAppImageBitmap(app.packageName, app.icon)
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
    onDeletePwa: (String) -> Unit = {},
    iconPackLoader: IconPackLoader?
) {
    val context = LocalContext.current
    // Long-press removal confirmation state
    var appToManage by remember { mutableStateOf<AppInfo?>(null) }
    var showManageDialog by remember { mutableStateOf(false) }

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
                items(folder.apps, key = { it.packageName }) { app ->
                    FolderAppItem(
                        app = app,
                        onClick = { onAppClick(app) },
                        onLongClick = {
                            appToManage = app
                            showManageDialog = true
                        },
                        iconPackLoader = iconPackLoader
                    )
                }
            }
        }
    }

    // Management dialog (TARS Ultra-Minimal)
    if (showManageDialog && appToManage != null) {
        Dialog(onDismissRequest = { showManageDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.95f)
                ),
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.width(250.dp)
            ) {
                Column(
                    modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Minimal Icon & Name
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                         val icon = remember(appToManage?.packageName) {
                             appToManage?.icon?.let { drawableToBitmap(it).asImageBitmap() }
                         }
                         if (icon != null) {
                             Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
                         }
                    }
                    
                    Text(
                        text = appToManage!!.name.uppercase(),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Divider
                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.1f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    // Actions
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // REMOVE
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    appToManage?.let { onAppRemove(it) }
                                    showManageDialog = false
                                    appToManage = null
                                }
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "REMOVE",
                                color = Color.White,
                                fontSize = 11.sp,
                                letterSpacing = 2.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.1f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        val isCustomPwa = appToManage!!.packageName.startsWith("pwa_") || appToManage!!.pwaId != null
                        if (isCustomPwa) {
                            if (!appToManage!!.webUrl.isNullOrBlank()) {
                                // OPEN IN BROWSER
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            appToManage?.let { app ->
                                                if (!app.webUrl.isNullOrBlank()) {
                                                    com.tarslauncher.app.utils.PwaLauncherHelper.launchPwa(context, app.webUrl)
                                                }
                                            }
                                            showManageDialog = false
                                            appToManage = null
                                        }
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "OPEN IN BROWSER",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        letterSpacing = 2.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                HorizontalDivider(
                                    color = Color.White.copy(alpha = 0.1f),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }

                            // DELETE PWA
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        appToManage?.let { app ->
                                            val pwaId = app.pwaId ?: app.packageName.removePrefix("pwa_")
                                            onDeletePwa(pwaId)
                                        }
                                        showManageDialog = false
                                        appToManage = null
                                    }
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "DELETE PWA",
                                    color = Color(0xFFFF4444),
                                    fontSize = 11.sp,
                                    letterSpacing = 2.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            // UNINSTALL
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        appToManage?.let { app ->
                                            try {
                                                val intent = Intent(Intent.ACTION_DELETE).apply {
                                                    data = Uri.parse("package:${app.packageName}")
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Error", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        showManageDialog = false
                                        appToManage = null
                                    }
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "UNINSTALL",
                                    color = Color(0xFFFF4444),
                                    fontSize = 11.sp,
                                    letterSpacing = 2.sp,
                                    fontWeight = FontWeight.Medium
                                )
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
fun FolderAppItem(
    app: AppInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    iconPackLoader: IconPackLoader?
) {
    val isPackLoaded = iconPackLoader?.isLoaded == true
    val appIcon = remember(app.packageName, iconPackLoader, isPackLoaded) {
        try {
            val activityName = app.activityName
            iconPackLoader?.getImageBitmapForApp(app.packageName, activityName, app.icon)
                ?: getAppImageBitmap(app.packageName, app.icon)
        } catch (e: Exception) {
            null
        }
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(48.dp)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
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

@Composable
fun ClockHeader(
    showClock: Boolean,
    showTarsHeader: Boolean,
    clockFontSize: androidx.compose.ui.unit.TextUnit,
    tarsFontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier
) {
    if (!showClock && !showTarsHeader) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentTime by remember { mutableStateOf("00:00") }

    if (showClock) {
        DisposableEffect(context, lifecycleOwner) {
            fun updateTime() {
                currentTime = try {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                } catch (_: Exception) {
                    "00:00"
                }
            }

            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                    updateTime()
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_TIME_TICK)
                addAction(android.content.Intent.ACTION_TIME_CHANGED)
                addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            }

            var isRegistered = false

            fun register() {
                if (!isRegistered) {
                    updateTime()
                    try {
                        ContextCompat.registerReceiver(
                            context,
                            receiver,
                            filter,
                            ContextCompat.RECEIVER_NOT_EXPORTED
                        )
                        isRegistered = true
                    } catch (_: Exception) {}
                }
            }

            fun unregister() {
                if (isRegistered) {
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (_: Exception) {}
                    isRegistered = false
                }
            }

            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> register()
                    Lifecycle.Event.ON_STOP -> unregister()
                    else -> {}
                }
            }

            lifecycleOwner.lifecycle.addObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                register()
            }

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                unregister()
            }
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showClock) {
            Text(
                text = currentTime,
                color = Color.White,
                fontSize = clockFontSize,
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
