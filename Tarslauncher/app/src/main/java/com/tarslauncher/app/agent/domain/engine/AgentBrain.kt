package com.tarslauncher.app.agent.domain.engine

import com.tarslauncher.app.agent.domain.model.AgentAction
import com.tarslauncher.app.agent.domain.model.ChatMessage

/**
 * Abstraction for the reasoning engine behind the Agent.
 * Allows effortless substitution between Gemini Cloud API, on-device models (LiteRT, Gemma), or test fakes.
 */
interface AgentBrain {
    suspend fun decideNextAction(
        systemPrompt: String,
        history: List<ChatMessage>
    ): Result<AgentAction>
}
