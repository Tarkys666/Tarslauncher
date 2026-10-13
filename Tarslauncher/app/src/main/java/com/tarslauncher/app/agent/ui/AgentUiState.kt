package com.tarslauncher.app.agent.ui

import com.tarslauncher.app.agent.domain.model.AgentEvent

data class AgentUiState(
    val isServiceActive: Boolean = false,
    val isRunning: Boolean = false,
    val apiKey: String = "",
    val taskInput: String = "",
    val logs: List<String> = emptyList(),
    val pendingConfirmation: AgentEvent.AwaitingConfirmation? = null,
    val pendingQuestion: String? = null,
    val finalMessage: String? = null,
    val isFailure: Boolean = false
)
