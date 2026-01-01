package com.tarslauncher.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log


class ProModeManager(
    private val context: Context,
    private var onProStatusChanged: ((Boolean) -> Unit)? = null
) {
    
    companion object {
        private const val TAG = "ProModeManager"
    }
    
    private var isInitialized = false
    private var currentProStatus = true
    
    fun initialize() {
        if (isInitialized) return
        
        currentProStatus = true
        setProStatus(true)
        isInitialized = true
        Log.d(TAG, "ProModeManager initialized - Pro enabled by default")
    }
    
    fun signInWithGoogle(activity: Activity, onResult: (Boolean, String?) -> Unit) {
        // Pro gereksinimi kaldırıldığı için gerçek giriş akışı yok
        onResult(true, null)
    }
    
    fun purchaseProVersion(activity: Activity) {
        // Satın alma akışı kaldırıldı, Pro zaten açık
        setProStatus(true)
    }
    
    private fun setProStatus(isPro: Boolean) {
        currentProStatus = true
        onProStatusChanged?.invoke(true)

        // Save to secure preferences with timestamp (always true)
        val prefs = SecurePreferences.getEncryptedPreferences(context)
        prefs.edit()
            .putBoolean("is_pro_user", true)
            .putLong("pro_activation_time", System.currentTimeMillis())
            .apply()

        Log.d(TAG, "Pro status forced to true")
    }
    
    fun validateProStatus(): Boolean {
        // Pro gereksinimi kaldırıldı, her zaman true
        setProStatus(true)
        return true
    }
    
    fun getCurrentProStatus(): Boolean = true
    
    fun signOut() {
        // Önceden Google hesabından çıkış yapılıyordu; artık no-op
    }
    
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // Önceden Google Sign-In sonucu burada işleniyordu; artık kullanılmıyor
    }
    
    fun cleanup() {
        isInitialized = false
        Log.d(TAG, "ProModeManager cleaned up (no-op)")
    }
}
