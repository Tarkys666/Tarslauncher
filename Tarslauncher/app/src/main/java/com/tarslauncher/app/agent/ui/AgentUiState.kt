package com.tarslauncher.app.agent.ui

import com.tarslauncher.app.agent.data.AgentProvider
import com.tarslauncher.app.agent.domain.model.AgentEvent
import com.tarslauncher.app.agent.domain.model.AgentPlan

data class AgentUiState(
    val isServiceActive: Boolean = false,
    val isRunning: Boolean = false,
    val provider: AgentProvider = AgentProvider.GEMINI,
    val geminiApiKey: String = "",
    val groqApiKey: String = "",
    val groqModel: String = "qwen/qwen3.8-27b",
    val supabaseUrl: String = "",
    val supabaseAnonKey: String = "",
    val taskInput: String = "",
    val currentPlan: AgentPlan? = null,
    val logs: List<String> = emptyList(),
    val datasetCount: Int = 0,
    val pendingConfirmation: AgentEvent.AwaitingConfirmation? = null,
    val pendingQuestion: String? = null,
    val finalMessage: String? = null,
    val isFailure: Boolean = false
) {
    val activeApiKey: String
        get() = when (provider) {
            AgentProvider.GEMINI -> geminiApiKey
            AgentProvider.GROQ -> groqApiKey
        }

    val apiKey: String
        get() = activeApiKey
}
