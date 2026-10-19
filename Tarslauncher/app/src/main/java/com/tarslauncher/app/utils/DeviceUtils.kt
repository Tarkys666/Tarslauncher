package com.tarslauncher.app.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

import android.app.role.RoleManager
import android.os.Build

// Check if TARS launcher is set as default home app
fun isDefaultLauncher(context: Context): Boolean {
    // 1. Android 10+ (API 29+) fast path via RoleManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
            return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        }
    }

    // 2. Legacy fallback
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
    }
    val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolveInfo?.activityInfo?.packageName == context.packageName
}

fun openDefaultHomeSettings(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }
    }
    return try {
        val intent = Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

