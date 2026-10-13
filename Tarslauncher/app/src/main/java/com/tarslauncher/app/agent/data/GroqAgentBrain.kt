package com.tarslauncher.app.agent.data

import com.tarslauncher.app.agent.domain.engine.AgentBrain
import com.tarslauncher.app.agent.domain.model.AgentAction
import com.tarslauncher.app.agent.domain.model.ChatMessage
import com.tarslauncher.app.agent.domain.parser.ActionParser

class GroqAgentBrain(
    private val remoteDataSource: GroqRemoteDataSource
) : AgentBrain {

    override suspend fun decideNextAction(
        systemPrompt: String,
        history: List<ChatMessage>
    ): Result<AgentAction> {
        val rawResult = remoteDataSource.generateContent(systemPrompt, history)
        return rawResult.mapCatching { rawJson ->
            ActionParser.parse(rawJson)
                ?: throw IllegalStateException("Groq çıktısı geçerli bir Agent eylemine dönüştürülemedi:\n$rawJson")
        }
    }
}
