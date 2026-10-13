package com.tarslauncher.app.agent.data

import com.tarslauncher.app.agent.domain.engine.AgentBrain
import com.tarslauncher.app.agent.domain.model.AgentDecision
import com.tarslauncher.app.agent.domain.model.ChatMessage
import com.tarslauncher.app.agent.domain.parser.ActionParser

class GeminiAgentBrain(
    private val remoteDataSource: GeminiRemoteDataSource
) : AgentBrain {

    override suspend fun decideNextAction(
        systemPrompt: String,
        history: List<ChatMessage>
    ): Result<AgentDecision> {
        val rawResult = remoteDataSource.generateContent(systemPrompt, history)
        return rawResult.mapCatching { rawJson ->
            val parsedAction = ActionParser.parse(rawJson)
                ?: throw IllegalStateException("Model çıktısı geçerli bir Agent eylemine dönüştürülemedi:\n$rawJson")
            AgentDecision(
                action = parsedAction,
                rawResponse = rawJson,
                provider = "GEMINI",
                model = "gemini-1.5-flash"
            )
        }
    }
}
