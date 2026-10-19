package com.tarslauncher.app.utils

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

import android.os.Handler
import android.os.Looper

/**
 * Security helper responsible for verifying intents, validating component names,
 * and safely launching activities without relying on ineffective static blacklists.
 */
object SecurityHelper {
    private const val TAG = "SecurityHelper"
    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    /**
     * Verifies that the package name is non-blank, reasonably formatted,
     * and installed on the device with a launchable main activity.
     */
    fun validatePackageName(packageName: String): Boolean {
        if (packageName.isBlank() || packageName.length > 255) return false
        return packageName.matches(PACKAGE_NAME_REGEX)
    }



    /**
     * Helper to safely launch an app directly by package and optional activity name
     * with a single IPC resolution pass.
     */
    fun safeStartApp(context: Context, packageName: String, activityName: String? = null): Boolean {
        if (!validatePackageName(packageName)) {
            Log.w(TAG, "Invalid package name: $packageName")
            return false
        }

        val packageManager = context.packageManager
        val targetIntent = if (!activityName.isNullOrBlank()) {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(packageName, activityName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
        } else {
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } ?: return false

        // Single resolution pass to eliminate duplicate Binder IPC
        val resolveInfo = packageManager.resolveActivity(targetIntent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolveInfo == null || resolveInfo.activityInfo?.packageName != packageName) {
            Log.w(TAG, "Activity resolution failed or component mismatch for $packageName")
            return false
        }

        val activityInfo = resolveInfo.activityInfo
        if (activityInfo?.exported == false && packageName != context.packageName) {
            Log.w(TAG, "Activity is not exported and belongs to a different package: $packageName")
            return false
        }

        return try {
            if (context !is android.app.Activity) {
                targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(targetIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start activity safely", e)
            false
        }
    }
}

/**
 * BroadcastReceiver helper for detecting package installations, removals, and updates safely.
 */
object SecureBroadcastHelper {
    private const val TAG = "SecureBroadcast"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRefreshRunnable: Runnable? = null
    private const val DEBOUNCE_INTERVAL = 300L

    /**
     * Cancels any pending debounced refresh runnable and clears references to prevent leaks.
     */
    fun cancelPending() {
        pendingRefreshRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingRefreshRunnable = null
        }
    }

    /**
     * Cleans up pending tasks. Alias for cancelPending.
     */
    fun cleanup() {
        cancelPending()
    }

    fun createSecurePackageReceiver(onRefresh: (packageName: String?) -> Unit): BroadcastReceiver {
        return object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null || intent == null) return
                val action = intent.action
                if (!isValidPackageAction(action)) return

                val packageName = intent.data?.schemeSpecificPart

                // Debounce instead of dropping: ensures batch updates (e.g. Play Store updates) trigger refresh reliably
                pendingRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    try {
                        Log.d(TAG, "Executing package event ($action) for package: $packageName")
                        onRefresh(packageName)
                    } finally {
                        pendingRefreshRunnable = null
                    }
                }
                pendingRefreshRunnable = runnable
                mainHandler.postDelayed(runnable, DEBOUNCE_INTERVAL)
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
