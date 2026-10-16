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

    data class QuestionToUser(
        val question: String,
        val userAnswer: CompletableDeferred<String?> = CompletableDeferred()
    ) : AgentEvent
    
    data class Success(val summary: String) : AgentEvent
    
    data class Failure(val errorReason: String) : AgentEvent

    data class PlanCreated(val plan: AgentPlan) : AgentEvent

    data class PlanStepUpdated(val plan: AgentPlan) : AgentEvent
}
