package com.tarslauncher.app.agent.domain.model

/**
 * Encapsulates the model's decision along with full telemetry for dataset generation and auditing.
 */
data class AgentDecision(
    val action: AgentAction,
    val rawResponse: String,
    val provider: String,
    val model: String,
    val reflection: String = "",
    val replan: List<String>? = null
)
