package com.tarslauncher.app.data.notes

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tarslauncher.app.data.datastore.LauncherDataStore
import com.tarslauncher.app.data.model.NoteItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import java.util.Locale

/**
 * Manages notes created by the user or TARS Agent.
 */
class NoteManager(context: Context) {
    private val dataStore = LauncherDataStore(context.applicationContext)
    private val gson = Gson()
    private val listType = object : TypeToken<List<NoteItem>>() {}.type

    val notesFlow: Flow<List<NoteItem>> = dataStore.notesJsonFlow
        .map { json -> parseNotes(json) }
        .flowOn(Dispatchers.IO)

    suspend fun getNotes(): List<NoteItem> {
        val json = dataStore.getNotesJson()
        return parseNotes(json)
    }

    suspend fun addNote(content: String, title: String = ""): NoteItem {
        val current = getNotes().toMutableList()
        val finalTitle = title.trim().ifBlank {
            content.lineSequence().firstOrNull()?.trim()?.take(30) ?: "Not"
        }
        val note = NoteItem(
            title = finalTitle,
            content = content.trim()
        )
        current.add(0, note)
        dataStore.saveNotesJson(gson.toJson(current))
        return note
    }

    suspend fun deleteNote(idOrQuery: String): Boolean {
        val trimmed = idOrQuery.trim()
        if (trimmed.isBlank()) return false

        val current = getNotes().toMutableList()
        if (current.isEmpty()) return false

        val query = trimmed.lowercase(Locale.ROOT)
        val removed = if (query == "son" || query == "last" || query == "en son") {
            current.removeAt(0)
            true
        } else {
            val byId = current.indexOfFirst { it.id.equals(trimmed, ignoreCase = true) }
            if (byId != -1) {
                current.removeAt(byId)
                true
            } else {
                val byExactTitle = current.indexOfFirst { it.title.equals(trimmed, ignoreCase = true) }
                if (byExactTitle != -1) {
                    current.removeAt(byExactTitle)
                    true
                } else {
                    val byTitle = current.indexOfFirst { it.title.lowercase(Locale.ROOT).contains(query) }
                    if (byTitle != -1) {
                        current.removeAt(byTitle)
                        true
                    } else false
                }
            }
        }
        if (removed) {
            dataStore.saveNotesJson(gson.toJson(current))
        }
        return removed
    }

    suspend fun clearNotes() {
        dataStore.saveNotesJson(gson.toJson(emptyList<NoteItem>()))
    }

    private fun parseNotes(json: String?): List<NoteItem> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<NoteItem>>(json, listType)
        }.getOrDefault(emptyList())
    }
}
