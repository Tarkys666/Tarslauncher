package com.tarslauncher.app.agent.domain.engine

import com.tarslauncher.app.agent.domain.model.AgentDecision
import com.tarslauncher.app.agent.domain.model.ChatMessage

/**
 * Abstraction for the reasoning engine behind the Agent.
 * Allows effortless substitution between Gemini Cloud API, Groq LPU, on-device models, or test fakes.
 */
interface AgentBrain {
    suspend fun generatePlan(
        systemPrompt: String,
        history: List<ChatMessage>
    ): Result<List<String>>

    suspend fun decideNextAction(
        systemPrompt: String,
        history: List<ChatMessage>
    ): Result<AgentDecision>
}
