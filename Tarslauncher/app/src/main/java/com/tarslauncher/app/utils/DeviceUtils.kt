package com.tarslauncher.app.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.tarslauncher.app.MyDeviceAdminReceiver

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
