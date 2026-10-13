package com.tarslauncher.app.utils

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

object PwaLauncherHelper {
    private const val TAG = "PwaLauncherHelper"

    /**
     * Sanitizes and validates that the provided string is a secure web URL (http/https).
     * Rejects file://, intent://, content://, javascript: and other arbitrary schemes.
     */
    fun sanitizeWebUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null

        val withScheme = if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)) {
            "https://$trimmed"
        } else {
            trimmed
        }

        return try {
            val parsed = Uri.parse(withScheme)
            val scheme = parsed.scheme?.lowercase()
            val host = parsed.host

            if ((scheme == "http" || scheme == "https") && !host.isNullOrBlank()) {
                withScheme
            } else {
                Log.w(TAG, "Rejected non-web scheme or invalid host: $withScheme")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse URL: $rawUrl", e)
            null
        }
    }

    /**
     * Launches a PWA/Web URL using Chrome Custom Tabs configured in dark/black OLED mode,
     * hiding the URL bar on scroll to feel like a native app. Falls back to default browser intent.
     */
    fun launchPwa(context: Context, url: String): Boolean {
        val sanitized = sanitizeWebUrl(url)
        if (sanitized == null) {
            Log.e(TAG, "Cannot launch invalid URL: $url")
            return false
        }

        val uri = Uri.parse(sanitized)

        return try {
            val colorSchemeParams = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(Color.BLACK)
                .setNavigationBarColor(Color.BLACK)
                .build()

            val customTabsIntent = CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(colorSchemeParams)
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()

            if (context !is android.app.Activity) {
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            customTabsIntent.launchUrl(context, uri)
            true
        } catch (e: Exception) {
            Log.w(TAG, "CustomTabs failed, falling back to ACTION_VIEW: ${e.message}")
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    if (context !is android.app.Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(fallbackIntent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to launch URL via fallback", e2)
                false
            }
        }
    }

    /**
     * Dispatches launch request based on whether the item is:
     * 1. A pinned shortcut with shortcutPackage & shortcutId (via LauncherApps)
     * 2. A custom PWA with webUrl (via Chrome Custom Tabs)
     * 3. A standard Android application / WebAPK (via safeStartApp)
     */
    fun launchApp(context: Context, appInfo: com.tarslauncher.app.data.model.AppInfo): Boolean {
        // 1. Android 8.0+ pinned shortcut
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !appInfo.shortcutPackage.isNullOrBlank() &&
            !appInfo.shortcutId.isNullOrBlank()
        ) {
            try {
                val launcherApps = context.getSystemService(android.content.pm.LauncherApps::class.java)
                if (launcherApps != null) {
                    launcherApps.startShortcut(
                        appInfo.shortcutPackage,
                        appInfo.shortcutId,
                        null,
                        null,
                        android.os.Process.myUserHandle()
                    )
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start pinned shortcut via LauncherApps: ${e.message}")
            }
        }

        // 2. Custom PWA with a validated Web URL
        if (appInfo.isPwa && !appInfo.webUrl.isNullOrBlank()) {
            return launchPwa(context, appInfo.webUrl)
        }

        // 3. Native App or WebAPK package
        return SecurityHelper.safeStartApp(
            context = context,
            packageName = appInfo.packageName,
            activityName = appInfo.activityName
        )
    }
}
