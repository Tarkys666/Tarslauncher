package com.tarslauncher.app.data.notes

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tarslauncher.app.data.model.WorkspaceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tamamen yerel not deposu. Veri yalnızca uygulamanın özel dizininde (`filesDir/notes.json`)
 * tutulur; ağ, hesap veya anahtar yoktur. Yazma işlemi atomiktir (tmp dosya + rename).
 */
class LocalNotesRepository private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val gson = Gson()
    private val mutex = Mutex()
    private val _items = MutableStateFlow(load())

    val workspaceItemsFlow: StateFlow<List<WorkspaceItem>> = _items.asStateFlow()

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

    private fun load(): List<WorkspaceItem> = try {
        if (!file.exists()) emptyList()
        else gson.fromJson<List<WorkspaceItem>>(
            file.readText(),
            object : TypeToken<List<WorkspaceItem>>() {}.type
        )?.map { it.sanitized() } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun persist(items: List<WorkspaceItem>) {
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(gson.toJson(items))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
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
