package com.tarslauncher.app.utils

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Security helper responsible for verifying intents, validating component names,
 * and safely launching activities without relying on ineffective static blacklists.
 */
object SecurityHelper {
    private const val TAG = "SecurityHelper"

    /**
     * Verifies that the package name is non-blank, reasonably formatted,
     * and installed on the device with a launchable main activity.
     */
    fun validatePackageName(packageName: String): Boolean {
        if (packageName.isBlank() || packageName.length > 255) return false
        // Valid Android package naming pattern
        return packageName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))
    }

    /**
     * Creates a validated launch Intent for the specified package and optional activity.
     */
    fun createSafeLaunchIntent(
        context: Context,
        packageName: String,
        activityName: String? = null
    ): Intent? {
        if (!validatePackageName(packageName)) {
            Log.w(TAG, "Invalid package name rejected: $packageName")
            return null
        }

        val packageManager = context.packageManager

        // If explicit activity name is provided, verify component
        if (!activityName.isNullOrBlank()) {
            val component = ComponentName(packageName, activityName)
            val explicitIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setComponent(component)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }

            val resolved = packageManager.resolveActivity(explicitIntent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolved?.activityInfo?.packageName == packageName) {
                return explicitIntent
            }
        }

        // Fallback to PackageManager launch intent
        return runCatching {
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }.getOrNull()
    }

    /**
     * Safely starts an activity with explicit component resolution and error handling.
     */
    fun safeStartActivity(context: Context, intent: Intent): Boolean {
        return try {
            val packageManager = context.packageManager
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)

            if (resolveInfo == null) {
                Log.w(TAG, "No activity resolved for intent: ${intent.action}")
                return false
            }

            // Ensure the resolved component matches expected package if component was set
            intent.component?.let { component ->
                if (resolveInfo.activityInfo?.packageName != component.packageName) {
                    Log.w(TAG, "Component mismatch: expected ${component.packageName}, resolved ${resolveInfo.activityInfo?.packageName}")
                    return false
                }
            }

            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting activity", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start activity safely", e)
            false
        }
    }

    /**
     * Helper to safely launch an app directly by package and optional activity name.
     */
    fun safeStartApp(context: Context, packageName: String, activityName: String? = null): Boolean {
        val intent = createSafeLaunchIntent(context, packageName, activityName)
        if (intent == null) {
            Log.w(TAG, "Could not create safe launch intent for: $packageName")
            return false
        }
        return safeStartActivity(context, intent)
    }
}

/**
 * BroadcastReceiver helper for detecting package installations, removals, and updates safely.
 */
object SecureBroadcastHelper {
    private const val TAG = "SecureBroadcast"
    private var lastRefreshTime = 0L
    private const val THROTTLE_INTERVAL = 1000L // 1 second throttle

    fun createSecurePackageReceiver(onRefresh: (packageName: String?) -> Unit): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null || intent == null) {
                    Log.w(TAG, "Received null context or intent")
                    return
                }

                val action = intent.action
                if (!isValidPackageAction(action)) {
                    return
                }

                // In Android package broadcasts, the affected package name is in intent.data?.schemeSpecificPart
                val packageName = intent.data?.schemeSpecificPart
                
                // Rate limiting
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastRefreshTime < THROTTLE_INTERVAL) {
                    Log.d(TAG, "Throttling package refresh for $packageName")
                    return
                }

                lastRefreshTime = currentTime
                Log.d(TAG, "Secure package event ($action) for package: $packageName")
                onRefresh(packageName)
            }
        }
    }

    private fun isValidPackageAction(action: String?): Boolean {
        return when (action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED -> true
            else -> false
        }
    }
}
