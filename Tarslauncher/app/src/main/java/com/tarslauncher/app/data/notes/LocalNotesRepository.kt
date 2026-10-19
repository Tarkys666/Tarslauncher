package com.tarslauncher.app.data.notes

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tarslauncher.app.data.model.WorkspaceItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tamamen yerel not deposu. Veri yalnızca uygulamanın özel dizininde (`filesDir/notes.json`)
 * tutulur; ağ, hesap veya anahtar yoktur. Yazma işlemi atomiktir (tmp dosya + bak yedek + rename).
 */
class LocalNotesRepository private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val bakFile = File(context.applicationContext.filesDir, "$FILE_NAME.bak")
    private val gson = Gson()
    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<WorkspaceItem>>(emptyList())

    val workspaceItemsFlow: StateFlow<List<WorkspaceItem>> = _items.asStateFlow()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            val initial = load()
            _items.update { current ->
                if (current.isEmpty()) initial else current
            }
        }
    }

    suspend fun addItem(item: WorkspaceItem) = update { listOf(item.sanitized()) + it }

    suspend fun toggleItemCompleted(id: String, completed: Boolean) = update { list ->
        list.map { if (it.id == id) it.copy(isCompleted = completed, updatedAt = System.currentTimeMillis()) else it }
    }

    suspend fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    /** Yedekten geri yükleme: mevcut notları verilen liste ile değiştirir. */
    suspend fun replaceAll(items: List<WorkspaceItem>) = update { items.map { it.sanitized() }.take(MAX_ITEMS) }

    fun snapshot(): List<WorkspaceItem> = _items.value

    private suspend fun update(transform: (List<WorkspaceItem>) -> List<WorkspaceItem>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = transform(_items.value).take(MAX_ITEMS)
                persist(next)
                _items.value = next
            }
        }
    }

    private fun load(): List<WorkspaceItem> {
        // 1. Try reading from main notes file
        val fromMain = tryParseFile(file)
        if (fromMain != null) return fromMain

        // 2. Fallback to .bak file if main file is corrupted or unreadable
        if (bakFile.exists()) {
            val fromBak = tryParseFile(bakFile)
            if (fromBak != null) {
                Log.w(TAG, "Restored notes from .bak backup file")
                try {
                    bakFile.copyTo(file, overwrite = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore backup file to main", e)
                }
                return fromBak
            }
        }

        return emptyList()
    }

    private fun tryParseFile(targetFile: File): List<WorkspaceItem>? {
        if (!targetFile.exists() || targetFile.length() == 0L) return null
        return try {
            val json = targetFile.readText()
            gson.fromJson<List<WorkspaceItem>>(
                json,
                object : TypeToken<List<WorkspaceItem>>() {}.type
            )?.map { it.sanitized() }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing notes from ${targetFile.name}", e)
            null
        }
    }

    private fun persist(items: List<WorkspaceItem>) {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        try {
            val json = gson.toJson(items)
            tmp.writeText(json)

            // Create backup of current valid file if present
            if (file.exists()) {
                file.copyTo(bakFile, overwrite = true)
            }

            // Atomic rename to commit new file
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting notes atomically", e)
        }
    }

    companion object {
        private const val TAG = "LocalNotesRepository"
        private const val FILE_NAME = "notes.json"
        private const val MAX_ITEMS = 2_000

        @Volatile
        private var instance: LocalNotesRepository? = null

        fun get(context: Context): LocalNotesRepository =
            instance ?: synchronized(this) {
                instance ?: LocalNotesRepository(context).also { instance = it }
            }
    }
}
