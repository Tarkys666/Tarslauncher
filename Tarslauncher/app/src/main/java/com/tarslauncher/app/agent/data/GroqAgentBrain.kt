package com.tarslauncher.app.agent.data

import com.tarslauncher.app.agent.domain.engine.AgentBrain
import com.tarslauncher.app.agent.domain.model.AgentDecision
import com.tarslauncher.app.agent.domain.model.ChatMessage
import com.tarslauncher.app.agent.domain.parser.ActionParser

class GroqAgentBrain(
    private val remoteDataSource: GroqRemoteDataSource,
    private val modelName: String = GroqRemoteDataSource.DEFAULT_GROQ_MODEL
) : AgentBrain {

    override suspend fun generatePlan(
        systemPrompt: String,
        history: List<ChatMessage>
    ): Result<List<String>> {
        val rawResult = remoteDataSource.generateContent(systemPrompt, history)
        return rawResult.mapCatching { rawJson ->
            ActionParser.parsePlan(rawJson)
                ?: throw IllegalStateException("Groq plan çıktısı ayrıştırılamadı:\n$rawJson")
        }
    }

    override suspend fun decideNextAction(
        systemPrompt: String,
        history: List<ChatMessage>
    ): Result<AgentDecision> {
        val rawResult = remoteDataSource.generateContent(systemPrompt, history)
        return rawResult.mapCatching { rawJson ->
            val payload = ActionParser.parsePayload(rawJson)
                ?: throw IllegalStateException("Groq çıktısı geçerli bir Agent eylemine dönüştürülemedi:\n$rawJson")
            AgentDecision(
                action = payload.action,
                rawResponse = rawJson,
                provider = "GROQ",
                model = remoteDataSource.lastUsedModel.ifBlank { modelName },
                reflection = payload.reflection,
                replan = payload.replan
            )
        }
    }
}
