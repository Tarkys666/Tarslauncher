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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
        val buttonLabel: String? = null,
        val sliderRange: ClosedFloatingPointRange<Float>? = null,
        val sliderSteps: Int = 0,
        val sliderValueUnit: String? = null
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
    appDrawerRows: Int = if (dualAppDrawer) 2 else 1,
    onAppDrawerRowsChange: (Int) -> Unit = {},
    gridSize: GridSize,
    onGridSizeChange: (GridSize) -> Unit,
    installedIconPacks: List<IconPackInfo>,
    selectedIconPack: String?,
    onIconPackSelected: (String?) -> Unit,
    showTarsHeader: Boolean,
    onShowTarsHeaderChange: (Boolean) -> Unit,
    showClock: Boolean,
    onShowClockChange: (Boolean) -> Unit,
    showAsciiDiamond: Boolean = false,
    onShowAsciiDiamondChange: (Boolean) -> Unit = {},
    asciiDiamondScale: Int = 100,
    onAsciiDiamondScaleChange: (Int) -> Unit = {},
    asciiDiamondOffsetY: Int = 0,
    onAsciiDiamondOffsetYChange: (Int) -> Unit = {},
    asciiDiamondFps: Int = 60,
    onAsciiDiamondFpsChange: (Int) -> Unit = {},
    asciiDiamondSpeed: Int = 100,
    onAsciiDiamondSpeedChange: (Int) -> Unit = {},
    appDrawerIconSize: Int,
    onAppDrawerIconSizeChange: (Int) -> Unit,
    showFolderGrid: Boolean = true,
    onShowFolderGridChange: (Boolean) -> Unit = {},
    appDrawerOffsetY: Int = 24,
    onAppDrawerOffsetYChange: (Int) -> Unit = {},
    onStartAdjustAppDrawerPosition: () -> Unit = {},
    appVersion: String = "1.2.0",
    installedPwas: List<PwaInfo> = emptyList(),
    pwaPresets: List<PwaInfo> = emptyList(),
    onAddPwa: (name: String, url: String) -> Unit = { _, _ -> },
    onDeletePwa: (pwaId: String) -> Unit = {},
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
    
    // Tree state management - expand all 5 clean categories by default
    var expandedCategories by remember {
        mutableStateOf(
            setOf(
                "HOME SCREEN & GRID",
                "APP DRAWER",
                "3D ASCII DIAMOND",
                "WEB APPS (PWA)",
                "SYSTEM & ABOUT"
            )
        )
    }
    var showGridSizeDialog by remember { mutableStateOf(false) }
    var showIconPackDialog by remember { mutableStateOf(false) }
    var showAddPwaDialog by remember { mutableStateOf(false) }
    var showManagePwasDialog by remember { mutableStateOf(false) }
    var showDiamondFpsDialog by remember { mutableStateOf(false) }
    var showAppDrawerRowsDialog by remember { mutableStateOf(false) }

    // Create organized settings tree structure
    val settingsTree = remember(
        showAppDrawer,
        dualAppDrawer,
        appDrawerRows,
        showFolderGrid,
        appDrawerOffsetY,
        showTarsHeader,
        showClock,
        showAsciiDiamond,
        asciiDiamondScale,
        asciiDiamondOffsetY,
        asciiDiamondFps,
        asciiDiamondSpeed,
        appDrawerIconSize,
        gridSize,
        selectedIconPack,
        isDefaultLauncherSet,
        installedPwas.size,
        appVersion
    ) {
        listOf(
            SettingsItem.Category(
                title = "HOME SCREEN & GRID",
                icon = "",
                children = buildList {
                    add(SettingsItem.Setting(
                        title = "Center App Grid",
                        description = "Show middle app and folder grid on home screen",
                        type = SettingType.TOGGLE,
                        currentValue = showFolderGrid,
                        onValueChange = { onShowFolderGridChange(it as Boolean) }
                    ))
                    if (showFolderGrid) {
                        add(SettingsItem.Setting(
                            title = "Grid Size",
                            description = "Current: $gridSize",
                            type = SettingType.BUTTON,
                            onClick = { showGridSizeDialog = true },
                            buttonLabel = "SELECT"
                        ))
                    }
                    add(SettingsItem.Setting(
                        title = "Icon Pack",
                        description = selectedIconPack ?: "Default",
                        type = SettingType.BUTTON,
                        onClick = { showIconPackDialog = true },
                        buttonLabel = "SELECT"
                    ))
                    add(SettingsItem.Setting(
                        title = "FACET Header",
                        description = "Show FACET text on home screen",
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
                }
            ),
            SettingsItem.Category(
                title = "APP DRAWER",
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
                            title = "App Drawer Rows",
                            description = "$appDrawerRows Row${if (appDrawerRows > 1) "s (Alternating kinetic flow)" else " (Classic single row)"}",
                            type = SettingType.BUTTON,
                            onClick = { showAppDrawerRowsDialog = true },
                            buttonLabel = "ROWS"
                        ))
                        add(SettingsItem.Setting(
                            title = "App Drawer Position",
                            description = "Bottom offset: ${appDrawerOffsetY}dp (Drag to adjust)",
                            type = SettingType.BUTTON,
                            onClick = onStartAdjustAppDrawerPosition,
                            buttonLabel = "ADJUST"
                        ))
                        add(SettingsItem.Setting(
                            title = "App Drawer Icon Size",
                            description = "Adjust drawer icon size (20-36dp)",
                            type = SettingType.SLIDER,
                            currentValue = appDrawerIconSize,
                            onValueChange = { onAppDrawerIconSizeChange(it as Int) }
                        ))
                    }
                }
            ),
            SettingsItem.Category(
                title = "3D ASCII DIAMOND",
                icon = "",
                children = buildList {
                    add(SettingsItem.Setting(
                        title = "ASCII Diamond",
                        description = if (showAsciiDiamond) "Enabled (Clock & Facet logo hidden)" else "3D rotating ASCII Diamond background",
                        type = SettingType.TOGGLE,
                        currentValue = showAsciiDiamond,
                        onValueChange = { onShowAsciiDiamondChange(it as Boolean) }
                    ))
                    if (showAsciiDiamond) {
                        add(SettingsItem.Setting(
                            title = "Diamond Size",
                            description = "Adjust 3D diamond scale",
                            type = SettingType.SLIDER,
                            currentValue = asciiDiamondScale,
                            onValueChange = { onAsciiDiamondScaleChange(it as Int) },
                            sliderRange = 40f..180f,
                            sliderSteps = 27,
                            sliderValueUnit = "%"
                        ))
                        add(SettingsItem.Setting(
                            title = "Diamond Position (Y)",
                            description = if (asciiDiamondOffsetY < 0) "${-asciiDiamondOffsetY}% Up" else if (asciiDiamondOffsetY > 0) "${asciiDiamondOffsetY}% Down" else "Center",
                            type = SettingType.SLIDER,
                            currentValue = asciiDiamondOffsetY,
                            onValueChange = { onAsciiDiamondOffsetYChange(it as Int) },
                            sliderRange = -40f..40f,
                            sliderSteps = 15,
                            sliderValueUnit = "%"
                        ))
                        add(SettingsItem.Setting(
                            title = "Diamond Rotation Speed",
                            description = if (asciiDiamondSpeed == 0) "Stopped (drag to rotate)" else "$asciiDiamondSpeed% speed",
                            type = SettingType.SLIDER,
                            currentValue = asciiDiamondSpeed,
                            onValueChange = { onAsciiDiamondSpeedChange(it as Int) },
                            sliderRange = 0f..200f,
                            sliderSteps = 19,
                            sliderValueUnit = "%"
                        ))
                        add(SettingsItem.Setting(
                            title = "Diamond Target FPS",
                            description = "Current: $asciiDiamondFps FPS",
                            type = SettingType.BUTTON,
                            onClick = { showDiamondFpsDialog = true },
                            buttonLabel = "SELECT"
                        ))
                    }
                }
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
                title = "SYSTEM & ABOUT",
                icon = "",
                children = listOf(
                    SettingsItem.Setting(
                        title = "Default Launcher",
                        description = if (isDefaultLauncherSet) "Facet is default launcher" else "Set Facet as default home screen",
                        type = SettingType.LAUNCHER_STATUS,
                        currentValue = isDefaultLauncherSet,
                        onClick = {
                            openDefaultHomeSettings(context)
                        }
                    ),
                    SettingsItem.Setting(
                        title = "Check for Updates",
                        description = "Check GitHub for new releases",
                        type = SettingType.BUTTON,
                        onClick = onCheckForUpdates,
                        buttonLabel = "CHECK"
                    ),
                    SettingsItem.Setting(
                        title = "Version",
                        description = "Version $appVersion",
                        type = SettingType.INFO
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
        
        // Categorized settings cards
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
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
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
                border = BorderStroke(1.dp, Color(0xFF333333))
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

    if (showDiamondFpsDialog) {
        Dialog(onDismissRequest = { showDiamondFpsDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF333333)),
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "TARGET FPS",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    listOf(
                        30 to "30 FPS (Battery Saver)",
                        45 to "45 FPS (Balanced)",
                        60 to "60 FPS (Default)",
                        90 to "90 FPS (Smooth)",
                        120 to "120 FPS (Ultra)"
                    ).forEach { (fpsVal, label) ->
                        TarsOptionButton(
                            text = label,
                            isSelected = asciiDiamondFps == fpsVal,
                            onClick = {
                                onAsciiDiamondFpsChange(fpsVal)
                                showDiamondFpsDialog = false
                            },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { showDiamondFpsDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Black,
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.Transparent),
                        shape = RoundedCornerShape(4.dp)
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

    if (showAppDrawerRowsDialog) {
        var tempRows by remember(appDrawerRows) { mutableIntStateOf(appDrawerRows) }
        Dialog(onDismissRequest = { showAppDrawerRowsDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF333333))
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "APP DRAWER ROWS",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "$tempRows Row${if (tempRows > 1) "s" else ""}",
                        color = Color(0xFF64B5F6),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (tempRows > 1) "Alternating kinetic flow (one right, one left)" else "Single standard row",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Slider 1 to 20
                    Slider(
                        value = tempRows.toFloat(),
                        onValueChange = { tempRows = it.toInt().coerceIn(1, 20) },
                        valueRange = 1f..20f,
                        steps = 18,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color(0xFF333333)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "QUICK PRESETS",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf(1, 2, 3, 5, 10, 20).forEach { r ->
                            Box(
                                modifier = Modifier
                                    .clickable { tempRows = r }
                                    .background(
                                        if (tempRows == r) Color.White else Color(0xFF1E1E1E),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 9.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (r == 1) "1" else "$r",
                                    color = if (tempRows == r) Color.Black else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (tempRows == r) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            onAppDrawerRowsChange(tempRows)
                            showAppDrawerRowsDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("SAVE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsCategoryItem(
    category: SettingsItem.Category,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0D0D0D)
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF222222))
    ) {
        Column {
            // Category header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandToggle() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = category.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp
                    )
                }
                Text(
                    text = if (isExpanded) "▼" else "▶",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
            
            // Category children
            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    category.children.forEach { setting ->
                        if (setting is SettingsItem.Setting) {
                            SettingsItemView(
                                setting = setting
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsItemView(
    setting: SettingsItem.Setting
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black
        ),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFF1C1C1C))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = setting.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal
                )
                Text(
                    text = setting.description,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
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
                            containerColor = Color(0xFF181818),
                            contentColor = Color.White,
                            disabledContainerColor = Color.Black,
                            disabledContentColor = Color(0xFF555555)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF333333)),
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
                    // No-op
                }
                SettingType.LAUNCHER_STATUS -> {
                    val isDefault = setting.currentValue as? Boolean ?: false
                    
                    Button(
                        onClick = { setting.onClick?.invoke() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF181818),
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color(0xFF333333)),
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
                    val range = setting.sliderRange ?: 20f..36f
                    val steps = if (setting.sliderSteps > 0) setting.sliderSteps else if (setting.sliderRange == null) 15 else 0
                    val unit = setting.sliderValueUnit ?: "dp"
                    
                    Column {
                        Text(
                            text = if (unit == "%") "${sliderPosition.toInt()}%" else "${sliderPosition.toInt()}$unit",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.align(Alignment.End)
                        )
                        Slider(
                            value = sliderPosition,
                            onValueChange = { sliderPosition = it },
                            onValueChangeFinished = {
                                setting.onValueChange?.invoke(sliderPosition.toInt())
                            },
                            valueRange = range,
                            steps = steps,
                            modifier = Modifier
                                .width(150.dp)
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
            border = BorderStroke(1.dp, Color(0xFF333333))
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
