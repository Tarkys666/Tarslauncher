package com.tarslauncher.app.data.sync

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.gson.JsonObject
import com.tarslauncher.app.BuildConfig
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TarsAuthUser(
    val uid: String,
    val email: String? = null,
    val displayName: String? = null,
    val isAnonymous: Boolean = true
)

/** Exchanges Google ID tokens for Supabase sessions and refreshes them before cloud operations. */
class GoogleAuthManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("tars_supabase_auth_prefs", Context.MODE_PRIVATE)

    init {
        synchronized(_authUserFlow) {
            if (_authUserFlow.value == null) {
                _authUserFlow.value = storedSession()?.user ?: anonymousUser()
            }
        }
    }

    val authUserFlow: StateFlow<TarsAuthUser?> = _authUserFlow.asStateFlow()
    val currentUser: TarsAuthUser? get() = _authUserFlow.value
    val isGoogleSignedIn: Boolean get() = currentUser?.isAnonymous == false

    private fun anonymousUser(): TarsAuthUser {
        val uid = prefs.getString("anon_uid", null) ?: "anon_${UUID.randomUUID()}".also {
            prefs.edit().putString("anon_uid", it).apply()
        }
        return TarsAuthUser(uid)
    }

    private fun storedSession(): SupabaseSession? {
        if (prefs.getString("session_url", null) != SupabaseConfig.SUPABASE_URL) return null
        val json = prefs.getString("session_json", null) ?: return null
        return runCatching { SupabaseSession.fromJson(json) }.getOrNull()
    }

    private fun saveSession(session: SupabaseSession) {
        check(prefs.edit()
            .putString("session_url", SupabaseConfig.SUPABASE_URL)
            .putString("session_json", session.toJson())
            .remove("google_uid").remove("google_email").remove("google_display_name")
            .commit()) { "Supabase oturumu cihaza kaydedilemedi." }
        _authUserFlow.value = session.user
    }

    private fun clearSession() {
        prefs.edit().remove("session_json").remove("session_url")
            .remove("google_uid").remove("google_email").remove("google_display_name").commit()
        _authUserFlow.value = anonymousUser()
    }

    fun getGoogleSignInClient(): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestProfile()
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
            options.requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID.trim())
        }
        return GoogleSignIn.getClient(appContext, options.build())
    }

    fun getSignInIntent(): Intent {
        check(SupabaseConfig.isConfigured()) { "Supabase bağlantı ayarları eksik." }
        check(BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) { "Google girişi yapılandırılmamış (GOOGLE_WEB_CLIENT_ID eksik)." }
        return getGoogleSignInClient().signInIntent
    }

    suspend fun handleGoogleSignInResult(data: Intent?): Result<TarsAuthUser> = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
                val idToken = account.idToken?.takeIf { it.isNotBlank() }
                    ?: error("Google kimlik token'ı alınamadı. Web OAuth istemci kimliğini kontrol edin.")
                val body = JsonObject().apply {
                    addProperty("provider", "google")
                    addProperty("id_token", idToken)
                }
                val response = SupabaseConfig.authRequest("token?grant_type=id_token", body.toString()).getOrThrow()
                val session = SupabaseSession.fromJson(response)
                saveSession(session)
                Result.success(session.user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                val message = when (e.statusCode) {
                    12501 -> "Google girişi iptal edildi."
                    7 -> "Ağ bağlantı hatası. İnternet bağlantınızı kontrol edin."
                    10 -> "Google OAuth ayarları uyuşmuyor. Paket adı, imza SHA-1 ve Web istemci kimliğini kontrol edin."
                    else -> "Google giriş hatası (Kod ${e.statusCode})."
                }
                Result.failure(IllegalStateException(message))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun requireSession(): SupabaseSession = withContext(Dispatchers.IO) {
        // Serialize refresh-token rotation across managers and concurrent backup/restore calls.
        sessionMutex.withLock {
            val session = storedSession() ?: run {
                _authUserFlow.value = anonymousUser()
                error("Bulut işlemleri için Google ile yeniden giriş yapmalısınız.")
            }
            if (!session.needsRefresh()) return@withLock session
            try {
                val body = JsonObject().apply { addProperty("refresh_token", session.refreshToken) }
                val response = SupabaseConfig.authRequest("token?grant_type=refresh_token", body.toString()).getOrThrow()
                val refreshed = SupabaseSession.fromJson(response)
                check(refreshed.user.uid == session.user.uid) { "Supabase oturum kullanıcısı değişti." }
                saveSession(refreshed)
                refreshed
            } catch (e: SupabaseHttpException) {
                if (e.statusCode in listOf(400, 401, 403)) {
                    clearSession()
                    throw IllegalStateException("Oturum süresi dolmuş. Google ile yeniden giriş yapın.", e)
                }
                throw e
            }
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            val session = storedSession()
            clearSession()
            getGoogleSignInClient().signOut()
            if (session != null) {
                SupabaseConfig.authRequest("logout?scope=local", accessToken = session.accessToken)
            }
        }
        Unit
    }

    companion object {
        private val sessionMutex = Mutex()
        private val _authUserFlow = MutableStateFlow<TarsAuthUser?>(null)
    }
}
