package com.tarslauncher.app.agent.data

import com.tarslauncher.app.agent.domain.engine.AgentBrain
import com.tarslauncher.app.agent.domain.model.AgentDecision
import com.tarslauncher.app.agent.domain.model.ChatMessage
import com.tarslauncher.app.agent.domain.parser.ActionParser

class GeminiAgentBrain(
    private val remoteDataSource: GeminiRemoteDataSource
) : AgentBrain {

    override suspend fun generatePlan(
        systemPrompt: String,
        history: List<ChatMessage>
    ): Result<List<String>> {
        val rawResult = remoteDataSource.generateContent(systemPrompt, history)
        return rawResult.mapCatching { rawJson ->
            ActionParser.parsePlan(rawJson)
                ?: throw IllegalStateException("Gemini plan çıktısı ayrıştırılamadı:\n$rawJson")
        }
    }

    override suspend fun decideNextAction(
        systemPrompt: String,
        history: List<ChatMessage>
    ): Result<AgentDecision> {
        val rawResult = remoteDataSource.generateContent(systemPrompt, history)
        return rawResult.mapCatching { rawJson ->
            val payload = ActionParser.parsePayload(rawJson)
                ?: throw IllegalStateException("Model çıktısı geçerli bir Agent eylemine dönüştürülemedi:\n$rawJson")
            AgentDecision(
                action = payload.action,
                rawResponse = rawJson,
                provider = "GEMINI",
                model = remoteDataSource.lastUsedModel,
                reflection = payload.reflection,
                replan = payload.replan
            )
        }
    }
}
