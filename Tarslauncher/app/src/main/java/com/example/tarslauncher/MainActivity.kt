package com.tarslauncher.app

import com.tarslauncher.app.R
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.content.pm.ResolveInfo
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import coil.compose.AsyncImage
import com.tarslauncher.app.ui.theme.TarslauncherTheme
import com.example.tarslauncher.onboarding.OnboardingManager
import com.example.tarslauncher.onboarding.OnboardingScreen
import java.text.SimpleDateFormat
import java.util.*
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.Composable

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.roundToInt
import androidx.compose.runtime.CompositionLocalProvider
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.provider.Settings
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.IconButton
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.core.app.ActivityCompat
import android.Manifest
import android.os.Build
import android.view.WindowManager
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import android.os.CountDownTimer
import android.provider.AlarmClock
import androidx.compose.runtime.mutableStateListOf
import com.example.tarslauncher.SecurePreferences
import com.example.tarslauncher.ProModeManager


data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    var usageCount: Int = 0,
    var isFavorite: Boolean = false
)

data class FolderInfo(
    val id: String,
    val name: String,
    val apps: MutableList<AppInfo> = mutableListOf()
)

data class IconPackInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?
)


// App usage statistics for persistence
data class AppUsageStats(
    val packageName: String,
    val usageCount: Int,
    val isFavorite: Boolean
)

// Security helper class
object SecurityHelper {
    private val BLACKLISTED_PACKAGES = setOf(
        "com.malware.example",
        "com.suspicious.app"
    )
    
    private val SYSTEM_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.packageinstaller"
    )
    
    fun validatePackageName(packageName: String): Boolean {
        return packageName.isNotBlank() &&
               packageName.length <= 255 &&
               packageName.matches(Regex("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*$")) &&
               !packageName.contains("..") &&
               !BLACKLISTED_PACKAGES.contains(packageName)
    }
    

    fun isSystemPackage(packageName: String): Boolean {
        return SYSTEM_PACKAGES.any { packageName.startsWith(it) }
    }
    
    fun safeStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            // Component validation
            intent.component?.let { component ->
                val packageName = component.packageName
                if (!isAllowedPackage(packageName)) {
                    Log.w("TarsLauncher", "Blocked unsafe package: $packageName")
                    return false
                }
            }
            
            // ResolveInfo check
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            if (resolveInfo == null) {
                Log.w("TarsLauncher", "No activity found for intent: ${intent.action}")
                return false
            }
            
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("TarsLauncher", "Failed to start activity safely", e)
            false
        }
    }
    
    private fun isAllowedPackage(packageName: String?): Boolean {
        if (packageName == null) return false
        return validatePackageName(packageName) || isSystemPackage(packageName)
    }
}



// Secure broadcast receiver helper
object SecureBroadcastHelper {
    private var lastRefreshTime = 0L
    private const val THROTTLE_INTERVAL = 5000L // 5 seconds
    
    fun createSecurePackageReceiver(onRefresh: () -> Unit): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null || intent == null) {
                    Log.w("TarsLauncher", "Received null context or intent")
                    return
                }
                
                // Validate intent action
                val action = intent.action
                if (!isValidPackageAction(action)) {
                    Log.w("TarsLauncher", "Received invalid action: $action")
                    return
                }
                
                // Rate limiting
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastRefreshTime < THROTTLE_INTERVAL) {
                    Log.d("TarsLauncher", "Throttling package refresh")
                    return
                }
                
                // Validate package name if present
                val packageName = intent.getStringExtra("package")
                if (packageName != null && !SecurityHelper.validatePackageName(packageName)) {
                    Log.w("TarsLauncher", "Received invalid package name: $packageName")
                    return
                }
                
                lastRefreshTime = currentTime
                Log.d("TarsLauncher", "Secure package refresh triggered by: $action")
                onRefresh()
            }
        }
    }
    
    private fun isValidPackageAction(action: String?): Boolean {
        return when (action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED -> true
            else -> false
        }
    }
}

// Minimal persistent representation of a folder (id + list of app package names)
data class PersistFolder(
    val id: String,
    val apps: List<String>
)



class MainActivity : ComponentActivity() {
    
    private lateinit var proModeManager: ProModeManager
    private lateinit var onboardingManager: OnboardingManager
    internal val isProUser = mutableStateOf(true)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Window'u Şeffaf yap ki sistem wallpaper'i görünsün
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        
        // Initialize ProModeManager with callback to update UI state
        proModeManager = ProModeManager(this) { isPro ->
            Log.d("MainActivity", "Pro status callback received: $isPro")
            // Update the UI state when pro status changes
            runOnUiThread {
                isProUser.value = isPro
            }
        }
        proModeManager.initialize()
        onboardingManager = OnboardingManager(this)
        
        setContent {
            TarslauncherTheme {
                // Şeffaf Surface ile sar
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    LauncherMain(proModeManager = proModeManager, onboardingManager = onboardingManager)
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
                    // Güvenlik: Hassas bilgi sızıntısını önlemek için sadece genel hata logu
                    Log.w("TarsLauncher", "Error loading app, skipping")
                }
            }
            
            // Benzersiz uygulamaları al ve sırala
            apps.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
            
        } catch (e: Exception) {
            // Son çare olarak boş liste döndür
            emptyList()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (::proModeManager.isInitialized) {
            proModeManager.handleActivityResult(requestCode, resultCode, data)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (::proModeManager.isInitialized) {
            proModeManager.cleanup()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherMain(proModeManager: ProModeManager, onboardingManager: OnboardingManager) {
    val context = LocalContext.current
    val activity = context as MainActivity

    var showOnboarding by remember { mutableStateOf(!onboardingManager.isOnboardingComplete()) }
    
    val sharedPrefs = remember { SecurePreferences.getEncryptedPreferences(context) }
    val backupPrefs = remember { context.getSharedPreferences("tarslauncher_usage_backup", Context.MODE_PRIVATE) }
    val gson = remember { Gson() }
    
    // (Removed) darkModeApplied preference — background system removed
    
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
            // Güvenlik: PACKAGE_REPLACED ve PACKAGE_CHANGED kaldırıldı (sık tetikleniyor)
            addDataScheme("package")
        }
        
        context.registerReceiver(receiver, filter)
        
        onDispose {
            try {
                (context as MainActivity).unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w("TarsLauncher", "Failed to unregister receiver safely")
            }
        }
    }
    
    // Stable navigation state with key to prevent recomposition issues
    var currentScreen by remember("navigation_state") { mutableStateOf("home") }
    var showAppDrawer by remember { mutableStateOf(true) }
    var gridSize by remember { mutableStateOf("4x3") }
    var showClock by remember { mutableStateOf(true) }
    // Double-tap screen lock preference
    var doubleTapLockEnabled by remember { mutableStateOf(true) }

    // Battery-friendly preference persisted via SharedPreferences
    var batteryFriendly by remember { mutableStateOf(sharedPrefs.getBoolean("battery_friendly", false)) }

    var selectedIconPack by remember { mutableStateOf(sharedPrefs.getString("icon_pack", null)) }

    // Button color preferences
    var buttonColorMode by remember { mutableStateOf(sharedPrefs.getString("button_color_mode", "black") ?: "black") }
    
    // Folder lock preference
    var folderLocked by remember { mutableStateOf(sharedPrefs.getBoolean("folder_locked", false)) }
    
    // TARS header visibility preference
    var showTarsHeader by remember { mutableStateOf(sharedPrefs.getBoolean("show_tars_header", true)) }
    
    // Pro feature status - use activity's state
    var isProUser by remember { activity.isProUser }
    
    // App drawer icon size preference (32dp default, range 20-36dp)
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
            
            // En çok kullanılan uygulamaları otomatik favori yap (kullanım sayısı 10'dan fazla olanlar)
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
    
    // (Removed) Dark mode wallpaper apply — launcher is always black now
    
    // Load persisted switch values
    LaunchedEffect(Unit) {
        showAppDrawer = sharedPrefs.getBoolean("show_app_drawer", true)
        gridSize = sharedPrefs.getString("grid_size", "4x3") ?: "4x3"
        buttonColorMode = sharedPrefs.getString("button_color_mode", "black") ?: "black"
        folderLocked = sharedPrefs.getBoolean("folder_locked", false)
        showTarsHeader = sharedPrefs.getBoolean("show_tars_header", true)
        showClock = sharedPrefs.getBoolean("show_clock", true)
        doubleTapLockEnabled = sharedPrefs.getBoolean("double_tap_lock", true)

        // Pro durumu artık her zaman açık, varsa kaydedilen değeri oku ama varsayılan true
        isProUser = sharedPrefs.getBoolean("is_pro_user", true)
    }

    val iconPackLoader = remember(selectedIconPack) {
        selectedIconPack?.let { IconPackLoader(context, it) }
    }

    LaunchedEffect(iconPackLoader) {
        iconPackLoader?.load()
    }
    
    // (Removed) System wallpaper restore — not needed
    
    // Function to get button color based on mode
    val getButtonColor = remember(buttonColorMode) {
        when (buttonColorMode) {
            "black" -> Color.Black.copy(alpha = 0.7f)
            else -> Color.Gray.copy(alpha = 0.3f) // normal
        }
    }
    


    // (Removed) Background pickers and permission launchers — background feature removed
    
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

    // Persist folders whenever they change (merge with existing to avoid data loss when grid shrinks)
    LaunchedEffect(folders) {
        if (folders.isNotEmpty()) {
            try {
                // Load existing saved folders (if any)
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

                // Write merged result (preserves folders beyond current grid if they existed)
                val mergedList = existing.values.sortedBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
                val json = gson.toJson(mergedList)
                sharedPrefs.edit().putString("folders", json).apply()
            } catch (_: Exception) {
                // Ignore persistence errors
            }
        }
    }
    
    // Aggressive animation scale to prevent layout shifts during navigation
    // Settings screen needs more visible animations for switches and buttons
    val scaleFactor = if (batteryFriendly) 0.3f else 0.5f // Increased for better settings UX

    CompositionLocalProvider(LocalMotionDurationScale provides StaticMotionDurationScale(scaleFactor)) {

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent) // Şeffaf arka plan
    ) {
        // The main content of the launcher
        Box(modifier = Modifier.fillMaxSize()) {
        // Transparent background to show system wallpaper
        // Launcher is now fully transparent

            // Simple conditional rendering - no animations at all
            if (currentScreen == "home") {
            HomeScreen(
                installedApps = installedApps,
                folders = folders,
                onFoldersUpdate = { folders = it },
                onSettingsClick = { currentScreen = "settings" },
                onAppClick = { appInfo ->
                    incrementAppUsage(appInfo)
                    // Güvenlik: SecurityHelper ile paket adı doğrulaması
                    if (SecurityHelper.validatePackageName(appInfo.packageName)) {
                        val safeIntent = runCatching {
                            context.packageManager.getLaunchIntentForPackage(appInfo.packageName)
                        }.getOrNull()
                        safeIntent?.let { intent ->
                            // Güvenlik: SecurityHelper ile güvenli başlatma
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
                    sharedPrefs.edit().putString("grid_size", it).commit() // commit() ile güvenli kaydet
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
                    sharedPrefs.edit().putString("button_color_mode", it).commit() // commit() ile güvenli kaydet
                },
                folderLocked = folderLocked,
                onFolderLockedChange = {
                    folderLocked = it
                    sharedPrefs.edit().putBoolean("folder_locked", it).commit() // commit() ile güvenli kaydet
                },
                doubleTapLockEnabled = doubleTapLockEnabled,
                onDoubleTapLockEnabledChange = {
                    doubleTapLockEnabled = it
                    sharedPrefs.edit().putBoolean("double_tap_lock", it).commit()
                },
                showTarsHeader = showTarsHeader,
                onShowTarsHeaderChange = {
                    if (it == false && !isProUser) {
                        // Pro özellik gerekli - kullanıcıyı bilgilendir
                        Toast.makeText(context, "TARS Header'ı gizlemek için Pro sürüm gereklidir", Toast.LENGTH_LONG).show()
                    } else {
                        showTarsHeader = it
                        sharedPrefs.edit().putBoolean("show_tars_header", it).commit()
                    }
                },
                isProUser = isProUser,
                onProStatusChange = {
                    isProUser = it
                    sharedPrefs.edit().putBoolean("is_pro_user", it).commit()
                },
                showClock = showClock,
                onShowClockChange = {
                    showClock = it
                    sharedPrefs.edit().putBoolean("show_clock", it).commit()
                },
                appDrawerIconSize = appDrawerIconSize,
                onAppDrawerIconSizeChange = {
                    appDrawerIconSize = it
                    sharedPrefs.edit().putInt("app_drawer_icon_size", it).commit() // commit() ile güvenli kaydet
                },
                proModeManager = proModeManager
            )
        }
    }
    }

    // Onboarding screen overlay
    if (showOnboarding) {
        OnboardingScreen {
            showOnboarding = false
            onboardingManager.setOnboardingComplete()
        }
    }
    }
}

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
    SLIDER,
    PRO_STATUS,
    PRO_PURCHASE
}

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
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
    isProUser: Boolean,
    onProStatusChange: (Boolean) -> Unit,
    showClock: Boolean,
    onShowClockChange: (Boolean) -> Unit,
    appDrawerIconSize: Int,
    onAppDrawerIconSizeChange: (Int) -> Unit,
    proModeManager: ProModeManager
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
    DisposableEffect(Unit) {
        val refreshLauncherStatus = {
            isDefaultLauncherSet = isDefaultLauncher(context)
        }
        
        // Check status when effect starts
        refreshLauncherStatus()
        
        onDispose { }
    }
    
    // Tree state management
    var expandedCategories by remember { mutableStateOf(setOf<String>()) }
    var showGridSizeDialog by remember { mutableStateOf(false) }
    var showIconPackDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }
    
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
                    description = if (!isProUser) "Show TARS text on home screen (Pro feature - to hide)" else "Show TARS text on home screen",
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
        if (!isProUser) {
            ProUpgradeBox(
                onUpgradeClick = {
                    val activity = context as MainActivity
                    android.widget.Toast.makeText(context, "Pro satın alma başlıyor...", android.widget.Toast.LENGTH_SHORT).show()
                    proModeManager.signInWithGoogle(activity) { success, email ->
                        if (success) {
                            android.widget.Toast.makeText(context, "Google ile giriş başarılı: $email", android.widget.Toast.LENGTH_SHORT).show()
                            proModeManager.purchaseProVersion(activity)
                        } else {
                            android.widget.Toast.makeText(context, "Google giriş hatası: $email", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        }
        
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
                                    if (isProUser) {
                                        onGridSizeChange(option)
                                        showGridSizeDialog = false
                                    } else {
                                        // Pro özellik gerekli - kullanıcıyı bilgilendir
                                        Toast.makeText(context, "5x klasör seçenekleri için Pro sürüm gereklidir", Toast.LENGTH_LONG).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (gridSize == option) 
                                        Color.White.copy(alpha = 0.2f) 
                                    else if (!isProUser)
                                        Color.Gray.copy(alpha = 0.3f)
                                    else 
                                        Color.Black,
                                    contentColor = if (!isProUser) Color.Gray else Color.White
                                ),
                                border = BorderStroke(1.dp, if (!isProUser) Color.Gray.copy(alpha = 0.5f) else Color.Gray),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = option,
                                        color = if (!isProUser) Color.Gray else Color.White,
                                        fontSize = 14.sp
                                    )
                                    if (!isProUser) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "PRO",
                                            color = Color.Yellow,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
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
                SettingType.PRO_STATUS -> {
                    val isProActive = setting.currentValue as? Boolean ?: false
                    
                    // Pro status indicator with animated colors
                    val statusColor by animateColorAsState(
                        targetValue = if (isProActive) Color.Green else Color.Gray,
                        animationSpec = tween(durationMillis = 300),
                        label = "proStatusColor"
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Status indicator circle
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    color = statusColor,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        
                        Text(
                            text = if (isProActive) "AKTIF" else "PASIF",
                            color = statusColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                SettingType.PRO_PURCHASE -> {
                    Button(
                        onClick = { setting.onClick?.invoke() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4285F4), // Google Blue
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color(0xFF4285F4)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Google icon placeholder
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(
                                        Color.White,
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                            ) {
                                Text(
                                    text = "G",
                                    color = Color(0xFF4285F4),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                            
                            Text(
                                text = "GOOGLE İLE GİRİŞ YAP & SATIN AL",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
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

fun drawableToBitmap(drawable: Drawable): Bitmap {
    val bitmap = Bitmap.createBitmap(
        drawable.intrinsicWidth.takeIf { it > 0 } ?: 48,
        drawable.intrinsicHeight.takeIf { it > 0 } ?: 48,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}


// ---------------- Animation Scale util -----------------------------
interface MotionDurationScale { fun scale(durationMillis: Int): Int }
class StaticMotionDurationScale(private val factor: Float) : MotionDurationScale {
    override fun scale(durationMillis: Int) = (durationMillis * factor).toInt()
}
val LocalMotionDurationScale = staticCompositionLocalOf<MotionDurationScale> { StaticMotionDurationScale(1f) }
// -------------------------------------------------------------------

// Check if TARS launcher is set as default home app
fun isDefaultLauncher(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
    }
    val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolveInfo?.activityInfo?.packageName == context.packageName
}

// Locks the device if Device Admin is active; otherwise, prompts user to enable admin
fun turnOffDevice(context: Context) {
    val dpm = context.getSystemService(DevicePolicyManager::class.java)
    val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
    if (dpm != null && dpm.isAdminActive(admin)) {
        dpm.lockNow()
    } else {
        requestDeviceAdmin(context)
    }
}

fun requestDeviceAdmin(context: Context) {
    val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
    intent.putExtra(
        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
        "Allow TARS to lock the screen on double-tap."
    )
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

 

// ---------------- Quick Command Executor ---------------------------
// Flashlight functionality removed - no camera permission

fun showToast(context: Context, msg: String) {
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}

// Network functionality removed - no longer needed

fun executeQuickCommand(context: Context, cmd: String): String? {
    return when (cmd.lowercase()) {
        "/ip" -> "Network functionality removed"
        "/flash" -> "Flashlight functionality removed"
        else -> "Unknown command: $cmd"
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
                    
                    item {
                        Text(
                            text = "Device Information:",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    item {
                        Text(
                            text = "• Device model, Android version, app version\n• Purpose: Compatibility, optimization, technical support\n• Legal basis: Legitimate interest",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    
                    // How We Use Information Section
                    item {
                        Text(
                            text = "How We Use Information",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                    
                    item {
                        Text(
                            text = "• Core launcher functionality and app display\n• Pro version authentication and feature access\n• User preferences and settings storage\n• Technical support and app improvement\n• Legal compliance and fraud prevention",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    
                    // Data Security Section
                    item {
                        Text(
                            text = "Data Security & Protection",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                    
                    item {
                        Text(
                            text = "• All sensitive data encrypted (TLS 1.3 in transit, AES-256 at rest)\n• Local data stored encrypted on your device\n• Cloud data (Pro only) stored in EU region (europe-west1)\n• Strict access controls and regular security audits\n• No data sharing with advertisers or data brokers",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    
                    // Third-Party Services Section
                    item {
                        Text(
                            text = "Third-Party Services",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                    
                    item {
                        Text(
                            text = "We only use Google services for Pro features:\n• Firebase/Firestore: Pro user authentication\n• Google Play Billing: Purchase processing\n• Google Sign-In: User authentication\n\nAll subject to Google's privacy policies.",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    
                    // Your Rights Section
                    item {
                        Text(
                            text = "Your Privacy Rights",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                    
                    item {
                        Text(
                            text = "You have the right to:\n• Access your personal data\n• Correct inaccurate information\n• Request data deletion\n• Data portability\n• Object to processing\n• Withdraw consent (Pro users)\n\nResponse time: Within 30 days (free of charge)",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    
                    // Children's Privacy Section
                    item {
                        Text(
                            text = "Children's Privacy",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                    
                    item {
                        Text(
                            text = "TarsLauncher is not intended for children under 13. We do not knowingly collect personal information from children under 13. If discovered, such data will be deleted immediately.",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    
                    // Legal Compliance Section
                    item {
                        Text(
                            text = "Legal Compliance",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                    
                    item {
                        Text(
                            text = "Compliant with:\n• GDPR (European Union)\n• KVKK (Turkey - Law No. 6698)\n• CCPA (California)\n• Other applicable data protection laws\n\nYou may lodge complaints with relevant supervisory authorities.",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    
                    // Important Notes Section
                    item {
                        Text(
                            text = "Important Notes",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                    
                    item {
                        Text(
                            text = "• No web cookies or cross-app tracking\n• No advertising IDs collected\n• Analytics can be disabled in settings\n• Camera and microphone are never accessed\n• App lists never leave your device (except Pro sync)",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
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

@Composable
fun ProUpgradeBox(
    onUpgradeClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUpgradeClick() },
        colors = CardDefaults.cardColors(
            containerColor = Color.Black
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            2.dp, 
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFFD700), // Gold
                    Color(0xFF8B4513)  // Brown
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Become Pro",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "- Remove TARS header",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                
                Text(
                    text = "- Extra folder",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }
    }
}
