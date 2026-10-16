package com.tarslauncher.app.data.model

import java.util.UUID

/**
 * Data model for a note saved via TARS Agent or the Launcher Notes manager.
 */
data class NoteItem(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String = "",
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
