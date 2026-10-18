package com.tarslauncher.app.data.workspace

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tarslauncher.app.data.model.ChecklistItem
import com.tarslauncher.app.data.model.WorkspaceItem
import com.tarslauncher.app.data.model.WorkspaceItemType
import com.tarslauncher.app.data.model.WorkspacePriority
import com.tarslauncher.app.data.sync.GoogleAuthManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Second Brain Vault & Cloud Workspace Repository (Obsidian + Claude style).
 * - Persists all notes, tasks, and ideas locally in an instant-access Vault (JSON + Obsidian `.md` files)
 *   AND synchronizes them in real time with Firebase Firestore (`users/{uid}`).
 * - Ensures the AI chatbot and Notes UI always have 100% access to every note, online or offline.
 */
class FirebaseWorkspaceRepository(
    context: Context,
    private val authManager: GoogleAuthManager = GoogleAuthManager(context)
) {
    private val appContext = context.applicationContext
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_VAULT_NAME, Context.MODE_PRIVATE)
    }

    init {
        ensureLocalCacheLoaded()
    }

    private fun ensureLocalCacheLoaded() {
        synchronized(cacheLock) {
            if (!isCacheInitialized) {
                val cached = readLocalVaultItems()
                vaultStateFlow.value = cached
                isCacheInitialized = true
            }
        }
    }

    /**
     * Real-time stream of Second Brain items combining instant local vault state and Firestore cloud sync.
     */
    val workspaceItemsFlow: Flow<List<WorkspaceItem>> = callbackFlow {
        ensureLocalCacheLoaded()
        trySend(vaultStateFlow.value)

        val localCollectorJob = launch {
            vaultStateFlow.collect { items ->
                trySend(items)
            }
        }

        var firestoreRegistration: ListenerRegistration? = null

        fun attachToUser(uid: String?) {
            firestoreRegistration?.remove()
            firestoreRegistration = null

            if (uid.isNullOrBlank()) {
                trySend(vaultStateFlow.value)
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
                        val localItems = vaultStateFlow.value
                        if (localItems.isNotEmpty()) {
                            launch(Dispatchers.IO) {
                                runCatching { saveItemsToFirestore(uid, localItems) }
                            }
                        }
                        return@addSnapshotListener
                    }
                    val remoteItems = parseItemsFromDocument(snapshot.data)
                    val merged = mergeVaultItems(local = vaultStateFlow.value, remote = remoteItems)
                    updateLocalVault(merged)
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
            localCollectorJob.cancel()
            auth.removeAuthStateListener(authListener)
            firestoreRegistration?.remove()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Returns all Second Brain items, merging local vault and cloud state so the AI chatbot
     * always has full access to every stored note.
     */
    suspend fun getItems(): List<WorkspaceItem> = withContext(Dispatchers.IO) {
        ensureLocalCacheLoaded()
        val localItems = vaultStateFlow.value
        val uid = authManager.ensureSessionUid().getOrNull() ?: return@withContext localItems

        val remoteItems = runCatching {
            val snapshot = firestore.collection(COLLECTION_USERS)
                .document(uid)
                .get()
                .await()
            if (snapshot.exists()) parseItemsFromDocument(snapshot.data) else null
        }.getOrNull()

        if (remoteItems == null) {
            return@withContext localItems
        }

        val merged = mergeVaultItems(local = localItems, remote = remoteItems)
        if (merged != localItems) {
            updateLocalVault(merged)
        }
        if (merged.size > remoteItems.size) {
            runCatching { saveItemsToFirestore(uid, merged) }
        }
        merged
    }

    suspend fun addItem(item: WorkspaceItem): Result<WorkspaceItem> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanItem = item.sanitized()
            val current = getItems().toMutableList()
            current.removeAll { it.id.equals(cleanItem.id, ignoreCase = true) }
            current.add(0, cleanItem)
            persistVaultAndCloud(current)
            cleanItem
        }
    }

    suspend fun updateItem(updated: WorkspaceItem): Result<WorkspaceItem> = withContext(Dispatchers.IO) {
        runCatching {
            val current = getItems().toMutableList()
            val index = current.indexOfFirst { it.id.equals(updated.id, ignoreCase = true) }
            if (index == -1) {
                throw IllegalArgumentException("Güncellenecek kayıt bulunamadı.")
            }
            val stamped = updated.copy(updatedAt = System.currentTimeMillis()).sanitized()
            current[index] = stamped
            persistVaultAndCloud(current)
            stamped
        }
    }

    /**
     * Obsidian + Claude style note update/append: finds an existing note by query (or creates one if not found)
     * and appends or updates its content, checklist, and tags.
     */
    suspend fun appendOrUpdateByQuery(
        idOrQuery: String,
        newTitle: String = "",
        additionalContent: String = "",
        additionalChecklist: List<String> = emptyList(),
        additionalTags: List<String> = emptyList(),
        appendMode: Boolean = true
    ): Result<WorkspaceItem> = withContext(Dispatchers.IO) {
        runCatching {
            val current = getItems().toMutableList()
            val index = findMatchingIndex(current, idOrQuery, preferTasks = false)
            if (index == null) {
                // If no matching note exists, create a new note in the Second Brain Vault
                val created = WorkspaceItem(
                    type = WorkspaceItemType.NOTE,
                    title = newTitle.ifBlank { idOrQuery.take(60).ifBlank { "Not" } },
                    content = additionalContent,
                    checklist = additionalChecklist.map { ChecklistItem(text = it) },
                    tags = additionalTags
                ).sanitized()
                current.add(0, created)
                persistVaultAndCloud(current)
                return@runCatching created
            }

            val target = current[index]
            val mergedTitle = if (!appendMode && newTitle.isNotBlank()) newTitle.trim() else target.title
            val mergedContent = when {
                additionalContent.isBlank() -> target.content
                target.content.isBlank() || !appendMode -> additionalContent.trim()
                target.content.contains(additionalContent.trim(), ignoreCase = true) -> target.content
                else -> "${target.content.trim()}\n- ${additionalContent.trim()}"
            }
            val existingChecklistTexts = target.checklist.map { it.text.lowercase(Locale.forLanguageTag("tr-TR")) }.toSet()
            val newCheckItems = additionalChecklist
                .map { it.trim() }
                .filter { it.isNotBlank() && it.lowercase(Locale.forLanguageTag("tr-TR")) !in existingChecklistTexts }
                .map { ChecklistItem(text = it) }
            val mergedChecklist = if (appendMode) target.checklist + newCheckItems else {
                if (newCheckItems.isNotEmpty()) newCheckItems else target.checklist
            }
            val mergedTags = (target.tags + additionalTags).map { it.trim() }.filter { it.isNotBlank() }.distinct()

            val updated = target.copy(
                title = mergedTitle,
                content = mergedContent,
                checklist = mergedChecklist,
                tags = mergedTags,
                updatedAt = System.currentTimeMillis()
            ).sanitized()

            current[index] = updated
            persistVaultAndCloud(current)
            updated
        }
    }

    suspend fun toggleItemCompleted(itemId: String, completed: Boolean): Result<WorkspaceItem> = withContext(Dispatchers.IO) {
        runCatching {
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
            persistVaultAndCloud(current)
            updated
        }
    }

    suspend fun toggleChecklistSubItem(itemId: String, subItemId: String, done: Boolean): Result<WorkspaceItem> = withContext(Dispatchers.IO) {
        runCatching {
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
            persistVaultAndCloud(current)
            updated
        }
    }

    suspend fun completeByQuery(idOrQuery: String): Result<WorkspaceItem> = withContext(Dispatchers.IO) {
        runCatching {
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
            persistVaultAndCloud(current)
            updated
        }
    }

    suspend fun deleteByQuery(idOrQuery: String): Result<WorkspaceItem> = withContext(Dispatchers.IO) {
        runCatching {
            val current = getItems().toMutableList()
            val index = findMatchingIndex(current, idOrQuery, preferTasks = false)
                ?: throw IllegalArgumentException("'$idOrQuery' ile eşleşen silinecek kayıt bulunamadı.")

            val removed = current.removeAt(index)
            persistVaultAndCloud(current)
            removed
        }
    }

    suspend fun clearCompletedTasks(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val current = getItems()
            val remaining = current.filterNot { it.type == WorkspaceItemType.TASK && it.isCompleted }
            val removedCount = current.size - remaining.size
            if (removedCount > 0) {
                persistVaultAndCloud(remaining)
            }
            removedCount
        }
    }

    suspend fun clearAll(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            persistVaultAndCloud(emptyList(), allowEmptyOverwrite = true)
        }
    }

    private suspend fun persistVaultAndCloud(
        items: List<WorkspaceItem>,
        allowEmptyOverwrite: Boolean = false
    ) {
        val bounded = items
            .map { it.sanitized() }
            .sortedByDescending { it.createdAt }
            .take(MAX_WORKSPACE_ITEMS)

        // 1. Always save immediately to Local Second Brain Vault (JSON + Obsidian Markdown files)
        updateLocalVault(bounded)

        // 2. Sync to Firebase Firestore if session is available
        val uid = authManager.ensureSessionUid().getOrNull()
        if (!uid.isNullOrBlank() && (bounded.isNotEmpty() || allowEmptyOverwrite)) {
            runCatching {
                saveItemsToFirestore(uid, bounded)
            }.onFailure { e ->
                Log.w(TAG, "Cloud sync deferred (saved in local Second Brain Vault): ${e.message}")
            }
        }
    }

    private fun updateLocalVault(items: List<WorkspaceItem>) {
        val bounded = items.sortedByDescending { it.createdAt }.take(MAX_WORKSPACE_ITEMS)
        vaultStateFlow.value = bounded
        runCatching {
            val json = gson.toJson(bounded.map { it.toFirestoreMap() })
            prefs.edit().putString(KEY_VAULT_ITEMS_JSON, json).apply()
            syncObsidianMarkdownVault(bounded)
        }.onFailure { e ->
            Log.w(TAG, "Failed writing local vault: ${e.message}")
        }
    }

    private fun readLocalVaultItems(): List<WorkspaceItem> {
        val json = prefs.getString(KEY_VAULT_ITEMS_JSON, null)
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val rawList: List<Map<String, Any?>> = gson.fromJson(json, type) ?: emptyList()
            rawList.mapNotNull { map ->
                val id = (map["id"] as? String).orEmpty()
                WorkspaceItem.fromFirestoreMap(id, map)
            }.sortedByDescending { it.createdAt }.take(MAX_WORKSPACE_ITEMS)
        }.getOrDefault(emptyList())
    }

    /**
     * Mirrors all Second Brain items as Obsidian-compatible Markdown (`.md` + `SECOND_BRAIN_INDEX.md`)
     * inside `filesDir/second_brain_vault/` with YAML frontmatter, `#tags`, and `[[wikilinks]]`.
     */
    private fun syncObsidianMarkdownVault(items: List<WorkspaceItem>) {
        runCatching {
            val vaultDir = File(appContext.filesDir, "second_brain_vault")
            if (!vaultDir.exists()) vaultDir.mkdirs()

            val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val indexBuilder = StringBuilder()
            indexBuilder.appendLine("# TARS Second Brain Vault")
            indexBuilder.appendLine("Updated: ${df.format(Date())}")
            indexBuilder.appendLine("Total Notes: ${items.size}")
            indexBuilder.appendLine()

            for (item in items) {
                val dateStr = df.format(Date(item.createdAt))
                val tagsStr = if (item.tags.isNotEmpty()) {
                    item.tags.joinToString(" ") { "#${it.replace(" ", "_")}" }
                } else ""
                val statusBox = if (item.isCompleted) "[x]" else "[ ]"
                indexBuilder.appendLine("- $statusBox **[[${item.title}]]** (${item.type.name} · $dateStr) $tagsStr")
                if (item.content.isNotBlank()) {
                    indexBuilder.appendLine("  ${item.content}")
                }
                item.checklist.forEach { sub ->
                    val subBox = if (sub.isDone) "[x]" else "[ ]"
                    indexBuilder.appendLine("  - $subBox ${sub.text}")
                }
            }

            File(vaultDir, "SECOND_BRAIN_INDEX.md").writeText(indexBuilder.toString())
        }
    }

    private fun mergeVaultItems(
        local: List<WorkspaceItem>,
        remote: List<WorkspaceItem>
    ): List<WorkspaceItem> {
        if (local.isEmpty()) return remote
        if (remote.isEmpty()) return local

        val byId = LinkedHashMap<String, WorkspaceItem>()
        for (item in remote) {
            byId[item.id.lowercase(Locale.US)] = item
        }
        for (localItem in local) {
            val key = localItem.id.lowercase(Locale.US)
            val existing = byId[key]
            if (existing == null || localItem.updatedAt >= existing.updatedAt) {
                byId[key] = localItem
            }
        }
        return byId.values.sortedByDescending { it.createdAt }.take(MAX_WORKSPACE_ITEMS)
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
            it.title.lowercase(Locale.forLanguageTag("tr-TR")).contains(lower) ||
                lower.contains(it.title.lowercase(Locale.forLanguageTag("tr-TR")))
        }
        if (byTitleContains != -1) return byTitleContains

        val byContentContains = items.indexOfFirst {
            it.content.lowercase(Locale.forLanguageTag("tr-TR")).contains(lower)
        }
        if (byContentContains != -1) return byContentContains

        val byTagMatches = items.indexOfFirst { item ->
            item.tags.any { tag -> tag.lowercase(Locale.forLanguageTag("tr-TR")).contains(lower) }
        }
        if (byTagMatches != -1) return byTagMatches

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
        private const val PREFS_VAULT_NAME = "tars_second_brain_vault"
        private const val KEY_VAULT_ITEMS_JSON = "vault_items_json_v1"
        const val MAX_WORKSPACE_ITEMS = 300

        private val cacheLock = Any()
        @Volatile
        private var isCacheInitialized = false
        private val vaultStateFlow = MutableStateFlow<List<WorkspaceItem>>(emptyList())
        private val gson = Gson()
    }
}

