package com.tarslauncher.app.agent.domain.model

import com.tarslauncher.app.data.model.WorkspaceItemType
import com.tarslauncher.app.data.model.WorkspacePriority

/**
 * Represents a Notion-style Workspace action determined by the TARS LLM Brain.
 */
sealed interface AgentAction {
    val thought: String
    val stepIndex: Int
        get() = -1

    fun toOperationalSignature(): String = when (this) {
        is CreateWorkspaceItem -> "Create(${itemType.name}, ${title.take(30)})"
        is UpdateWorkspaceItem -> "Update($targetQuery, ${title.take(24)})"
        is CompleteWorkspaceItem -> "Complete($targetQuery)"
        is DeleteWorkspaceItem -> "Delete($targetQuery)"
        is ClearCompletedTasks -> "ClearCompletedTasks"
        is ClearAllWorkspace -> "ClearAllWorkspace"
        is QueryWorkspace -> "Query($filterType)"
        is AskUser -> "AskUser($question)"
        is Done -> "Done"
        is Unknown -> "Unknown($rawAction)"
    }

    data class CreateWorkspaceItem(
        override val thought: String,
        val itemType: WorkspaceItemType,
        val title: String,
        val content: String = "",
        val priority: WorkspacePriority = WorkspacePriority.MEDIUM,
        val checklist: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val alarmHour: Int? = null,
        val alarmMinute: Int? = null,
        val durationSeconds: Long? = null,
        val isTomorrow: Boolean = false,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class UpdateWorkspaceItem(
        override val thought: String,
        val targetQuery: String,
        val title: String = "",
        val content: String = "",
        val checklist: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val appendMode: Boolean = true,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class CompleteWorkspaceItem(
        override val thought: String,
        val targetQuery: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class DeleteWorkspaceItem(
        override val thought: String,
        val targetQuery: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class ClearCompletedTasks(
        override val thought: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class ClearAllWorkspace(
        override val thought: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class QueryWorkspace(
        override val thought: String,
        val filterType: String = "ALL",
        val summaryResponse: String = "",
        override val stepIndex: Int = -1
    ) : AgentAction

    data class AskUser(
        override val thought: String,
        val question: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class Done(
        override val thought: String,
        val summary: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class Unknown(
        override val thought: String,
        val rawAction: String,
        override val stepIndex: Int = -1
    ) : AgentAction
}

