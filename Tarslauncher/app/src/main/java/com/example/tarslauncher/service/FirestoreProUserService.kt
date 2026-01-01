package com.example.tarslauncher.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.example.tarslauncher.data.ProUser
import kotlinx.coroutines.tasks.await
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Firestore-based Pro User Registration Service
 */
class FirestoreProUserService(private val context: Context) {
    
    companion object {
        private const val TAG = "FirestoreProUserService"
        private const val COLLECTION_PRO_USERS = "pro_users"
        private const val PRO_PRICE = 59.00 // TL
    }
    
    private val firestore = FirebaseFirestore.getInstance()
    private val proUsersCollection = firestore.collection(COLLECTION_PRO_USERS)
    
    /**
     * Register a new pro user
     */
    suspend fun registerProUser(
        googleAccountId: String,
        email: String,
        displayName: String,
        purchaseToken: String,
        orderId: String,
        purchaseTime: Long,
        purchaseState: Int
    ): Result<String> {
        return try {
            // Check if user already exists
            val existingUser = getUserByGoogleId(googleAccountId)
            if (existingUser != null) {
                Log.d(TAG, "User already registered: $email")
                return Result.success(existingUser.id)
            }
            
            val proUser = ProUser(
                googleAccountId = googleAccountId,
                email = email,
                displayName = displayName,
                purchaseToken = purchaseToken,
                orderId = orderId,
                purchaseTime = purchaseTime,
                purchaseState = purchaseState,
                registrationTime = Date(),
                isActive = true,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = Build.VERSION.RELEASE,
                appVersion = getAppVersion(),
                lastLoginTime = System.currentTimeMillis(),
                loginCount = 1
            )
            
            val docRef = proUsersCollection.add(proUser.toMap()).await()
            Log.d(TAG, "Pro user registered successfully: ${docRef.id}")
            
            Result.success(docRef.id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register pro user", e)
            Result.failure(e)
        }
    }
    
    /**
     * Get user by Google Account ID
     */
    suspend fun getUserByGoogleId(googleAccountId: String): ProUser? {
        return try {
            val querySnapshot = proUsersCollection
                .whereEqualTo("googleAccountId", googleAccountId)
                .whereEqualTo("isActive", true)
                .get()
                .await()
            
            if (!querySnapshot.isEmpty) {
                val document = querySnapshot.documents.first()
                document.toObject(ProUser::class.java as Class<ProUser>)?.copy(id = document.id)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get user by Google ID", e)
            null
        }
    }
    
    /**
     * Get user by email
     */
    suspend fun getUserByEmail(email: String): ProUser? {
        return try {
            val querySnapshot = proUsersCollection
                .whereEqualTo("email", email)
                .whereEqualTo("isActive", true)
                .get()
                .await()
            
            if (!querySnapshot.isEmpty) {
                val document = querySnapshot.documents.first()
                document.toObject(ProUser::class.java as Class<ProUser>)?.copy(id = document.id)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get user by email", e)
            null
        }
    }
    
    /**
     * Update user login information
     */
    suspend fun updateUserLogin(googleAccountId: String): Result<Unit> {
        return try {
            val user = getUserByGoogleId(googleAccountId)
            if (user != null) {
                proUsersCollection.document(user.id)
                    .update(
                        mapOf(
                            "lastLoginTime" to System.currentTimeMillis(),
                            "loginCount" to (user.loginCount + 1)
                        )
                    )
                    .await()
                
                Log.d(TAG, "User login updated: ${user.email}")
                Result.success(Unit)
            } else {
                Result.failure(Exception("User not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update user login", e)
            Result.failure(e)
        }
    }
    
    /**
     * Validate pro user status
     */
    suspend fun validateProUser(googleAccountId: String): Boolean {
        return try {
            val user = getUserByGoogleId(googleAccountId)
            val isValid = user != null && user.isActive && user.purchaseState == 1
            
            if (isValid && user != null) {
                // Update last login
                updateUserLogin(googleAccountId)
            }
            
            Log.d(TAG, "Pro user validation for $googleAccountId: $isValid")
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate pro user", e)
            false
        }
    }
    
    
    /**
     * Get app version
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }
}
