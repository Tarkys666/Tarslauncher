package com.tarslauncher.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tarslauncher.app.MainActivity
import com.tarslauncher.app.data.model.AppInfo
import com.tarslauncher.app.data.model.IconPackInfo
import com.tarslauncher.app.utils.isDefaultLauncher

// Tree-based settings data structure
sealed class SettingsItem {
    data class Category(
        val title: String,
        val icon: String,
        val children: List<SettingsItem>,
        val isExpanded: Boolean = false
    ) : SettingsItem()
    
    data class Setting(
        val title: String,
        val description: String,
        val type: SettingType,
        val currentValue: Any? = null,
        val onValueChange: ((Any) -> Unit)? = null,
        val onClick: (() -> Unit)? = null
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

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onLocationTrackingClick: () -> Unit,
    showAppDrawer: Boolean,
    batteryFriendly: Boolean,
    onShowAppDrawerChange: (Boolean) -> Unit,
    onBatteryFriendlyChange: (Boolean) -> Unit,
    gridSize: String,
    onGridSizeChange: (String) -> Unit,
    installedIconPacks: List<IconPackInfo>,
    selectedIconPack: String?,
    onIconPackSelected: (String?) -> Unit,
    buttonColorMode: String,
    onButtonColorModeChange: (String) -> Unit,
    folderLocked: Boolean,
    onFolderLockedChange: (Boolean) -> Unit,
    doubleTapLockEnabled: Boolean,
    onDoubleTapLockEnabledChange: (Boolean) -> Unit,
    showTarsHeader: Boolean,
    onShowTarsHeaderChange: (Boolean) -> Unit,

    showClock: Boolean,
    onShowClockChange: (Boolean) -> Unit,
    appDrawerIconSize: Int,
    onAppDrawerIconSizeChange: (Int) -> Unit,
    internetCheckEnabled: Boolean,
    onInternetCheckEnabledChange: (Boolean) -> Unit,
    onMuteAll: () -> Unit,
    onRestoreSounds: () -> Unit,
    adaptiveVolumeEnabled: Boolean,
    onAdaptiveVolumeEnabledChange: (Boolean) -> Unit,
    adaptiveVolumeSensitivity: Float,
    onAdaptiveVolumeSensitivityChange: (Float) -> Unit,
    adaptiveVolumeApps: Set<String>,
    onAdaptiveAppChecked: (String, Boolean) -> Unit,
    installedApps: List<AppInfo>
) {
    val context = LocalContext.current
    
    // Handle system back button
    BackHandler {
        onBackClick()
    }
    
    // Default launcher state
    var isDefaultLauncherSet by remember { mutableStateOf(false) }
    
    // Check default launcher status on composition and when returning from system settings
    LaunchedEffect(Unit) {
        isDefaultLauncherSet = isDefaultLauncher(context)
    }
    
    // Refresh launcher status when screen becomes visible (user returns from settings)
    // Lifecycle aware default launcher status check
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isDefaultLauncherSet = isDefaultLauncher(context)
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        // Initial check
        isDefaultLauncherSet = isDefaultLauncher(context)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Tree state management
    var expandedCategories by remember { mutableStateOf(setOf<String>()) }
    var showGridSizeDialog by remember { mutableStateOf(false) }
    var showIconPackDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }
    var showAppSelectionDialog by remember { mutableStateOf(false) }
    
    val grid2xOptions = listOf("2x1", "2x2", "2x3", "2x4")
    val grid3xOptions = listOf("3x1", "3x2", "3x3")
    val grid4xOptions = listOf("4x1", "4x2", "4x3", "4x4")
    val grid5xOptions = listOf("5x1", "5x2", "5x3", "5x4", "5x5")
    
    // Create settings tree structure - remove remember to allow state updates
    val settingsTree = listOf(
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
                // (Removed) Dark Mode button
                add(SettingsItem.Setting(
                    title = "Grid Size",
                    description = "Current: $gridSize",
                    type = SettingType.BUTTON,
                    onClick = { showGridSizeDialog = true }
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
                    onClick = { showIconPackDialog = true }
                )
            )
        ),
        SettingsItem.Category(
            title = "SECURITY & FOLDERS",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Folder Lock",
                    description = "Prevent adding/removing apps from folders",
                    type = SettingType.TOGGLE,
                    currentValue = folderLocked,
                    onValueChange = { onFolderLockedChange(it as Boolean) }
                ),
                SettingsItem.Setting(
                    title = "Double-tap to Lock",
                    description = "Turn screen off with double-tap on home",
                    type = SettingType.TOGGLE,
                    currentValue = doubleTapLockEnabled,
                    onValueChange = { onDoubleTapLockEnabledChange(it as Boolean) }
                ),
                SettingsItem.Setting(
                    title = "Default Launcher",
                    description = if (isDefaultLauncherSet) "TARS is set as default" else "Set TARS as default home screen",
                    type = SettingType.LAUNCHER_STATUS,
                    currentValue = isDefaultLauncherSet,
                    onClick = {
                        val intent = Intent(Settings.ACTION_HOME_SETTINGS)
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        }
                    }
                )
            )
        ),
        SettingsItem.Category(
            title = "NETWORK",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Internet Watchdog",
                    description = "Periodically check real internet access",
                    type = SettingType.TOGGLE,
                    currentValue = internetCheckEnabled,
                    onValueChange = { onInternetCheckEnabledChange(it as Boolean) }
                )
            )
        ),
        SettingsItem.Category(
            title = "TRACKING & LOCATION",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Tracking System",
                    description = "Open cellular base-station tracking",
                    type = SettingType.BUTTON,
                    onClick = onLocationTrackingClick
                )
            )
        ),
        SettingsItem.Category(
            title = "SYSTEM AUDIO",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Mute All Systems",
                    description = "Silence ringer, music, and notifications",
                    type = SettingType.BUTTON,
                    onClick = onMuteAll
                ),
                SettingsItem.Setting(
                    title = "Restore All Sounds",
                    description = "Restore sounds to previous levels",
                    type = SettingType.BUTTON,
                    onClick = onRestoreSounds
                )
            )
        ),
        SettingsItem.Category(
            title = "ADAPTIVE VOLUME",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Smart Noise Shield",
                    description = "Adjust volume based on ambient noise",
                    type = SettingType.TOGGLE,
                    currentValue = adaptiveVolumeEnabled,
                    onValueChange = { onAdaptiveVolumeEnabledChange(it as Boolean) }
                ),
                SettingsItem.Setting(
                    title = "Sensitivity",
                    description = "Aggressiveness: ${(adaptiveVolumeSensitivity * 100).toInt()}%",
                    type = SettingType.SLIDER,
                    currentValue = (adaptiveVolumeSensitivity * 100).toInt(),
                    onValueChange = { onAdaptiveVolumeSensitivityChange((it as Int) / 100f) }
                ),
                SettingsItem.Setting(
                    title = "Selected Apps",
                    description = "${adaptiveVolumeApps.size} apps monitored",
                    type = SettingType.BUTTON,
                    onClick = { showAppSelectionDialog = true }
                )
            )
        ),
        SettingsItem.Category(
            title = "PERFORMANCE",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Battery Friendly",
                    description = "Reduce animations and optimize performance",
                    type = SettingType.TOGGLE,
                    currentValue = batteryFriendly,
                    onValueChange = { onBatteryFriendlyChange(it as Boolean) }
                )
            )
        ),
        SettingsItem.Category(
            title = "ABOUT",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Privacy Policy",
                    description = "Tarslauncher – Privacy Policy",
                    type = SettingType.BUTTON,
                    onClick = { showPrivacyPolicyDialog = true }
                )
            )
        )
    )
    
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
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
                    containerColor = Color.Black.copy(alpha = 0.8f),
                    contentColor = Color.White
                ),
                border = BorderStroke(1.dp, Color.Black)
            ) {
                Text(
                    text = "← BACK",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraLight,
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
        
        // Pro Upgrade Box (only show if not pro user)

        
        // Tree-based settings
        settingsTree.forEach { category ->
            if (category is SettingsItem.Category) {
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
                        // 2x Grid Options
                        item {
                            Text(
                                text = "2x Grid Options",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(grid2xOptions) { option ->
                            Button(
                                onClick = {
                                    onGridSizeChange(option)
                                    showGridSizeDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (gridSize == option) 
                                        Color.White.copy(alpha = 0.2f) 
                                    else 
                                        Color.Black,
                                    contentColor = Color.White
                                ),
                                border = BorderStroke(1.dp, Color.Gray),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                            ) {
                                Text(
                                    text = option,
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        
                        // 3x Grid Options
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "3x Grid Options",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(grid3xOptions) { option ->
                            Button(
                                onClick = {
                                    onGridSizeChange(option)
                                    showGridSizeDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (gridSize == option) 
                                        Color.White.copy(alpha = 0.2f) 
                                    else 
                                        Color.Black,
                                    contentColor = Color.White
                                ),
                                border = BorderStroke(1.dp, Color.Gray),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                            ) {
                                Text(
                                    text = option,
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        
                        // 4x Grid Options
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "4x Grid Options",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(grid4xOptions) { option ->
                            Button(
                                onClick = {
                                    onGridSizeChange(option)
                                    showGridSizeDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (gridSize == option) 
                                        Color.White.copy(alpha = 0.2f) 
                                    else 
                                        Color.Black,
                                    contentColor = Color.White
                                ),
                                border = BorderStroke(1.dp, Color.Gray),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                            ) {
                                Text(
                                    text = option,
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        
                        // 5x Grid Options
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "5x Grid Options",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(grid5xOptions) { option ->
                            Button(
                                onClick = {
                                    onGridSizeChange(option)
                                    showGridSizeDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (gridSize == option) 
                                        Color.White.copy(alpha = 0.2f) 
                                    else 
                                        Color.Black,
                                    contentColor = Color.White
                                ),
                                border = BorderStroke(1.dp, Color.Gray),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                            ) {
                                Text(
                                    text = option,
                                    color = Color.White,
                                    fontSize = 14.sp
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
                        border = BorderStroke(1.dp, Color.Black),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("CLOSE", color = Color.White)
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
    
    if (showPrivacyPolicyDialog) {
        PrivacyPolicyDialog(
            onDismiss = { showPrivacyPolicyDialog = false }
        )
    }

    if (showAppSelectionDialog) {
        AppSelectionDialog(
            installedApps = installedApps,
            selectedApps = adaptiveVolumeApps,
            onDismiss = { showAppSelectionDialog = false },
            onAppChecked = onAdaptiveAppChecked
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
                    
                    // Animated colors for smooth transitions
                    val thumbColor by animateColorAsState(
                        targetValue = if (isChecked) Color.White else Color.Gray,
                        animationSpec = tween(durationMillis = 300),
                        label = "thumbColor"
                    )
                    val trackColor by animateColorAsState(
                        targetValue = if (isChecked) Color.White.copy(alpha = 0.3f) else Color.Gray.copy(alpha = 0.3f),
                        animationSpec = tween(durationMillis = 300),
                        label = "trackColor"
                    )
                    
                    Switch(
                        checked = isChecked,
                        onCheckedChange = { newValue ->
                            // Immediate state update with animation
                            setting.onValueChange?.invoke(newValue)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = thumbColor,
                            checkedTrackColor = trackColor,
                            uncheckedThumbColor = thumbColor,
                            uncheckedTrackColor = trackColor
                        ),
                        modifier = Modifier
                            .graphicsLayer {
                                // Enable smooth animations with proper scaling
                                scaleX = 1.0f
                                scaleY = 1.0f
                                alpha = 1.0f
                            }
                    )
                }
                SettingType.BUTTON -> {
                    Button(
                        onClick = { setting.onClick?.invoke() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Black,
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.Black),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "OPEN",
                            color = Color.White,
                            fontSize = 10.sp
                        )
                    }
                }
                SettingType.SELECTION -> {
                    if (setting.title == "Button Color") {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("normal", "black").forEach { mode ->
                                val isSelected = buttonColorMode == mode
                                
                                // Animated button color for smooth selection feedback
                                val buttonBgColor by animateColorAsState(
                                    targetValue = if (isSelected)
                                        Color.White.copy(alpha = 0.2f)
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
                                    border = BorderStroke(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = Color.Black
                                    ),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier
                                        .size(width = 60.dp, height = 32.dp)
                                        .graphicsLayer {
                                            // Smooth selection animation
                                            scaleX = if (isSelected) 1.05f else 1.0f
                                            scaleY = if (isSelected) 1.05f else 1.0f
                                            alpha = if (isSelected) 1.0f else 0.8f
                                        },
                                    contentPadding = PaddingValues(4.dp)
                                ) {
                                    Text(
                                        text = mode.uppercase(),
                                        color = Color.White,
                                        fontSize = 8.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
                SettingType.LAUNCHER_STATUS -> {
                    val isDefault = setting.currentValue as? Boolean ?: false
                    
                    // Animated button color based on launcher status
                    val launcherButtonColor by animateColorAsState(
                        targetValue = if (isDefault) 
                            Color.Green.copy(alpha = 0.3f) 
                        else 
                            Color.Red.copy(alpha = 0.3f),
                        animationSpec = tween(durationMillis = 300),
                        label = "launcherButtonColor"
                    )
                    
                    Button(
                        onClick = { setting.onClick?.invoke() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = launcherButtonColor
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .graphicsLayer {
                                // Subtle pulse effect for non-default state
                                scaleX = if (!isDefault) 1.02f else 1.0f
                                scaleY = if (!isDefault) 1.02f else 1.0f
                            }
                    ) {
                        Text(
                            text = if (isDefault) "✓ SET" else "SET",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = if (isDefault) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
                SettingType.SLIDER -> {
                    val currentValue = setting.currentValue as? Int ?: 32
                    
                    Column {
                        // Değer gösterimi
                        Text(
                            text = "${currentValue}dp",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.align(Alignment.End)
                        )
                        
                        // Uzun yatay slider
                        Slider(
                            value = currentValue.toFloat(),
                            onValueChange = { newValue ->
                                setting.onValueChange?.invoke(newValue.toInt())
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
            border = BorderStroke(1.dp, Color.Black)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Select Icon Pack",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light
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
                            border = BorderStroke(1.dp, Color.Black),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Default",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                    items(iconPacks) { iconPack ->
                        Button(
                            onClick = {
                                onIconPackSelected(iconPack.packageName)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, Color.Black),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = iconPack.name,
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("CLOSE", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun PrivacyPolicyDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black
            ),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "P R I V A C Y   P O L I C Y",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 2.sp
                    )
                    
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Black,
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                        modifier = Modifier.size(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = "×",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Light
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Privacy policy content
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = "TarsLauncher Privacy Policy",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                    // ... (Reduced for brevity, but logically full content is assumed or copied)


                    item {
                        Text(
                            text = "Last updated: August 30, 2025 • Effective: August 30, 2025",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Light
                        )
                    }
                    
                    item {
                        Text(
                            text = "We are committed to protecting your privacy and ensuring transparency about how we handle your personal information. By using TarsLauncher, you agree to this policy.",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            lineHeight = 20.sp
                        )
                    }
                    
                    // Information We Collect Section
                    item {
                        Text(
                            text = "Information We Collect",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                    
                    item {
                        Text(
                            text = "Personal Information (Pro Version Only):",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    item {
                        Text(
                            text = "• Google account information (email, ID, display name)\n• Purpose: Pro authentication and license verification\n• Retention: Until Pro subscription cancellation + 90 days",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    
                    item {
                        Text(
                            text = "System Permissions & Usage:",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrivacyPolicyPermissionItem(
                                permission = "QUERY_ALL_PACKAGES",
                                purpose = "Display installed apps (stored locally only)"
                            )
                            PrivacyPolicyPermissionItem(
                                permission = "SET_WALLPAPER",
                                purpose = "Enable wallpaper customization features"
                            )
                            PrivacyPolicyPermissionItem(
                                permission = "BIND_DEVICE_ADMIN",
                                purpose = "Double-tap to lock screen functionality"
                            )
                            PrivacyPolicyPermissionItem(
                                permission = "INTERNET",
                                purpose = "Pro features: authentication & cloud sync"
                            )
                        }
                    }

                    // Links Section
                    item {
                        Text(
                            text = "Links & Contact",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                    
                    item {
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://tarslauncher.online/"))
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Color.White
                            ),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "📄 Read Full Privacy Policy",
                                fontSize = 14.sp
                            )
                        }
                    }

                    item {
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:equryt@gmail.com")
                                    putExtra(Intent.EXTRA_SUBJECT, "TarsLauncher Privacy Policy Inquiry")
                                    putExtra(Intent.EXTRA_TEXT, "Hello TarsLauncher Team,\n\nI have a question about your privacy policy:\n\n")
                                }
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.DarkGray.copy(alpha = 0.8f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "✉️ Contact Privacy Team",
                                fontSize = 14.sp
                            )
                        }
                    }
                    
                    item {
                        Text(
                            text = "Data Controller: TarsLauncher Development Team\nEmail: equryt@gmail.com\nWebsite: heartfelt-sundae-0b73f4.netlify.app\n\nDocument Version: 2.0\nNext Review: February 28, 2026",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PrivacyPolicyPermissionItem(
    permission: String,
    purpose: String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.05f)
        ),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = permission,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = purpose,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 16.sp
            )
        }
    }
}


