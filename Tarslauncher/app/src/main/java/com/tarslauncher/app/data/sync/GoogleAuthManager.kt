package com.tarslauncher.app.data.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.tarslauncher.app.agent.data.AgentPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Manages Firebase Authentication with Google Sign-In and seamless anonymous-to-Google migration.
 */
class GoogleAuthManager(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val agentPrefs by lazy { AgentPreferences(appContext) }

    val authUserFlow: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        awaitClose {
            auth.removeAuthStateListener(listener)
        }
    }

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isGoogleSignedIn: Boolean
        get() = auth.currentUser?.let { !it.isAnonymous } == true

    fun hasWebClientId(): Boolean {
        return agentPrefs.getGoogleWebClientId().isNotBlank()
    }

    fun getGoogleSignInClient(): GoogleSignInClient? {
        val webClientId = agentPrefs.getGoogleWebClientId()
        if (webClientId.isBlank()) return null

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(appContext, gso)
    }

    suspend fun handleGoogleSignInResult(data: Intent?): Result<FirebaseUser> = withContext(Dispatchers.IO) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
                ?: return@withContext Result.failure(IllegalStateException("Google ID Token alınamadı. Lütfen Web Client ID yapılandırmasını kontrol edin."))

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val previousUser = auth.currentUser
            val previousAnonUid = if (previousUser?.isAnonymous == true) previousUser.uid else null

            val firebaseUser = if (previousUser?.isAnonymous == true && previousAnonUid != null) {
                try {
                    // Link anonymous account directly to Google so UID and data stay intact
                    previousUser.linkWithCredential(credential).await().user
                } catch (_: FirebaseAuthUserCollisionException) {
                    // Read anonymous workspace items while still authenticated as previousAnonUid
                    val anonWorkspaceItems = readWorkspaceItemsRaw(previousAnonUid)
                    // Google account already exists in Firebase; sign in and merge anonymous workspace items
                    val signedIn = auth.signInWithCredential(credential).await().user
                    if (signedIn != null && previousAnonUid != signedIn.uid && anonWorkspaceItems.isNotEmpty()) {
                        mergeAnonymousWorkspaceItems(signedIn.uid, anonWorkspaceItems)
                    }
                    signedIn
                }
            } else {
                auth.signInWithCredential(credential).await().user
            }

            if (firebaseUser != null) {
                Result.success(firebaseUser)
            } else {
                Result.failure(IllegalStateException("Firebase Google oturumu açılamadı."))
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Google sign-in ApiException: statusCode=${e.statusCode}", e)
            val msg = when (e.statusCode) {
                12501 -> "Google girişi iptal edildi."
                10 -> "Geliştirici yapılandırma hatası (Kod 10): Firebase Console'da SHA-1 parmak izi veya Web Client ID eksik."
                7 -> "Ağ bağlantı hatası. İnternet bağlantınızı kontrol edin."
                else -> "Google giriş hatası (Kod ${e.statusCode}): ${e.localizedMessage}"
            }
            Result.failure(IllegalStateException(msg))
        } catch (e: Exception) {
            Log.e(TAG, "Google sign-in failed", e)
            Result.failure(e)
        }
    }

    suspend fun ensureSessionUid(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val existing = auth.currentUser
            if (existing != null) {
                return@withContext Result.success(existing.uid)
            }
            val anonResult = auth.signInAnonymously().await()
            val uid = anonResult.user?.uid
            if (!uid.isNullOrBlank()) {
                Result.success(uid)
            } else {
                Result.failure(IllegalStateException("Firebase oturumu başlatılamadı."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure session UID", e)
            Result.failure(e)
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        runCatching {
            getGoogleSignInClient()?.signOut()?.await()
        }
        auth.signOut()
    }

    private suspend fun readWorkspaceItemsRaw(uid: String): List<Map<String, Any?>> {
        return runCatching {
            val doc = firestore.collection("users").document(uid).get().await()
            val rawList = doc.data?.get("workspaceItems") as? List<*> ?: return emptyList()
            rawList.mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                entry as? Map<String, Any?>
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun mergeAnonymousWorkspaceItems(toUid: String, anonItems: List<Map<String, Any?>>) {
        runCatching {
            val targetRef = firestore.collection("users").document(toUid)
            val existingItems = readWorkspaceItemsRaw(toUid)
            val existingIds = existingItems.mapNotNull { it["id"] as? String }.toSet()
            val newItems = anonItems.filter { (it["id"] as? String) !in existingIds }
            if (newItems.isNotEmpty()) {
                val merged = newItems + existingItems
                val payload = hashMapOf<String, Any>(
                    "workspaceItems" to merged,
                    "workspaceUpdatedAt" to System.currentTimeMillis()
                )
                targetRef.set(payload, SetOptions.merge()).await()
                Log.i(TAG, "Merged ${newItems.size} anonymous workspace items into Google account $toUid")
            }
        }.onFailure { e ->
            Log.w(TAG, "Workspace migration from anonymous account skipped: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GoogleAuthManager"
    }
}
