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

private val Grid2xOptions = listOf(GridSize(2, 1), GridSize(2, 2), GridSize(2, 3), GridSize(2, 4))
private val Grid3xOptions = listOf(GridSize(3, 1), GridSize(3, 2), GridSize(3, 3))
private val Grid4xOptions = listOf(GridSize(4, 1), GridSize(4, 2), GridSize(4, 3), GridSize(4, 4))
private val Grid5xOptions = listOf(GridSize(5, 1), GridSize(5, 2), GridSize(5, 3), GridSize(5, 4), GridSize(5, 5))

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    showAppDrawer: Boolean,
    onShowAppDrawerChange: (Boolean) -> Unit,
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
    onBackupToCloud: () -> Unit = {},
    onRestoreFromCloud: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {}
) {
    val context = LocalContext.current
    
    // Handle system back button
    BackHandler {
        onBackClick()
    }
    
    // Default launcher state
    var isDefaultLauncherSet by remember { mutableStateOf(false) }

    // TARS Agent Master Kill Switch & Qwen Model state
    val agentPrefs = remember { com.tarslauncher.app.agent.data.AgentPreferences(context) }
    var isAgentEnabled by remember { mutableStateOf(agentPrefs.isAgentEnabled()) }
    var isQwenEnabled by remember { mutableStateOf(agentPrefs.isQwenEnabled()) }
    
    // Refresh launcher status when screen becomes visible (user returns from settings)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isDefaultLauncherSet = isDefaultLauncher(context)
                isAgentEnabled = agentPrefs.isAgentEnabled()
                isQwenEnabled = agentPrefs.isQwenEnabled()
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        isDefaultLauncherSet = isDefaultLauncher(context)
        isAgentEnabled = agentPrefs.isAgentEnabled()
        isQwenEnabled = agentPrefs.isQwenEnabled()
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Tree state management
    var expandedCategories by remember { mutableStateOf(setOf<String>()) }
    var showGridSizeDialog by remember { mutableStateOf(false) }
    var showIconPackDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }
    var showAddPwaDialog by remember { mutableStateOf(false) }
    var showManagePwasDialog by remember { mutableStateOf(false) }
    var showNotesDialog by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var showAgentSheet by remember { mutableStateOf(false) }

    val authManager = remember { com.tarslauncher.app.data.sync.GoogleAuthManager(context) }
    val workspaceRepo = remember { com.tarslauncher.app.data.workspace.FirebaseWorkspaceRepository(context, authManager) }
    val workspaceItems by workspaceRepo.workspaceItemsFlow.collectAsState(initial = emptyList())
    val currentUser by authManager.authUserFlow.collectAsState(initial = authManager.currentUser)
    val isGoogleLinked = currentUser != null && currentUser?.isAnonymous == false
    val googleUserLabel = currentUser?.email ?: currentUser?.displayName ?: "Google Hesabı"
    
    // Create settings tree structure - memoized to prevent reallocations on every composition
    val settingsTree = remember(
        showAppDrawer,
        showTarsHeader,
        showClock,
        appDrawerIconSize,
        gridSize,
        buttonColorMode,
        selectedIconPack,
        isDefaultLauncherSet,
        isAgentEnabled,
        isQwenEnabled,
        installedPwas.size,
        appVersion,
        isSyncing,
        lastBackupTime,
        workspaceItems.size,
        isGoogleLinked,
        googleUserLabel
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
            title = "SECURITY",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "TARS Agent (Kill Switch)",
                    description = if (isAgentEnabled) "Ajan ve Dynamic Island aktif" else "Ajan tamamen kapalı (devre dışı)",
                    type = SettingType.TOGGLE,
                    currentValue = isAgentEnabled,
                    onValueChange = { newValue ->
                        val enabled = newValue as Boolean
                        isAgentEnabled = enabled
                        agentPrefs.setAgentEnabled(enabled)
                        com.tarslauncher.app.agent.ui.AgentViewModel.getInstance()?.onToggleAgentEnabled(enabled)
                        if (!enabled) {
                            com.tarslauncher.app.agent.service.TarsAccessibilityService.getInstance()?.stopAgentTask()
                            com.tarslauncher.app.agent.service.TarsAccessibilityService.getInstance()?.overlayNotch?.hide()
                        } else {
                            if (com.tarslauncher.app.agent.service.TarsAccessibilityService.isConnected()) {
                                com.tarslauncher.app.agent.service.TarsAccessibilityService.getInstance()?.overlayNotch?.showIdle()
                            }
                        }
                    }
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
            title = "WEB APPS (PWA)",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Add Web App (PWA)",
                    description = "Install websites as native apps with Presets or URL",
                    type = SettingType.BUTTON,
                    onClick = { showAddPwaDialog = true }
                ),
                SettingsItem.Setting(
                    title = "Manage Web Apps",
                    description = "${installedPwas.size} web app(s) installed",
                    type = SettingType.BUTTON,
                    onClick = { showManagePwasDialog = true }
                )
            )
        ),
        SettingsItem.Category(
            title = "CLOUD BACKUP (SUPABASE)",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Backup to Cloud",
                    description = if (isSyncing) "Syncing..." else if (lastBackupTime > 0) "Last: " + SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(lastBackupTime)) else "Save layout & settings to Supabase",
                    type = SettingType.BUTTON,
                    onClick = onBackupToCloud
                ),
                SettingsItem.Setting(
                    title = "Restore from Cloud",
                    description = if (isSyncing) "Restoring..." else "Download and apply cloud backup",
                    type = SettingType.BUTTON,
                    onClick = onRestoreFromCloud
                )
            )
        ),
        SettingsItem.Category(
            title = "ACCOUNT & AUTH (GOOGLE)",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Google Auth & Bulut Hesap",
                    description = if (isGoogleLinked) "Bağlı: $googleUserLabel" else "Anonim oturum — Google ile bağlamak için açın",
                    type = SettingType.BUTTON,
                    onClick = { showAuthDialog = true }
                )
            )
        ),
        SettingsItem.Category(
            title = "NOTLARIM",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "Notlarım",
                    description = if (workspaceItems.isEmpty()) "Henüz not yok" else "${workspaceItems.size} kayıtlı not",
                    type = SettingType.BUTTON,
                    onClick = { showNotesDialog = true }
                )
            )
        ),
        SettingsItem.Category(
            title = "YAPAY ZEKA & LLM YÖNETİMİ",
            icon = "",
            children = listOf(
                SettingsItem.Setting(
                    title = "TARS Agent Konsolu",
                    description = "Dynamic Island ve komut konsolu",
                    type = SettingType.BUTTON,
                    onClick = { showAgentSheet = true }
                ),
                SettingsItem.Setting(
                    title = "Qwen Modeli",
                    description = if (isQwenEnabled) "Açık: Qwen 3.8-27B aktif" else "Kapalı: Dots3-Note Preview (Free) aktif",
                    type = SettingType.TOGGLE,
                    currentValue = isQwenEnabled,
                    onValueChange = { newValue ->
                        val enabled = newValue as Boolean
                        isQwenEnabled = enabled
                        agentPrefs.setQwenEnabled(enabled)
                        com.tarslauncher.app.agent.ui.AgentViewModel.getInstance()?.onToggleQwenEnabled(enabled)
                    }
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
                    onClick = onCheckForUpdates
                ),
                SettingsItem.Setting(
                    title = "Privacy Policy",
                    description = "Tarslauncher – Privacy Policy",
                    type = SettingType.BUTTON,
                    onClick = { showPrivacyPolicyDialog = true }
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
                        items(Grid2xOptions, key = { it.serialize() }) { option ->
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
                                    text = option.serialize(),
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
                        items(Grid3xOptions, key = { it.serialize() }) { option ->
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
                                    text = option.serialize(),
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
                        items(Grid4xOptions, key = { it.serialize() }) { option ->
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
                                    text = option.serialize(),
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
                        items(Grid5xOptions, key = { it.serialize() }) { option ->
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
                                    text = option.serialize(),
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

    if (showNotesDialog) {
        ManageNotesDialog(
            onDismiss = { showNotesDialog = false }
        )
    }

    if (showAuthDialog) {
        ManageAuthDialog(
            onDismiss = { showAuthDialog = false }
        )
    }

    if (showAgentSheet) {
        val agentViewModel = remember(context) {
            val owner = context as? androidx.lifecycle.ViewModelStoreOwner
            if (owner != null) {
                androidx.lifecycle.ViewModelProvider(
                    owner,
                    com.tarslauncher.app.agent.ui.AgentViewModel.Factory(context.applicationContext as android.app.Application)
                )[com.tarslauncher.app.agent.ui.AgentViewModel::class.java]
            } else {
                com.tarslauncher.app.agent.ui.AgentViewModel(context.applicationContext as android.app.Application)
            }
        }
        com.tarslauncher.app.agent.ui.AgentBottomSheet(
            viewModel = agentViewModel,
            onDismissRequest = {
                showAgentSheet = false
                isQwenEnabled = agentPrefs.isQwenEnabled()
                isAgentEnabled = agentPrefs.isAgentEnabled()
            },
            onOpenWorkspace = {
                showAgentSheet = false
                isQwenEnabled = agentPrefs.isQwenEnabled()
                isAgentEnabled = agentPrefs.isAgentEnabled()
                showNotesDialog = true
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


