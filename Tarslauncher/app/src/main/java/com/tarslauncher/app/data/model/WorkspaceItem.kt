package com.tarslauncher.app.data.model

import java.util.UUID

enum class WorkspaceItemType(val labelTr: String, val badgeText: String) {
    TASK("Görev", "GÖREV"),
    NOTE("Not", "NOT"),
    IDEA("Fikir", "FİKİR");

    companion object {
        fun fromString(raw: String?): WorkspaceItemType {
            return when (raw?.trim()?.lowercase()) {
                "task", "todo", "to-do", "to_do", "görev", "gorev", "yapılacak", "hatırlatıcı" -> TASK
                "idea", "fikir", "proje", "brainstorm" -> IDEA
                else -> NOTE
            }
        }
    }
}

enum class WorkspacePriority(val labelTr: String) {
    LOW("Düşük"),
    MEDIUM("Normal"),
    HIGH("Yüksek");

    companion object {
        fun fromString(raw: String?): WorkspacePriority {
            return when (raw?.trim()?.lowercase()) {
                "high", "yüksek", "yuksek", "acil", "urgent", "kritik" -> HIGH
                "low", "düşük", "dusuk", "ertelenebilir" -> LOW
                else -> MEDIUM
            }
        }
    }
}

data class ChecklistItem(
    val id: String = UUID.randomUUID().toString().take(8),
    val text: String,
    val isDone: Boolean = false
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id.take(MAX_ID_LENGTH),
        "text" to text.trim().take(MAX_CHECKLIST_TEXT_LENGTH),
        "isDone" to isDone
    )

    companion object {
        const val MAX_ID_LENGTH = 36
        const val MAX_CHECKLIST_TEXT_LENGTH = 240

        fun fromMap(map: Map<*, *>): ChecklistItem? {
            val text = (map["text"] as? String)?.trim()?.take(MAX_CHECKLIST_TEXT_LENGTH).orEmpty()
            if (text.isBlank()) return null
            val id = (map["id"] as? String)?.trim()?.take(MAX_ID_LENGTH)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().take(8)
            val isDone = map["isDone"] as? Boolean ?: false
            return ChecklistItem(id = id, text = text, isDone = isDone)
        }
    }
}

data class WorkspaceItem(
    val id: String = UUID.randomUUID().toString().take(8),
    val type: WorkspaceItemType = WorkspaceItemType.NOTE,
    val title: String,
    val content: String = "",
    val checklist: List<ChecklistItem> = emptyList(),
    val priority: WorkspacePriority = WorkspacePriority.MEDIUM,
    val isCompleted: Boolean = false,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun sanitized(): WorkspaceItem = copy(
        id = id.trim().take(ChecklistItem.MAX_ID_LENGTH).ifBlank { UUID.randomUUID().toString().take(8) },
        title = title.trim().take(MAX_TITLE_LENGTH).ifBlank { content.trim().take(32).ifBlank { type.labelTr } },
        content = content.trim().take(MAX_CONTENT_LENGTH),
        checklist = checklist.take(MAX_CHECKLIST_ITEMS).map {
            it.copy(
                id = it.id.trim().take(ChecklistItem.MAX_ID_LENGTH).ifBlank { UUID.randomUUID().toString().take(8) },
                text = it.text.trim().take(ChecklistItem.MAX_CHECKLIST_TEXT_LENGTH)
            )
        }.filter { it.text.isNotBlank() },
        tags = tags.mapNotNull { it.trim().take(MAX_TAG_LENGTH).takeIf { s -> s.isNotBlank() } }.distinct().take(MAX_TAGS)
    )

    fun toFirestoreMap(): Map<String, Any> {
        val clean = sanitized()
        return hashMapOf(
            "id" to clean.id,
            "type" to clean.type.name,
            "title" to clean.title,
            "content" to clean.content,
            "checklist" to clean.checklist.map { it.toMap() },
            "priority" to clean.priority.name,
            "isCompleted" to clean.isCompleted,
            "tags" to clean.tags,
            "createdAt" to clean.createdAt,
            "updatedAt" to clean.updatedAt
        )
    }

    companion object {
        const val MAX_TITLE_LENGTH = 160
        const val MAX_CONTENT_LENGTH = 4_000
        const val MAX_CHECKLIST_ITEMS = 40
        const val MAX_TAGS = 10
        const val MAX_TAG_LENGTH = 32

        fun fromFirestoreMap(docId: String, data: Map<String, Any?>): WorkspaceItem? {
            val title = (data["title"] as? String)?.trim()?.take(MAX_TITLE_LENGTH).orEmpty()
            val content = (data["content"] as? String)?.trim()?.take(MAX_CONTENT_LENGTH).orEmpty()
            if (title.isBlank() && content.isBlank()) return null

            val type = WorkspaceItemType.fromString(data["type"] as? String)
            val priority = WorkspacePriority.fromString(data["priority"] as? String)
            val isCompleted = data["isCompleted"] as? Boolean ?: false

            val rawChecklist = data["checklist"] as? List<*>
            val checklist = rawChecklist?.mapNotNull { item ->
                when (item) {
                    is Map<*, *> -> ChecklistItem.fromMap(item)
                    is String -> item.trim().take(ChecklistItem.MAX_CHECKLIST_TEXT_LENGTH)
                        .takeIf { it.isNotBlank() }
                        ?.let { ChecklistItem(text = it) }
                    else -> null
                }
            }?.take(MAX_CHECKLIST_ITEMS) ?: emptyList()

            val rawTags = data["tags"] as? List<*>
            val tags = rawTags?.mapNotNull {
                (it as? String)?.trim()?.take(MAX_TAG_LENGTH)?.takeIf { s -> s.isNotBlank() }
            }?.distinct()?.take(MAX_TAGS) ?: emptyList()

            val createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: createdAt

            return WorkspaceItem(
                id = docId.ifBlank { (data["id"] as? String).orEmpty() }.take(ChecklistItem.MAX_ID_LENGTH),
                type = type,
                title = title.ifBlank { content.take(32) },
                content = content,
                checklist = checklist,
                priority = priority,
                isCompleted = isCompleted,
                tags = tags,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        }
    }
}
