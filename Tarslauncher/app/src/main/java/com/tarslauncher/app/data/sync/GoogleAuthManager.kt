package com.tarslauncher.app.data.sync

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class TarsAuthUser(
    val uid: String,
    val email: String? = null,
    val displayName: String? = null,
    val isAnonymous: Boolean = true
)

/**
 * Manages Google Sign-In and user identity for TARS Launcher.
 * Keeps user session persistent and provides reactive StateFlow updates for UI.
 */
class GoogleAuthManager(
    context: Context
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_AUTH_NAME, Context.MODE_PRIVATE)

    init {
        ensureInitialized()
    }

    private fun ensureInitialized() {
        synchronized(stateLock) {
            if (_authUserFlow.value == null) {
                _authUserFlow.value = resolveStoredOrGoogleUser()
            }
        }
    }

    private fun resolveStoredOrGoogleUser(): TarsAuthUser {
        val lastGoogle = runCatching { GoogleSignIn.getLastSignedInAccount(appContext) }.getOrNull()
        if (lastGoogle != null && (!lastGoogle.email.isNullOrBlank() || !lastGoogle.id.isNullOrBlank())) {
            val email = lastGoogle.email?.trim()
            val uid = buildGoogleUid(email, lastGoogle.id)
            prefs.edit()
                .putString(KEY_GOOGLE_UID, uid)
                .putString(KEY_GOOGLE_EMAIL, email)
                .putString(KEY_GOOGLE_NAME, lastGoogle.displayName)
                .apply()
            return TarsAuthUser(
                uid = uid,
                email = email,
                displayName = lastGoogle.displayName,
                isAnonymous = false
            )
        }

        val savedGoogleUid = prefs.getString(KEY_GOOGLE_UID, null)
        if (!savedGoogleUid.isNullOrBlank()) {
            return TarsAuthUser(
                uid = savedGoogleUid,
                email = prefs.getString(KEY_GOOGLE_EMAIL, null),
                displayName = prefs.getString(KEY_GOOGLE_NAME, null),
                isAnonymous = false
            )
        }

        val anonUid = getOrCreateAnonUid()
        return TarsAuthUser(
            uid = anonUid,
            email = null,
            displayName = null,
            isAnonymous = true
        )
    }

    private fun getOrCreateAnonUid(): String {
        val existing = prefs.getString(KEY_ANON_UID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = "anon_${UUID.randomUUID()}"
        prefs.edit().putString(KEY_ANON_UID, created).apply()
        return created
    }

    private fun buildGoogleUid(email: String?, googleId: String?): String {
        val cleanEmail = email?.trim()?.lowercase(Locale.ROOT)
        return if (!cleanEmail.isNullOrBlank()) {
            "google_$cleanEmail"
        } else {
            "google_${googleId?.trim().orEmpty().ifBlank { UUID.randomUUID().toString() }}"
        }
    }

    val authUserFlow: StateFlow<TarsAuthUser?>
        get() = _authUserFlow.asStateFlow()

    val currentUser: TarsAuthUser?
        get() {
            ensureInitialized()
            return _authUserFlow.value
        }

    val isGoogleSignedIn: Boolean
        get() = currentUser?.isAnonymous == false

    fun getGoogleSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        return GoogleSignIn.getClient(appContext, gso)
    }

    fun getSignInIntent(): Intent {
        return getGoogleSignInClient().signInIntent
    }

    suspend fun handleGoogleSignInResult(data: Intent?): Result<TarsAuthUser> = withContext(Dispatchers.IO) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            val email = account.email?.trim()
            val googleUid = buildGoogleUid(email, account.id)

            prefs.edit()
                .putString(KEY_GOOGLE_UID, googleUid)
                .putString(KEY_GOOGLE_EMAIL, email)
                .putString(KEY_GOOGLE_NAME, account.displayName)
                .apply()

            val newUser = TarsAuthUser(
                uid = googleUid,
                email = email,
                displayName = account.displayName,
                isAnonymous = false
            )
            _authUserFlow.value = newUser

            Result.success(newUser)
        } catch (e: ApiException) {
            Log.e(TAG, "Google sign-in ApiException: statusCode=${e.statusCode}", e)
            val msg = when (e.statusCode) {
                12501 -> "Google girişi iptal edildi."
                7 -> "Ağ bağlantı hatası. İnternet bağlantınızı kontrol edin."
                else -> "Google giriş hatası (Kod ${e.statusCode}): ${e.localizedMessage}"
            }
            Result.failure(IllegalStateException(msg))
        } catch (e: Exception) {
            Log.e(TAG, "Google sign-in failed", e)
            Result.failure(e)
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        runCatching {
            getGoogleSignInClient().signOut()
        }
        prefs.edit()
            .remove(KEY_GOOGLE_UID)
            .remove(KEY_GOOGLE_EMAIL)
            .remove(KEY_GOOGLE_NAME)
            .apply()

        val anonUser = TarsAuthUser(
            uid = getOrCreateAnonUid(),
            email = null,
            displayName = null,
            isAnonymous = true
        )
        _authUserFlow.value = anonUser
    }

    companion object {
        private const val TAG = "GoogleAuthManager"
        private const val PREFS_AUTH_NAME = "tars_supabase_auth_prefs"
        private const val KEY_ANON_UID = "anon_uid"
        private const val KEY_GOOGLE_UID = "google_uid"
        private const val KEY_GOOGLE_EMAIL = "google_email"
        private const val KEY_GOOGLE_NAME = "google_display_name"

        private val stateLock = Any()
        private val _authUserFlow = MutableStateFlow<TarsAuthUser?>(null)
    }
}
