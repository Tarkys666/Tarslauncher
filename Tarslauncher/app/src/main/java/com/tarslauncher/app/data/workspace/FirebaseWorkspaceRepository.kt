package com.tarslauncher.app.data.workspace

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.tarslauncher.app.data.model.ChecklistItem
import com.tarslauncher.app.data.model.WorkspaceItem
import com.tarslauncher.app.data.model.WorkspaceItemType
import com.tarslauncher.app.data.sync.GoogleAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Real-time Notion-style Workspace Repository backed by Firebase Firestore.
 * Stores tasks, notes, and ideas under `users/{uid}` (`workspaceItems`) with offline cache support.
 */
class FirebaseWorkspaceRepository(
    context: Context,
    private val authManager: GoogleAuthManager = GoogleAuthManager(context)
) {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /**
     * Real-time stream of workspace items for the currently authenticated Firebase user.
     */
    val workspaceItemsFlow: Flow<List<WorkspaceItem>> = callbackFlow {
        var firestoreRegistration: ListenerRegistration? = null

        fun attachToUser(uid: String?) {
            firestoreRegistration?.remove()
            firestoreRegistration = null

            if (uid.isNullOrBlank()) {
                trySend(emptyList())
                return
            }

            firestoreRegistration = firestore.collection(COLLECTION_USERS)
                .document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Firestore workspace listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot == null || !snapshot.exists()) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val items = parseItemsFromDocument(snapshot.data)
                    trySend(items)
                }
        }

        val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            attachToUser(user?.uid)
        }

        auth.addAuthStateListener(authListener)
        if (auth.currentUser == null) {
            auth.signInAnonymously()
        } else {
            attachToUser(auth.currentUser?.uid)
        }

        awaitClose {
            auth.removeAuthStateListener(authListener)
            firestoreRegistration?.remove()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun getItems(): List<WorkspaceItem> = withContext(Dispatchers.IO) {
        val uid = authManager.ensureSessionUid().getOrNull() ?: return@withContext emptyList()
        runCatching {
            val snapshot = firestore.collection(COLLECTION_USERS)
                .document(uid)
                .get()
                .await()
            parseItemsFromDocument(snapshot.data)
        }.getOrDefault(emptyList())
    }

    suspend fun addItem(item: WorkspaceItem): Result<WorkspaceItem> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = authManager.ensureSessionUid().getOrThrow()
            val current = getItems().toMutableList()
            current.add(0, item)
            saveItemsToFirestore(uid, current)
            item
        }
    }

    suspend fun updateItem(updated: WorkspaceItem): Result<WorkspaceItem> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = authManager.ensureSessionUid().getOrThrow()
            val current = getItems().toMutableList()
            val index = current.indexOfFirst { it.id.equals(updated.id, ignoreCase = true) }
            if (index == -1) {
                throw IllegalArgumentException("Güncellenecek kayıt bulunamadı.")
            }
            val stamped = updated.copy(updatedAt = System.currentTimeMillis())
            current[index] = stamped
            saveItemsToFirestore(uid, current)
            stamped
        }
    }

    suspend fun toggleItemCompleted(itemId: String, completed: Boolean): Result<WorkspaceItem> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = authManager.ensureSessionUid().getOrThrow()
            val current = getItems().toMutableList()
            val index = current.indexOfFirst { it.id.equals(itemId, ignoreCase = true) }
            if (index == -1) throw IllegalArgumentException("Kayıt bulunamadı: $itemId")

            val target = current[index]
            val updatedChecklist = if (completed && target.checklist.isNotEmpty()) {
                target.checklist.map { it.copy(isDone = true) }
            } else {
                target.checklist
            }
            val updated = target.copy(
                isCompleted = completed,
                checklist = updatedChecklist,
                updatedAt = System.currentTimeMillis()
            )
            current[index] = updated
            saveItemsToFirestore(uid, current)
            updated
        }
    }

    suspend fun toggleChecklistSubItem(itemId: String, subItemId: String, done: Boolean): Result<WorkspaceItem> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = authManager.ensureSessionUid().getOrThrow()
            val current = getItems().toMutableList()
            val index = current.indexOfFirst { it.id.equals(itemId, ignoreCase = true) }
            if (index == -1) throw IllegalArgumentException("Kayıt bulunamadı: $itemId")

            val target = current[index]
            val newChecklist = target.checklist.map { sub ->
                if (sub.id == subItemId) sub.copy(isDone = done) else sub
            }
            val allDone = newChecklist.isNotEmpty() && newChecklist.all { it.isDone }
            val updated = target.copy(
                checklist = newChecklist,
                isCompleted = if (allDone) true else target.isCompleted,
                updatedAt = System.currentTimeMillis()
            )
            current[index] = updated
            saveItemsToFirestore(uid, current)
            updated
        }
    }

    suspend fun completeByQuery(idOrQuery: String): Result<WorkspaceItem> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = authManager.ensureSessionUid().getOrThrow()
            val current = getItems().toMutableList()
            val index = findMatchingIndex(current, idOrQuery, preferTasks = true)
                ?: throw IllegalArgumentException("'$idOrQuery' ile eşleşen görev veya kayıt bulunamadı.")

            val target = current[index]
            val updated = target.copy(
                isCompleted = true,
                checklist = target.checklist.map { it.copy(isDone = true) },
                updatedAt = System.currentTimeMillis()
            )
            current[index] = updated
            saveItemsToFirestore(uid, current)
            updated
        }
    }

    suspend fun deleteByQuery(idOrQuery: String): Result<WorkspaceItem> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = authManager.ensureSessionUid().getOrThrow()
            val current = getItems().toMutableList()
            val index = findMatchingIndex(current, idOrQuery, preferTasks = false)
                ?: throw IllegalArgumentException("'$idOrQuery' ile eşleşen silinecek kayıt bulunamadı.")

            val removed = current.removeAt(index)
            saveItemsToFirestore(uid, current)
            removed
        }
    }

    suspend fun clearCompletedTasks(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = authManager.ensureSessionUid().getOrThrow()
            val current = getItems()
            val remaining = current.filterNot { it.type == WorkspaceItemType.TASK && it.isCompleted }
            val removedCount = current.size - remaining.size
            if (removedCount > 0) {
                saveItemsToFirestore(uid, remaining)
            }
            removedCount
        }
    }

    suspend fun clearAll(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = authManager.ensureSessionUid().getOrThrow()
            saveItemsToFirestore(uid, emptyList())
        }
    }

    private fun findMatchingIndex(
        items: List<WorkspaceItem>,
        idOrQuery: String,
        preferTasks: Boolean
    ): Int? {
        val trimmed = idOrQuery.trim()
        if (trimmed.isBlank() || items.isEmpty()) return null

        val lower = trimmed.lowercase(Locale.forLanguageTag("tr-TR"))
        if (lower == "son" || lower == "en son" || lower == "last") {
            if (preferTasks) {
                val taskIdx = items.indexOfFirst { it.type == WorkspaceItemType.TASK && !it.isCompleted }
                if (taskIdx != -1) return taskIdx
            }
            return 0
        }

        val byId = items.indexOfFirst { it.id.equals(trimmed, ignoreCase = true) }
        if (byId != -1) return byId

        val byExactTitle = items.indexOfFirst { it.title.equals(trimmed, ignoreCase = true) }
        if (byExactTitle != -1) return byExactTitle

        val byTitleContains = items.indexOfFirst {
            it.title.lowercase(Locale.forLanguageTag("tr-TR")).contains(lower)
        }
        if (byTitleContains != -1) return byTitleContains

        val byContentContains = items.indexOfFirst {
            it.content.lowercase(Locale.forLanguageTag("tr-TR")).contains(lower)
        }
        if (byContentContains != -1) return byContentContains

        return null
    }

    private suspend fun saveItemsToFirestore(uid: String, items: List<WorkspaceItem>) {
        val bounded = items.take(MAX_WORKSPACE_ITEMS).map { it.sanitized().toFirestoreMap() }
        val payload = hashMapOf<String, Any>(
            FIELD_WORKSPACE_ITEMS to bounded,
            FIELD_WORKSPACE_UPDATED_AT to System.currentTimeMillis()
        )
        firestore.collection(COLLECTION_USERS)
            .document(uid)
            .set(payload, SetOptions.merge())
            .await()
    }

    private fun parseItemsFromDocument(data: Map<String, Any?>?): List<WorkspaceItem> {
        if (data == null) return emptyList()
        val rawList = data[FIELD_WORKSPACE_ITEMS] as? List<*> ?: return emptyList()
        return rawList.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val typedMap = map as Map<String, Any?>
            val id = (typedMap["id"] as? String).orEmpty()
            WorkspaceItem.fromFirestoreMap(id, typedMap)
        }.sortedByDescending { it.createdAt }.take(MAX_WORKSPACE_ITEMS)
    }

    companion object {
        private const val TAG = "FirebaseWorkspaceRepo"
        private const val COLLECTION_USERS = "users"
        private const val FIELD_WORKSPACE_ITEMS = "workspaceItems"
        private const val FIELD_WORKSPACE_UPDATED_AT = "workspaceUpdatedAt"
        const val MAX_WORKSPACE_ITEMS = 300
    }
}
