package com.tarslauncher.app.agent.data

import com.tarslauncher.app.agent.domain.engine.AgentBrain
import com.tarslauncher.app.agent.domain.model.AgentDecision
import com.tarslauncher.app.agent.domain.model.ChatMessage
import com.tarslauncher.app.agent.domain.parser.ActionParser

class GroqAgentBrain(
    private val remoteDataSource: GroqRemoteDataSource,
    private val modelName: String = "qwen/qwen3.8-27b"
) : AgentBrain {

    override suspend fun decideNextAction(
        systemPrompt: String,
        history: List<ChatMessage>
    ): Result<AgentDecision> {
        val rawResult = remoteDataSource.generateContent(systemPrompt, history)
        return rawResult.mapCatching { rawJson ->
            val parsedAction = ActionParser.parse(rawJson)
                ?: throw IllegalStateException("Groq çıktısı geçerli bir Agent eylemine dönüştürülemedi:\n$rawJson")
            AgentDecision(
                action = parsedAction,
                rawResponse = rawJson,
                provider = "GROQ",
                model = modelName
            )
        }
    }
}
