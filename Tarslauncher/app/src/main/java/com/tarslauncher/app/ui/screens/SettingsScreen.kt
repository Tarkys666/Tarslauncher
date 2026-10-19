package com.tarslauncher.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tarslauncher.app.data.model.GridSize
import com.tarslauncher.app.data.model.IconPackInfo
import com.tarslauncher.app.data.model.PwaInfo
import com.tarslauncher.app.utils.isDefaultLauncher
import com.tarslauncher.app.utils.openDefaultHomeSettings


// Tree-based settings data structure
@Immutable
sealed class SettingsItem {
    @Immutable
    data class Category(
        val title: String,
        val icon: String,
        val children: List<SettingsItem>,
        val isExpanded: Boolean = false
    ) : SettingsItem()
    
    @Immutable
    data class Setting(
        val title: String,
        val description: String,
        val type: SettingType,
        val currentValue: Any? = null,
        val onValueChange: ((Any) -> Unit)? = null,
        val onClick: (() -> Unit)? = null,
        val enabled: Boolean = true,
        val buttonLabel: String? = null
    ) : SettingsItem()
}

enum class SettingType {
    TOGGLE,
    BUTTON,
    SELECTION,
    INFO,
    LAUNCHER_STATUS,
    SLIDER
}

private val Grid2xOptions = listOf(GridSize(2, 1), GridSize(2, 2), GridSize(2, 3), GridSize(2, 4))
private val Grid3xOptions = listOf(GridSize(3, 1), GridSize(3, 2), GridSize(3, 3))
private val Grid4xOptions = listOf(GridSize(4, 1), GridSize(4, 2), GridSize(4, 3), GridSize(4, 4))
private val Grid5xOptions = listOf(GridSize(5, 1), GridSize(5, 2), GridSize(5, 3), GridSize(5, 4), GridSize(5, 5))

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    showAppDrawer: Boolean,
    onShowAppDrawerChange: (Boolean) -> Unit,
    dualAppDrawer: Boolean = false,
    onDualAppDrawerChange: (Boolean) -> Unit = {},
    gridSize: GridSize,
    onGridSizeChange: (GridSize) -> Unit,
    installedIconPacks: List<IconPackInfo>,
    selectedIconPack: String?,
    onIconPackSelected: (String?) -> Unit,
    buttonColorMode: String,
    onButtonColorModeChange: (String) -> Unit,
    showTarsHeader: Boolean,
    onShowTarsHeaderChange: (Boolean) -> Unit,
    showClock: Boolean,
    onShowClockChange: (Boolean) -> Unit,
    appDrawerIconSize: Int,
    onAppDrawerIconSizeChange: (Int) -> Unit,
    appVersion: String = "1.2.0",
    installedPwas: List<PwaInfo> = emptyList(),
    pwaPresets: List<PwaInfo> = emptyList(),
    onAddPwa: (name: String, url: String) -> Unit = { _, _ -> },
    onDeletePwa: (pwaId: String) -> Unit = {},
    isSyncing: Boolean = false,
    lastBackupTime: Long = 0L,
    onExportBackup: (Uri) -> Unit = {},
    onImportBackup: (Uri) -> Unit = {},
    onCheckForUpdates: () -> Unit = {}
) {
    val context = LocalContext.current
    
    // Handle system back button
    BackHandler {
        onBackClick()
    }
    
    // Default launcher state
    var isDefaultLauncherSet by remember { mutableStateOf(false) }

    // Refresh launcher status when screen becomes visible (user returns from settings)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isDefaultLauncherSet = isDefaultLauncher(context)
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        isDefaultLauncherSet = isDefaultLauncher(context)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Tree state management
    var expandedCategories by remember { mutableStateOf(setOf<String>()) }
    var showGridSizeDialog by remember { mutableStateOf(false) }
    var showIconPackDialog by remember { mutableStateOf(false) }
    var showAddPwaDialog by remember { mutableStateOf(false) }
    var showManagePwasDialog by remember { mutableStateOf(false) }

    // Local backup: Storage Access Framework file picker (offline, zero permissions)
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) onExportBackup(uri) }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportBackup(uri) }
    
    // Create settings tree structure - memoized to prevent reallocations on every composition
    val settingsTree = remember(
        showAppDrawer,
        dualAppDrawer,
        showTarsHeader,
        showClock,
        appDrawerIconSize,
        gridSize,
        buttonColorMode,
        selectedIconPack,
        isDefaultLauncherSet,
        installedPwas.size,
        appVersion,
        isSyncing,
        lastBackupTime
    ) {
        listOf(
            SettingsItem.Category(
                title = "INTERFACE",
                icon = "",
                children = buildList {
                    add(SettingsItem.Setting(
                        title = "App Drawer",
                        description = "Show app list at bottom",
                        type = SettingType.TOGGLE,
                        currentValue = showAppDrawer,
                        onValueChange = { onShowAppDrawerChange(it as Boolean) }
                    ))
                    if (showAppDrawer) {
                        add(SettingsItem.Setting(
                            title = "Dual Row App Drawer",
                            description = "Two interconnected rows scrolling in opposite directions",
                            type = SettingType.TOGGLE,
                            currentValue = dualAppDrawer,
                            onValueChange = { onDualAppDrawerChange(it as Boolean) }
                        ))
                    }
                    add(SettingsItem.Setting(
                        title = "TARS Header",
                        description = "Show TARS text on home screen",
                        type = SettingType.TOGGLE,
                        currentValue = showTarsHeader,
                        onValueChange = { onShowTarsHeaderChange(it as Boolean) }
                    ))
                    add(SettingsItem.Setting(
                        title = "Clock",
                        description = "Show clock on home screen",
                        type = SettingType.TOGGLE,
                    currentValue = showClock,
                    onValueChange = { onShowClockChange(it as Boolean) }
                ))
                add(SettingsItem.Setting(
                    title = "App Drawer Icon Size",
                    description = "Adjust app drawer icon size (20-36dp)",
                    type = SettingType.SLIDER,
                    currentValue = appDrawerIconSize,
                    onValueChange = { onAppDrawerIconSizeChange(it as Int) }
                ))
                add(SettingsItem.Setting(
                    title = "Grid Size",
                    description = "Current: $gridSize",
                    type = SettingType.BUTTON,
                    onClick = { showGridSizeDialog = true },
                    buttonLabel = "SELECT"
                ))
                add(SettingsItem.Setting(
                    title = "Button Color",
                    description = "Current: ${buttonColorMode.uppercase()}",
                    type = SettingType.SELECTION,
                    currentValue = buttonColorMode
                ))
            }
        ),
        SettingsItem.Category(
            title = "CUSTOMIZATION",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Icon Pack",
                    description = selectedIconPack ?: "Default",
                    type = SettingType.BUTTON,
                    onClick = { showIconPackDialog = true },
                    buttonLabel = "SELECT"
                )
            )
        ),
        SettingsItem.Category(
            title = "SYSTEM",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Default Launcher",
                    description = if (isDefaultLauncherSet) "TARS is default launcher" else "Set TARS as default home screen",
                    type = SettingType.LAUNCHER_STATUS,
                    currentValue = isDefaultLauncherSet,
                    onClick = {
                        openDefaultHomeSettings(context)
                    }
                )
            )
        ),
        SettingsItem.Category(
            title = "WEB APPS (PWA)",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Add Web App",
                    description = "Install websites as apps with presets or URL",
                    type = SettingType.BUTTON,
                    onClick = { showAddPwaDialog = true },
                    buttonLabel = "ADD"
                ),
                SettingsItem.Setting(
                    title = "Manage Web Apps",
                    description = "${installedPwas.size} web app(s) installed",
                    type = SettingType.BUTTON,
                    onClick = { showManagePwasDialog = true },
                    buttonLabel = "MANAGE"
                )
            )
        ),
        SettingsItem.Category(
            title = "BACKUP & RESTORE",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Export Backup",
                    description = if (isSyncing) "Exporting..." else if (lastBackupTime > 0) "Last: " + SimpleDateFormat("dd MMM, HH:mm", Locale.US).format(Date(lastBackupTime)) else "Save layout and settings to a file",
                    type = SettingType.BUTTON,
                    onClick = { exportLauncher.launch("tars-backup.json") },
                    enabled = !isSyncing,
                    buttonLabel = "EXPORT"
                ),
                SettingsItem.Setting(
                    title = "Import Backup",
                    description = if (isSyncing) "Importing..." else "Restore layout and settings from a file",
                    type = SettingType.BUTTON,
                    onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
                    enabled = !isSyncing,
                    buttonLabel = "IMPORT"
                )
            )
        ),
        SettingsItem.Category(
            title = "ABOUT",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Version",
                    description = "Version $appVersion",
                    type = SettingType.INFO
                ),
                SettingsItem.Setting(
                    title = "Check for Updates",
                    description = "Check GitHub for new releases",
                    type = SettingType.BUTTON,
                    onClick = onCheckForUpdates,
                    buttonLabel = "CHECK"
                )
            )
        )
    )
}
    
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 64.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
            .verticalScroll(scrollState)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBackClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White
                ),
                border = BorderStroke(1.dp, Color.Transparent),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "← BACK",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Text(
                text = "S E T T I N G S",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraLight,
                letterSpacing = 3.sp
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Tree-based settings
        settingsTree.forEach { category ->
            SettingsCategoryItem(
                category = category,
                isExpanded = expandedCategories.contains(category.title),
                onExpandToggle = {
                    expandedCategories = if (expandedCategories.contains(category.title)) {
                        expandedCategories - category.title
                    } else {
                        expandedCategories + category.title
                    }
                },
                onButtonColorModeChange = onButtonColorModeChange,
                buttonColorMode = buttonColorMode
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        
    }
    
    // Dialogs
    if (showGridSizeDialog) {
        Dialog(onDismissRequest = { showGridSizeDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.Black)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Select Grid Size",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    LazyColumn(
                        modifier = Modifier.height(300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val gridSections = listOf(
                            "2x Grid Options" to Grid2xOptions,
                            "3x Grid Options" to Grid3xOptions,
                            "4x Grid Options" to Grid4xOptions,
                            "5x Grid Options" to Grid5xOptions
                        )
                        gridSections.forEachIndexed { index, (title, options) ->
                            item {
                                if (index > 0) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                Text(
                                    text = title,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(options, key = { it.serialize() }) { option ->
                                TarsOptionButton(
                                    text = option.serialize(),
                                    isSelected = gridSize == option,
                                    onClick = {
                                        onGridSizeChange(option)
                                        showGridSizeDialog = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showGridSizeDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Black,
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.Transparent),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            text = "CLOSE",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
        }
    
    if (showIconPackDialog) {
        IconPackSelectionDialog(
            iconPacks = installedIconPacks,
            onDismiss = { showIconPackDialog = false },
            onIconPackSelected = { pack ->
                onIconPackSelected(pack)
                showIconPackDialog = false
            }
        )
    }

    if (showAddPwaDialog) {
        AddPwaDialog(
            presets = pwaPresets,
            onDismiss = { showAddPwaDialog = false },
            onAddPwa = onAddPwa
        )
    }

    if (showManagePwasDialog) {
        ManagePwasDialog(
            pwas = installedPwas,
            onDismiss = { showManagePwasDialog = false },
            onDeletePwa = onDeletePwa,
            onAddNewClick = {
                showManagePwasDialog = false
                showAddPwaDialog = true
            }
        )
    }
}

@Composable
fun SettingsCategoryItem(
    category: SettingsItem.Category,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    onButtonColorModeChange: (String) -> Unit,
    buttonColorMode: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.Black)
    ) {
        Column {
            // Category header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandToggle() }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = category.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = if (isExpanded) "▼" else "▶",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
            
            // Category children
            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    category.children.forEach { setting ->
                        if (setting is SettingsItem.Setting) {
                            SettingsItemView(
                                setting = setting,
                                onButtonColorModeChange = onButtonColorModeChange,
                                buttonColorMode = buttonColorMode
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsItemView(
    setting: SettingsItem.Setting,
    onButtonColorModeChange: (String) -> Unit,
    buttonColorMode: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black
        ),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.Black)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = setting.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal
                )
                Text(
                    text = setting.description,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
            
            when (setting.type) {
                SettingType.TOGGLE -> {
                    val isChecked = setting.currentValue as? Boolean ?: false
                    
                    Switch(
                        checked = isChecked,
                        onCheckedChange = { newValue ->
                            setting.onValueChange?.invoke(newValue)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color.White.copy(alpha = 0.3f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
                        )
                    )
                }
                SettingType.BUTTON -> {
                    val label = setting.buttonLabel ?: when {
                        setting.title.contains("Export", ignoreCase = true) -> "EXPORT"
                        setting.title.contains("Import", ignoreCase = true) -> "IMPORT"
                        setting.title.contains("Check", ignoreCase = true) || setting.title.contains("Update", ignoreCase = true) -> "CHECK"
                        setting.title.contains("Manage", ignoreCase = true) -> "MANAGE"
                        setting.title.contains("Add", ignoreCase = true) -> "ADD"
                        setting.title.contains("Grid", ignoreCase = true) || setting.title.contains("Icon", ignoreCase = true) -> "SELECT"
                        setting.title.contains("Note", ignoreCase = true) -> "VIEW"
                        else -> "OPEN"
                    }

                    Button(
                        onClick = { setting.onClick?.invoke() },
                        enabled = setting.enabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Black,
                            contentColor = Color.White,
                            disabledContainerColor = Color.Black,
                            disabledContentColor = Color(0xFF555555)
                        ),
                        border = BorderStroke(1.dp, Color.Transparent),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (setting.enabled) Color.White else Color(0xFF555555),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 1.sp
                        )
                    }
                }
                SettingType.SELECTION -> {
                    if (setting.title == "Button Color") {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("normal", "black").forEach { mode ->
                                val isSelected = buttonColorMode == mode
                                
                                val buttonBgColor by animateColorAsState(
                                    targetValue = if (isSelected)
                                        Color(0xFF1E1E1E)
                                    else
                                        Color.Black,
                                    animationSpec = tween(durationMillis = 250),
                                    label = "buttonBgColor"
                                )
                                
                                Button(
                                    onClick = { onButtonColorModeChange(mode) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = buttonBgColor,
                                        contentColor = Color.White
                                    ),
                                    border = BorderStroke(1.dp, Color.Transparent),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.size(width = 60.dp, height = 32.dp),
                                    contentPadding = PaddingValues(4.dp)
                                ) {
                                    Text(
                                        text = mode.uppercase(),
                                        color = if (isSelected) Color.White else Color(0xFF888888),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Light,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
                SettingType.LAUNCHER_STATUS -> {
                    val isDefault = setting.currentValue as? Boolean ?: false
                    
                    Button(
                        onClick = { setting.onClick?.invoke() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Black,
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.Transparent),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isDefault) "✓ DEFAULT" else "SET DEFAULT",
                            color = if (isDefault) Color.White else Color(0xFF888888),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 1.sp
                        )
                    }
                }
                SettingType.SLIDER -> {
                    val currentValue = setting.currentValue as? Int ?: 32
                    var sliderPosition by remember(currentValue) { mutableFloatStateOf(currentValue.toFloat()) }
                    
                    Column {
                        // Değer gösterimi
                        Text(
                            text = "${sliderPosition.toInt()}dp",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.align(Alignment.End)
                        )
                        
                        // Uzun yatay slider with debounce on finish
                        Slider(
                            value = sliderPosition,
                            onValueChange = { sliderPosition = it },
                            onValueChangeFinished = {
                                setting.onValueChange?.invoke(sliderPosition.toInt())
                            },
                            valueRange = 20f..36f, // 20dp - 36dp aralığı
                            steps = 15, // 1dp artışlarla (20,21,22...36)
                            modifier = Modifier
                                .width(150.dp) // Uzun yatay slider
                                .height(24.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.Gray
                            )
                        )
                    }
                }
                SettingType.INFO -> {
                    // Just display info, no interaction
                }
            }
        }
    }
}

@Composable
fun TarsOptionButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) 
                Color(0xFF1E1E1E) 
            else 
                Color.Black,
            contentColor = Color.White
        ),
        border = BorderStroke(1.dp, Color.Transparent),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Light
        )
    }
}

@Composable
fun IconPackSelectionDialog(
    iconPacks: List<IconPackInfo>,
    onDismiss: () -> Unit,
    onIconPackSelected: (String?) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.Black
            ),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color.Transparent)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Select Icon Pack",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(
                    modifier = Modifier.height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Button(
                            onClick = {
                                onIconPackSelected(null)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, Color.Transparent),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Default",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Light,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                    items(iconPacks, key = { it.packageName }) { iconPack ->
                        Button(
                            onClick = {
                                onIconPackSelected(iconPack.packageName)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, Color.Transparent),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = iconPack.name,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Light,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black,
                        contentColor = Color.White
                    ),
                    border = BorderStroke(1.dp, Color.Transparent),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = "CLOSE",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}



