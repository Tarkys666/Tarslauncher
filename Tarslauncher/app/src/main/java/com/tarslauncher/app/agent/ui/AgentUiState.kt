package com.tarslauncher.app.agent.ui

import com.tarslauncher.app.agent.data.AgentProvider
import com.tarslauncher.app.agent.domain.model.AgentEvent
import com.tarslauncher.app.agent.domain.model.AgentPlan

data class AgentUiState(
    val isServiceActive: Boolean = false,
    val isAgentEnabled: Boolean = true,
    val isRunning: Boolean = false,
    val provider: AgentProvider = AgentProvider.GROQ,
    val groqApiKey: String = "",
    val groqModel: String = "qwen/qwen3.8-27b",
    val googleWebClientId: String = "",
    val isGoogleSignedIn: Boolean = false,
    val userEmail: String? = null,
    val workspaceCount: Int = 0,
    val taskInput: String = "",
    val currentPlan: AgentPlan? = null,
    val logs: List<String> = emptyList(),
    val pendingQuestion: AgentEvent.QuestionToUser? = null,
    val finalMessage: String? = null,
    val isFailure: Boolean = false
) {
    val activeApiKey: String
        get() = groqApiKey
}
