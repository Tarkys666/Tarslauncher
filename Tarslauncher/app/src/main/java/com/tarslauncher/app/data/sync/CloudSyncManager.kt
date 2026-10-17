package com.tarslauncher.app.data.sync

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.tarslauncher.app.data.datastore.LauncherDataStore
import com.tarslauncher.app.data.model.GridSize
import com.tarslauncher.app.data.model.LauncherSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class CloudSyncManager(
    private val context: Context,
    private val dataStore: LauncherDataStore = LauncherDataStore(context)
) {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    companion object {
        private const val TAG = "CloudSyncManager"
        private const val COLLECTION_USERS = "users"
    }

    suspend fun getCurrentUser(): FirebaseUser? = withContext(Dispatchers.IO) {
        auth.currentUser
    }

    suspend fun ensureAuthenticated(): Result<FirebaseUser> = withContext(Dispatchers.IO) {
        try {
            val current = auth.currentUser
            if (current != null) {
                return@withContext Result.success(current)
            }
            val authResult = auth.signInAnonymously().await()
            val user = authResult.user
            if (user != null) {
                Log.i(TAG, "Anonymous sign-in successful: ${user.uid}")
                Result.success(user)
            } else {
                Result.failure(Exception("Kullanıcı kimliği oluşturulamadı."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Authentication failed", e)
            Result.failure(e)
        }
    }

    suspend fun backupLayout(currentSettings: LauncherSettings): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val authResult = ensureAuthenticated()
            val user = authResult.getOrNull()
                ?: return@withContext Result.failure(authResult.exceptionOrNull() ?: Exception("Giriş yapılamadı."))

            val now = System.currentTimeMillis()
            val foldersJson = dataStore.getFoldersJson() ?: "[]"
            val pwasJson = dataStore.getPwasJson() ?: "[]"
            val usageStatsJson = dataStore.getUsageStatsJson() ?: "[]"
            val notesJson = dataStore.getNotesJson() ?: "[]"

            val settingsMap = hashMapOf<String, Any?>(
                "showAppDrawer" to currentSettings.showAppDrawer,
                "gridSize" to currentSettings.gridSize.serialize(),
                "buttonColorMode" to currentSettings.buttonColorMode,
                "showTarsHeader" to currentSettings.showTarsHeader,
                "showClock" to currentSettings.showClock,
                "selectedIconPack" to currentSettings.selectedIconPack,
                "appDrawerIconSize" to currentSettings.appDrawerIconSize
            )

            val backupPayload = hashMapOf<String, Any?>(
                "updatedAt" to now,
                "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "appVersion" to com.tarslauncher.app.BuildConfig.VERSION_NAME,
                "foldersJson" to foldersJson,
                "pwasJson" to pwasJson,
                "usageStatsJson" to usageStatsJson,
                "notesJson" to notesJson,
                "settings" to settingsMap
            )

            // Write securely to users/{userId} (enforced by Firestore rules)
            firestore.collection(COLLECTION_USERS)
                .document(user.uid)
                .set(backupPayload, SetOptions.merge())
                .await()

            dataStore.setLastBackupTime(now)
            Log.i(TAG, "Backup successfully uploaded to Firestore for uid: ${user.uid}")
            Result.success(now)
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            Result.failure(e)
        }
    }

    suspend fun restoreLayout(): Result<LauncherSettings> = withContext(Dispatchers.IO) {
        try {
            val authResult = ensureAuthenticated()
            val user = authResult.getOrNull()
                ?: return@withContext Result.failure(authResult.exceptionOrNull() ?: Exception("Giriş yapılamadı."))

            val snapshot = firestore.collection(COLLECTION_USERS)
                .document(user.uid)
                .get()
                .await()

            if (!snapshot.exists()) {
                return@withContext Result.failure(Exception("Bulutta bu hesaba ait kayıtlı bir yedek bulunamadı."))
            }

            val foldersJson = snapshot.getString("foldersJson")
            if (!foldersJson.isNullOrBlank()) {
                dataStore.saveFoldersJson(foldersJson)
            }

            val pwasJson = snapshot.getString("pwasJson")
            if (!pwasJson.isNullOrBlank()) {
                dataStore.savePwasJson(pwasJson)
            }

            val usageStatsJson = snapshot.getString("usageStatsJson")
            if (!usageStatsJson.isNullOrBlank()) {
                dataStore.saveUsageStatsJson(usageStatsJson)
            }

            val notesJson = snapshot.getString("notesJson")
            if (!notesJson.isNullOrBlank()) {
                dataStore.saveNotesJson(notesJson)
            }

            val updatedAt = snapshot.getLong("updatedAt") ?: System.currentTimeMillis()
            dataStore.setLastBackupTime(updatedAt)

            @Suppress("UNCHECKED_CAST")
            val settingsMap = snapshot.get("settings") as? Map<String, Any?>
            val restoredSettings = if (settingsMap != null) {
                val settings = LauncherSettings(
                    showAppDrawer = settingsMap["showAppDrawer"] as? Boolean ?: true,
                    gridSize = GridSize.fromString(settingsMap["gridSize"] as? String),
                    buttonColorMode = settingsMap["buttonColorMode"] as? String ?: "black",
                    showTarsHeader = settingsMap["showTarsHeader"] as? Boolean ?: true,
                    showClock = settingsMap["showClock"] as? Boolean ?: true,
                    selectedIconPack = settingsMap["selectedIconPack"] as? String,
                    appDrawerIconSize = (settingsMap["appDrawerIconSize"] as? Number)?.toInt() ?: 32
                )
                dataStore.saveAllSettings(settings)
                settings
            } else {
                LauncherSettings()
            }

            Log.i(TAG, "Layout successfully restored from Firestore for uid: ${user.uid}")
            Result.success(restoredSettings)
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            Result.failure(e)
        }
    }
}
