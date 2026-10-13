package com.tarslauncher.app.agent.runner

import android.content.Context
import com.tarslauncher.app.agent.data.logging.AgentDatasetCollector
import com.tarslauncher.app.agent.domain.engine.AgentBrain
import com.tarslauncher.app.agent.domain.model.AgentAction
import com.tarslauncher.app.agent.domain.model.AgentEvent
import com.tarslauncher.app.agent.domain.model.ChatMessage
import com.tarslauncher.app.agent.domain.prompt.AgentPromptProvider
import com.tarslauncher.app.agent.domain.safety.SafetyGate
import com.tarslauncher.app.agent.service.TarsAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.util.UUID

class AgentLoopRunner(
    private val context: Context,
    private val agentBrain: AgentBrain,
    private val safetyGate: SafetyGate = SafetyGate(),
    private val datasetCollector: AgentDatasetCollector = AgentDatasetCollector(context),
    private val maxSteps: Int = 25
) {

    private val actionExecutor = ActionExecutor(context)

    fun runAgent(userGoal: String): Flow<AgentEvent> = flow {
        val service = TarsAccessibilityService.getInstance()
        if (service == null) {
            emit(AgentEvent.Failure("TARS Erişilebilirlik Servisi aktif değil. Lütfen ayarlardan servisi açın."))
            return@flow
        }

        val sessionId = "tars_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        emit(AgentEvent.StatusLog("Hedef başlatılıyor: \"$userGoal\""))

        val history = mutableListOf<ChatMessage>()
        history.add(ChatMessage(ChatMessage.Role.USER, "GÖREV: $userGoal"))

        var previousActionSignature = ""
        var identicalActionRepeatCount = 0

        for (step in 1..maxSteps) {
            if (!currentCoroutineContext().isActive) {
                return@flow
            }

            // 1. Ekranı dökümle (Untrusted Screen State ile Prompt Injection izolasyonu)
            val screenDump = service.captureScreenDump()
            val sanitizedScreenContent = "<untrusted_screen_state>\n${screenDump.formattedContent}\n</untrusted_screen_state>"
            history.add(ChatMessage(ChatMessage.Role.USER, "EKRAN:\n$sanitizedScreenContent"))

            // Bağlam boyutunu sınırla: son 10 mesajı tut
            val boundedHistory = if (history.size > 10) {
                listOf(history.first()) + history.takeLast(9)
            } else {
                history
            }

            emit(AgentEvent.StatusLog("Adım $step/$maxSteps: Ekran inceleniyor ve karar alınıyor..."))

            // 2. Karar al
            val decisionResult = agentBrain.decideNextAction(
                systemPrompt = AgentPromptProvider.SYSTEM_PROMPT,
                history = boundedHistory
            )

            if (decisionResult.isFailure) {
                val errorMsg = decisionResult.exceptionOrNull()?.message ?: "Bilinmeyen model hatası"
                datasetCollector.recordStep(
                    sessionId = sessionId,
                    step = step,
                    goal = userGoal,
                    systemPrompt = AgentPromptProvider.SYSTEM_PROMPT,
                    screenDump = screenDump.formattedContent,
                    rawModelResponse = "HATA: $errorMsg",
                    parsedAction = null,
                    executionResult = "Model Hatası: $errorMsg",
                    provider = "UNKNOWN",
                    model = "UNKNOWN",
                    status = "FAILED"
                )
                emit(AgentEvent.Failure("Yapay zeka yanıt veremedi: $errorMsg"))
                return@flow
            }

            val decision = decisionResult.getOrThrow()
            val action = decision.action
            emit(AgentEvent.StatusLog("Adım $step: ${action.thought}"))

            // 3. Sonlandırma veya Kullanıcıya soru kontrolleri
            when (action) {
                is AgentAction.Done -> {
                    datasetCollector.recordStep(
                        sessionId = sessionId,
                        step = step,
                        goal = userGoal,
                        systemPrompt = AgentPromptProvider.SYSTEM_PROMPT,
                        screenDump = screenDump.formattedContent,
                        rawModelResponse = decision.rawResponse,
                        parsedAction = action,
                        executionResult = action.summary,
                        provider = decision.provider,
                        model = decision.model,
                        status = "COMPLETED"
                    )
                    emit(AgentEvent.Success(action.summary))
                    return@flow
                }
                is AgentAction.AskUser -> {
                    datasetCollector.recordStep(
                        sessionId = sessionId,
                        step = step,
                        goal = userGoal,
                        systemPrompt = AgentPromptProvider.SYSTEM_PROMPT,
                        screenDump = screenDump.formattedContent,
                        rawModelResponse = decision.rawResponse,
                        parsedAction = action,
                        executionResult = action.question,
                        provider = decision.provider,
                        model = decision.model,
                        status = "ASK_USER"
                    )
                    emit(AgentEvent.QuestionToUser(action.question))
                    return@flow
                }
                else -> Unit
            }

            // 4. Döngü kırıcı (Circuit Breaker) - Düşünceden bağımsız operasyonel imza ile kontrol
            val currentSignature = action.toOperationalSignature()
            if (currentSignature == previousActionSignature) {
                identicalActionRepeatCount++
            } else {
                identicalActionRepeatCount = 0
            }
            previousActionSignature = currentSignature

            if (identicalActionRepeatCount >= 3) {
                datasetCollector.recordStep(
                    sessionId = sessionId,
                    step = step,
                    goal = userGoal,
                    systemPrompt = AgentPromptProvider.SYSTEM_PROMPT,
                    screenDump = screenDump.formattedContent,
                    rawModelResponse = decision.rawResponse,
                    parsedAction = action,
                    executionResult = "Ajan aynı eylemde takıldı ($currentSignature)",
                    provider = decision.provider,
                    model = decision.model,
                    status = "FAILED_STUCK"
                )
                emit(AgentEvent.Failure("Ajan aynı eylemde takıldı ($currentSignature). Güvenlik sebebiyle durduruldu."))
                return@flow
            }

            // 5. Güvenlik Kapısı kontrolü (Hassas işlemler için kullanıcı onayı)
            val (needsConfirm, promptReason) = safetyGate.evaluate(action, screenDump.formattedContent)
            if (needsConfirm) {
                val confirmationDeferred = CompletableDeferred<Boolean>()
                emit(AgentEvent.AwaitingConfirmation(promptReason, confirmationDeferred))

                val isApproved = confirmationDeferred.await()
                if (!isApproved) {
                    datasetCollector.recordStep(
                        sessionId = sessionId,
                        step = step,
                        goal = userGoal,
                        systemPrompt = AgentPromptProvider.SYSTEM_PROMPT,
                        screenDump = screenDump.formattedContent,
                        rawModelResponse = decision.rawResponse,
                        parsedAction = action,
                        executionResult = "Hassas işlem kullanıcı tarafından reddedildi",
                        provider = decision.provider,
                        model = decision.model,
                        status = "REJECTED_BY_USER"
                    )
                    emit(AgentEvent.Failure("Hassas işlem kullanıcı tarafından reddedildi."))
                    return@flow
                }
            }

            // 6. Eylemi çalıştır
            val executionSummary = actionExecutor.execute(action)
            emit(AgentEvent.StatusLog("Sonuç: $executionSummary"))

            // Model hafızasına eylem ve sonucunu ekle
            history.add(ChatMessage(ChatMessage.Role.MODEL, "DÜŞÜNCE: ${action.thought}\nEYLEM: ${action.toOperationalSignature()}"))
            history.add(ChatMessage(ChatMessage.Role.USER, "SONUÇ: $executionSummary"))

            // Trajectory Dataset'ine adımı kaydet
            datasetCollector.recordStep(
                sessionId = sessionId,
                step = step,
                goal = userGoal,
                systemPrompt = AgentPromptProvider.SYSTEM_PROMPT,
                screenDump = screenDump.formattedContent,
                rawModelResponse = decision.rawResponse,
                parsedAction = action,
                executionResult = executionSummary,
                provider = decision.provider,
                model = decision.model,
                status = "IN_PROGRESS"
            )

            // Sayfanın yüklenmesi ve animasyonların oturması için bekleme payı
            delay(900L)
        }

        emit(AgentEvent.Failure("Maksimum adım sınırına ($maxSteps) ulaşıldı."))
    }.flowOn(Dispatchers.Default)
}
