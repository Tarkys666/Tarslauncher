package com.tarslauncher.app.agent.domain.model

import kotlinx.coroutines.CompletableDeferred

/**
 * Lifecycle and execution events emitted by the Agent loop to update the UI and handle confirmations.
 */
sealed interface AgentEvent {
    data class StatusLog(val message: String) : AgentEvent
    
    data class AwaitingConfirmation(
        val prompt: String,
        val userDecision: CompletableDeferred<Boolean>
    ) : AgentEvent

    data class QuestionToUser(val question: String) : AgentEvent
    
    data class Success(val summary: String) : AgentEvent
    
    data class Failure(val errorReason: String) : AgentEvent
}
