package com.tarslauncher.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tarslauncher.app.data.model.*
import com.tarslauncher.app.onboarding.OnboardingManager
import com.tarslauncher.app.onboarding.OnboardingScreen
import com.tarslauncher.app.ui.screens.HomeScreen
import com.tarslauncher.app.ui.screens.SettingsScreen
import com.tarslauncher.app.ui.theme.TarslauncherTheme
import com.tarslauncher.app.utils.*

class MainActivity : ComponentActivity() {
    
    private lateinit var onboardingManager: OnboardingManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Window'u Şeffaf yap ki sistem wallpaper'i görünsün
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        
        onboardingManager = OnboardingManager(this)
        
        setContent {
            TarslauncherTheme {
                // Şeffaf Surface ile sar
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    LauncherMain(onboardingManager = onboardingManager)
                }
            }
        }
    }
    
    internal fun getInstalledIconPacks(): List<IconPackInfo> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME")
        return pm.queryIntentActivities(intent, 0).mapNotNull {
            try {
                val appName = it.loadLabel(pm).toString()
                val packageName = it.activityInfo.packageName
                val icon = it.loadIcon(pm)
                IconPackInfo(name = appName, packageName = packageName, icon = icon)
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.name }
    }
    
    internal fun getInstalledApps(): List<AppInfo> {
        return try {
            val packageManager = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)
            
            // Daha kapsamlı uygulama listesi için farklı yöntemler dene
            val resolveInfoList = try {
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            } catch (e: Exception) {
                try {
                    packageManager.queryIntentActivities(intent, 0)
                } catch (e2: Exception) {
                    packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                }
            }
            
            val apps = mutableListOf<AppInfo>()
            
            resolveInfoList.forEach { resolveInfo ->
                try {
                    val activityInfo = resolveInfo.activityInfo ?: return@forEach
                    val packageName = activityInfo.packageName
                    
                    // Kendi paketimizi hariç tut
                    if (packageName == this.packageName) return@forEach
                    
                    // Güvenlik kontrolü - paket adı doğrulaması
                    if (!SecurityHelper.validatePackageName(packageName)) {
                        Log.w("TarsLauncher", "Skipping invalid package: $packageName")
                        return@forEach
                    }
                    
                    val appName = try {
                        activityInfo.loadLabel(packageManager).toString()
                    } catch (e: Exception) {
                        packageName.substringAfterLast(".")
                    }
                    
                    if (appName.isBlank()) return@forEach
                    
                    val icon = try {
                        activityInfo.loadIcon(packageManager)
                    } catch (e: Exception) {
                        try {
                            packageManager.getApplicationIcon(packageName)
                        } catch (e2: Exception) {
                            null
                        }
                    }
                    
                    apps.add(
                        AppInfo(
                            name = appName.take(50).trim(),
                            packageName = packageName,
                            icon = icon
                        )
                    )
                } catch (e: Exception) {
                    // Tek bir uygulama hatası tüm listeyi bozmasın
                    Log.w("TarsLauncher", "Error loading app, skipping")
                }
            }
            
            // Benzersiz uygulamaları al ve sırala
            apps.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
            
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (resultCode == RESULT_OK) {
            // Handle other results if needed
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        super.onDestroy()
    }
}

@Composable
fun LauncherMain(onboardingManager: OnboardingManager) {
    val context = LocalContext.current
    val activity = context as MainActivity

    // Onboarding not used in main logic but kept for interface consistency
    // Onboarding state
    var showOnboarding by remember { mutableStateOf(!onboardingManager.isOnboardingComplete()) }
    
    val sharedPrefs = remember { SecurePreferences.getEncryptedPreferences(context) }
    val gson = remember { Gson() }
    
    // Dinamik uygulama listesi
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var iconPacks by remember { mutableStateOf<List<IconPackInfo>>(emptyList()) }
    
    // Uygulama kullanım istatistiklerini yükle
    val loadUsageStats = remember(sharedPrefs, gson) {
        try {
            val json = sharedPrefs.getString("app_usage_stats", null)
            if (json != null) {
                val type = object : TypeToken<List<AppUsageStats>>() {}.type
                gson.fromJson<List<AppUsageStats>>(json, type) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // Uygulama listesini yükleme fonksiyonu
    val refreshAppList = {
        val baseApps = activity.getInstalledApps()
        // Kullanım istatistiklerini uygula
        installedApps = baseApps.map { app ->
            val stats = loadUsageStats.find { it.packageName == app.packageName }
            AppInfo(
                name = app.name,
                packageName = app.packageName,
                icon = app.icon,
                usageCount = stats?.usageCount ?: 0,
                isFavorite = stats?.isFavorite ?: false
            )
        }.sortedWith(compareByDescending<AppInfo> { it.isFavorite }.thenByDescending { it.usageCount }.thenBy { it.name })
        
        iconPacks = activity.getInstalledIconPacks()
    }
    
    // İlk yükleme
    LaunchedEffect(Unit) {
        refreshAppList()
    }
    
    // Güvenli broadcast receiver ile uygulama değişikliklerini dinle
    DisposableEffect(context) {
        val receiver = SecureBroadcastHelper.createSecurePackageReceiver {
            refreshAppList()
        }
        
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        
        context.registerReceiver(receiver, filter)
        
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w("TarsLauncher", "Failed to unregister receiver safely")
            }
        }
    }
    
    // Stable navigation state
    var currentScreen by remember("navigation_state") { mutableStateOf("home") }
    var showAppDrawer by remember { mutableStateOf(true) }
    var gridSize by remember { mutableStateOf("4x3") }
    var showClock by remember { mutableStateOf(true) }
    var doubleTapLockEnabled by remember { mutableStateOf(true) }
    var batteryFriendly by remember { mutableStateOf(sharedPrefs.getBoolean("battery_friendly", false)) }
    var selectedIconPack by remember { mutableStateOf(sharedPrefs.getString("icon_pack", null)) }
    var buttonColorMode by remember { mutableStateOf(sharedPrefs.getString("button_color_mode", "black") ?: "black") }
    var folderLocked by remember { mutableStateOf(sharedPrefs.getBoolean("folder_locked", false)) }
    var showTarsHeader by remember { mutableStateOf(sharedPrefs.getBoolean("show_tars_header", true)) }
    var appDrawerIconSize by remember { mutableStateOf(sharedPrefs.getInt("app_drawer_icon_size", 32)) }
    
    // Uygulama kullanım istatistiklerini kaydetme fonksiyonu
    val saveUsageStats = remember {
        { apps: List<AppInfo> ->
            try {
                val stats = apps.map { app ->
                    AppUsageStats(
                        packageName = app.packageName,
                        usageCount = app.usageCount,
                        isFavorite = app.isFavorite
                    )
                }
                val json = gson.toJson(stats)
                sharedPrefs.edit().putString("app_usage_stats", json).apply()
            } catch (e: Exception) {
                // Hata durumunda sessizce devam et
            }
        }
    }
    
    // Uygulama kullanımını artırma fonksiyonu
    val incrementAppUsage = remember {
        { app: AppInfo ->
            val updatedApps = installedApps.map { existingApp ->
                if (existingApp.packageName == app.packageName) {
                    AppInfo(
                        name = existingApp.name,
                        packageName = existingApp.packageName,
                        icon = existingApp.icon,
                        usageCount = existingApp.usageCount + 1,
                        isFavorite = existingApp.isFavorite
                    )
                } else {
                    existingApp
                }
            }
            
            // En çok kullanılan uygulamaları otomatik favori yap
            val finalApps = updatedApps.map { existingApp ->
                if (existingApp.usageCount >= 10 && !existingApp.isFavorite) {
                    AppInfo(
                        name = existingApp.name,
                        packageName = existingApp.packageName,
                        icon = existingApp.icon,
                        usageCount = existingApp.usageCount,
                        isFavorite = true
                    )
                } else {
                    existingApp
                }
            }.sortedWith(compareByDescending<AppInfo> { it.isFavorite }.thenByDescending { it.usageCount }.thenBy { it.name })
            
            installedApps = finalApps
            saveUsageStats(finalApps)
        }
    }
    
    // Load persiste settings
    LaunchedEffect(Unit) {
        showAppDrawer = sharedPrefs.getBoolean("show_app_drawer", true)
        gridSize = sharedPrefs.getString("grid_size", "4x3") ?: "4x3"
        buttonColorMode = sharedPrefs.getString("button_color_mode", "black") ?: "black"
        folderLocked = sharedPrefs.getBoolean("folder_locked", false)
        showTarsHeader = sharedPrefs.getBoolean("show_tars_header", true)
        showClock = sharedPrefs.getBoolean("show_clock", true)
        doubleTapLockEnabled = sharedPrefs.getBoolean("double_tap_lock", true)
    }

    val iconPackLoader = remember(selectedIconPack) {
        selectedIconPack?.let { IconPackLoader(context, it) }
    }

    LaunchedEffect(iconPackLoader) {
        iconPackLoader?.load()
    }
    
    val getButtonColor = remember(buttonColorMode) {
        when (buttonColorMode) {
            "black" -> Color.Black.copy(alpha = 0.7f)
            else -> Color.Gray.copy(alpha = 0.3f)
        }
    }
    
    // Grid boyutuna göre folder sayısını hesapla
    val totalFolders = remember(gridSize) {
        val (cols, rows) = gridSize.split("x").map { it.toInt() }
        cols * rows
    }
    
    val initialFolders = remember(totalFolders, installedApps) {
        val savedJson = sharedPrefs.getString("folders", null)
        val listType = object : TypeToken<List<PersistFolder>>() {}.type
        val saved: List<PersistFolder>? = try { 
            if (savedJson != null) gson.fromJson(savedJson, listType) else null 
        } catch (e: Exception) { 
            null 
        }

        (1..totalFolders).map { index ->
            val id = index.toString()
            val match = saved?.find { it.id == id }
            val appList = if (installedApps.isNotEmpty() && match?.apps != null) {
                match.apps.mapNotNull { pkg -> 
                    installedApps.find { it.packageName == pkg }
                }.toMutableList()
            } else {
                mutableListOf()
            }
            FolderInfo(id, "", appList)
        }
    }

    var folders by remember(totalFolders) { mutableStateOf(initialFolders) }
    
    // Uygulama listesi yüklendikten sonra folder verilerini yeniden yükle
    LaunchedEffect(installedApps, totalFolders) {
        if (installedApps.isNotEmpty()) {
            val savedJson = sharedPrefs.getString("folders", null)
            if (savedJson != null) {
                try {
                    val listType = object : TypeToken<List<PersistFolder>>() {}.type
                    val saved: List<PersistFolder>? = gson.fromJson(savedJson, listType)
                    
                    if (saved != null) {
                        val reloadedFolders = (1..totalFolders).map { index ->
                            val id = index.toString()
                            val match = saved.find { it.id == id }
                            val appList = match?.apps?.mapNotNull { pkg -> 
                                installedApps.find { it.packageName == pkg }
                            }?.toMutableList() ?: mutableListOf()
                            FolderInfo(id, "", appList)
                        }
                        folders = reloadedFolders
                    }
                } catch (e: Exception) {
                    // Hata durumunda varsayılan folder'ları kullan
                }
            }
        }
    }

    // Persist folders whenever they change
    LaunchedEffect(folders) {
        if (folders.isNotEmpty()) {
            try {
                // Load existing saved folders
                val existingJson = sharedPrefs.getString("folders", null)
                val listType = object : TypeToken<List<PersistFolder>>() {}.type
                val existing: MutableMap<String, PersistFolder> = try {
                    (if (existingJson != null) gson.fromJson<List<PersistFolder>>(existingJson, listType) else emptyList())
                        ?.associateBy { it.id }
                        ?.toMutableMap() ?: mutableMapOf()
                } catch (_: Exception) {
                    mutableMapOf()
                }

                // Update/insert current visible folders
                folders.forEach { f ->
                    existing[f.id] = PersistFolder(f.id, f.apps.map { a -> a.packageName })
                }

                val mergedList = existing.values.sortedBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
                val json = gson.toJson(mergedList)
                sharedPrefs.edit().putString("folders", json).apply()
            } catch (_: Exception) {
                // Ignore persistence errors
            }
        }
    }
    
    val scaleFactor = if (batteryFriendly) 0.3f else 0.5f

    CompositionLocalProvider(LocalMotionDurationScale provides StaticMotionDurationScale(scaleFactor)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (currentScreen == "home") {
                    HomeScreen(
                        installedApps = installedApps,
                        folders = folders,
                        onFoldersUpdate = { folders = it },
                        onSettingsClick = { currentScreen = "settings" },
                        onAppClick = { appInfo ->
                            incrementAppUsage(appInfo)
                            if (SecurityHelper.validatePackageName(appInfo.packageName)) {
                                val safeIntent = runCatching {
                                    context.packageManager.getLaunchIntentForPackage(appInfo.packageName)
                                }.getOrNull()
                                safeIntent?.let { intent ->
                                    if (!SecurityHelper.safeStartActivity(context, intent)) {
                                        Log.w("TarsLauncher", "Failed to start app safely: ${appInfo.packageName}")
                                    }
                                }
                            } else {
                                Log.w("TarsLauncher", "Invalid package name blocked: ${appInfo.packageName}")
                            }
                        },
                        showAppDrawer = showAppDrawer,
                        gridSize = gridSize,
                        iconPackLoader = iconPackLoader,
                        buttonColor = getButtonColor,
                        folderLocked = folderLocked,
                        showTarsHeader = showTarsHeader,
                        appDrawerIconSize = appDrawerIconSize,
                        showClock = showClock,
                        doubleTapLockEnabled = doubleTapLockEnabled
                    )
                } else if (currentScreen == "settings") {
                    SettingsScreen(
                        onBackClick = { currentScreen = "home" },
                        showAppDrawer = showAppDrawer,
                        batteryFriendly = batteryFriendly,
                        onShowAppDrawerChange = {
                            showAppDrawer = it
                            sharedPrefs.edit().putBoolean("show_app_drawer", it).apply()
                        },
                        onBatteryFriendlyChange = {
                            batteryFriendly = it
                            sharedPrefs.edit().putBoolean("battery_friendly", it).apply()
                        },
                        gridSize = gridSize,
                        onGridSizeChange = {
                            gridSize = it
                            sharedPrefs.edit().putString("grid_size", it).commit()
                        },
                        installedIconPacks = iconPacks,
                        selectedIconPack = selectedIconPack,
                        onIconPackSelected = {
                            selectedIconPack = it
                            sharedPrefs.edit().putString("icon_pack", it).apply()
                        },
                        buttonColorMode = buttonColorMode,
                        onButtonColorModeChange = {
                            buttonColorMode = it
                            sharedPrefs.edit().putString("button_color_mode", it).commit()
                        },
                        folderLocked = folderLocked,
                        onFolderLockedChange = {
                            folderLocked = it
                            sharedPrefs.edit().putBoolean("folder_locked", it).commit()
                        },
                        doubleTapLockEnabled = doubleTapLockEnabled,
                        onDoubleTapLockEnabledChange = {
                            doubleTapLockEnabled = it
                            sharedPrefs.edit().putBoolean("double_tap_lock", it).commit()
                        },
                        showTarsHeader = showTarsHeader,
                        onShowTarsHeaderChange = {
                            showTarsHeader = it
                            sharedPrefs.edit().putBoolean("show_tars_header", it).commit()
                        },
                        showClock = showClock,
                        onShowClockChange = {
                            showClock = it
                            sharedPrefs.edit().putBoolean("show_clock", it).commit()
                        },
                        appDrawerIconSize = appDrawerIconSize,
                        onAppDrawerIconSizeChange = {
                            appDrawerIconSize = it
                            sharedPrefs.edit().putInt("app_drawer_icon_size", it).commit()
                        }
                    )
            }
        }

        // Onboarding Overlay
        if (showOnboarding) {
            OnboardingScreen(
                onOnboardingComplete = {
                    onboardingManager.setOnboardingComplete()
                    showOnboarding = false
                }
            )
        }
    }
}
}
