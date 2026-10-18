package com.tarslauncher.app.agent.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AgentProvider {
    GROQ
}

class AgentPreferences(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getProvider(): AgentProvider = AgentProvider.GROQ

    fun getGroqApiKey(): String {
        val encrypted = prefs.getString(KEY_GROQ_API_KEY_ENCRYPTED, null)
        if (!encrypted.isNullOrBlank()) {
            val decrypted = decryptSecret(encrypted)
            if (!decrypted.isNullOrBlank()) {
                return decrypted
            }
        }

        // Migrate legacy plaintext key if present
        val legacyPlain = prefs.getString(KEY_GROQ_API_KEY_LEGACY, "").orEmpty().trim()
        if (legacyPlain.isNotBlank()) {
            setGroqApiKey(legacyPlain)
            return legacyPlain
        }
        return ""
    }

    fun setGroqApiKey(key: String) {
        val trimmed = key.trim()
        val editor = prefs.edit().remove(KEY_GROQ_API_KEY_LEGACY)
        if (trimmed.isBlank()) {
            editor.remove(KEY_GROQ_API_KEY_ENCRYPTED).apply()
            return
        }
        val encrypted = encryptSecret(trimmed)
        if (encrypted != null) {
            editor.putString(KEY_GROQ_API_KEY_ENCRYPTED, encrypted).apply()
        } else {
            // Fallback only if Keystore is unavailable (e.g. unit test JVM environment)
            editor.putString(KEY_GROQ_API_KEY_LEGACY, trimmed).apply()
        }
    }

    fun getGroqModel(): String = GroqRemoteDataSource.DEFAULT_GROQ_MODEL

    fun getGoogleWebClientId(): String {
        val custom = prefs.getString(KEY_GOOGLE_WEB_CLIENT_ID, "").orEmpty().trim()
        if (custom.isNotBlank()) return custom

        val resId = appContext.resources.getIdentifier(
            "default_web_client_id",
            "string",
            appContext.packageName
        )
        if (resId != 0) {
            val fromRes = runCatching { appContext.getString(resId).trim() }.getOrDefault("")
            if (fromRes.isNotBlank()) return fromRes
        }
        return DEFAULT_GOOGLE_WEB_CLIENT_ID
    }

    fun setGoogleWebClientId(clientId: String) {
        prefs.edit().putString(KEY_GOOGLE_WEB_CLIENT_ID, clientId.trim()).apply()
    }

    fun isAgentEnabled(): Boolean {
        return prefs.getBoolean(KEY_AGENT_ENABLED, true)
    }

    fun setAgentEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AGENT_ENABLED, enabled).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                return existing.secretKey
            }
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }.getOrNull()
    }

    private fun encryptSecret(plainText: String): String? {
        return runCatching {
            val secretKey = getOrCreateSecretKey() ?: return null
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            "$ivB64:$dataB64"
        }.getOrNull()
    }

    private fun decryptSecret(payload: String): String? {
        return runCatching {
            val parts = payload.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val secretKey = getOrCreateSecretKey() ?: return null
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        private const val PREFS_NAME = "tars_agent_prefs"
        private const val KEY_AGENT_ENABLED = "agent_master_enabled"
        private const val KEY_GROQ_API_KEY_LEGACY = "groq_api_key"
        private const val KEY_GROQ_API_KEY_ENCRYPTED = "groq_api_key_enc_v1"
        private const val KEY_GOOGLE_WEB_CLIENT_ID = "google_web_client_id"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "tars_agent_groq_key_v1"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128

        const val DEFAULT_GOOGLE_WEB_CLIENT_ID =
            "691832963274-f6mn8av1khpvfbpgm30gs0rff45518e2.apps.googleusercontent.com"
    }
}
