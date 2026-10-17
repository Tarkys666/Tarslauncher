package com.tarslauncher.app

import android.app.AlertDialog
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.tarslauncher.app.data.model.PwaInfo
import com.tarslauncher.app.repository.AppRepository
import com.tarslauncher.app.utils.PwaLauncherHelper
import com.tarslauncher.app.utils.bitmapToBase64
import com.tarslauncher.app.utils.drawableToBitmap
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Handles system shortcut pinning requests (Android 8.0+ / API 26+)
 * initiated by browsers like Chrome, Edge, Brave, etc. when users tap "Add to Home screen" or "Install".
 */
class PinShortcutActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PinShortcutActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            handlePinShortcut()
        } else {
            finish()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handlePinShortcut() {
        val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (launcherApps == null) {
            finish()
            return
        }

        val pinItemRequest = try {
            launcherApps.getPinItemRequest(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining pin item request (BadParcelableException or invalid intent)", e)
            null
        }

        if (pinItemRequest == null || pinItemRequest.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            finish()
            return
        }

        val shortcutInfo = try {
            pinItemRequest.shortcutInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing shortcutInfo", e)
            null
        }

        if (shortcutInfo == null) {
            finish()
            return
        }

        val label = shortcutInfo.shortLabel?.toString()
            ?: shortcutInfo.longLabel?.toString()
            ?: "Web App"

        val targetIntent = try {
            shortcutInfo.intent ?: shortcutInfo.intents?.firstOrNull()
        } catch (e: Exception) {
            null
        }
        val rawUrl = targetIntent?.dataString ?: targetIntent?.getStringExtra("url") ?: ""
        val sanitizedUrl = PwaLauncherHelper.sanitizeWebUrl(rawUrl)

        val shortcutPackage = shortcutInfo.`package`
        val shortcutId = shortcutInfo.id

        // Must have either a valid web URL or a valid system shortcut reference
        if (sanitizedUrl == null && (shortcutPackage.isNullOrBlank() || shortcutId.isNullOrBlank())) {
            Log.w(TAG, "Rejected pin request: neither a valid web URL nor valid shortcut ID could be resolved")
            finish()
            return
        }

        val densityDpi = resources.displayMetrics.densityDpi
        val iconDrawable = try {
            launcherApps.getShortcutIconDrawable(shortcutInfo, densityDpi)
        } catch (e: Exception) {
            null
        }

        val dialogMessage = if (!sanitizedUrl.isNullOrBlank()) {
            "\"$label\" kısayolunu ana ekranınıza eklemek istiyor musunuz?\n\nHedef: $sanitizedUrl"
        } else {
            "\"$label\" kısayolunu ana ekranınıza eklemek istiyor musunuz?"
        }

        var isAccepted = false

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Kısayol Ekle")
            .setMessage(dialogMessage)
            .setPositiveButton("Ekle") { _, _ ->
                isAccepted = true
                saveAndAcceptShortcut(
                    pinItemRequest = pinItemRequest,
                    label = label,
                    sanitizedUrl = sanitizedUrl,
                    shortcutPackage = shortcutPackage,
                    shortcutId = shortcutId,
                    iconDrawable = iconDrawable
                )
            }
            .setNegativeButton("İptal") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .setOnDismissListener {
                if (!isAccepted) {
                    finish()
                }
            }

        if (iconDrawable != null) {
            builder.setIcon(iconDrawable)
        }

        if (!isFinishing && !isDestroyed) {
            val dialog = builder.create()
            dialog.window?.decorView?.filterTouchesWhenObscured = true
            dialog.show()
        } else {
            finish()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun saveAndAcceptShortcut(
        pinItemRequest: LauncherApps.PinItemRequest,
        label: String,
        sanitizedUrl: String?,
        shortcutPackage: String?,
        shortcutId: String?,
        iconDrawable: android.graphics.drawable.Drawable?
    ) {
        val repository = AppRepository(applicationContext)

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val iconBase64 = iconDrawable?.let { drawable ->
                val bitmap = drawableToBitmap(drawable)
                val base64 = bitmapToBase64(bitmap)
                if (drawable !is android.graphics.drawable.BitmapDrawable || drawable.bitmap != bitmap) {
                    bitmap.recycle()
                }
                base64
            }

            val pwaInfo = PwaInfo(
                id = UUID.randomUUID().toString().take(8),
                name = label,
                url = sanitizedUrl ?: "",
                shortcutPackage = shortcutPackage,
                shortcutId = shortcutId,
                iconBase64 = iconBase64
            )

            try {
                repository.savePwa(pwaInfo)
                if (pinItemRequest.isValid) {
                    pinItemRequest.accept()
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(applicationContext, "$label TARS Launcher'a eklendi", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting pin shortcut request", e)
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    finish()
                }
            }
        }
    }
}
