package com.tarslauncher.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure preferences helper for storing sensitive data.
 * Ensures data is encrypted at rest using Android Keystore and never silently falls back to plaintext.
 */
object SecurePreferences {
    private const val TAG = "SecurePreferences"
    private const val PREFS_NAME = "tarslauncher_secure_prefs"
    
    /**
     * Get encrypted shared preferences instance, or null if secure storage is unavailable.
     */
    fun getEncryptedPreferences(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences initialization failed", e)
            null
        }
    }
}
