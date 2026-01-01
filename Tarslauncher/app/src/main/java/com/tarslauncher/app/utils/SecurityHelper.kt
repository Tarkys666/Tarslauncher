package com.tarslauncher.app.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

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
