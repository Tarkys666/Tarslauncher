package com.tarslauncher.app.agent.runner

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tarslauncher.app.agent.data.firebase.FirebaseAgentLogger
import com.tarslauncher.app.agent.data.logging.AgentDatasetCollector
import com.tarslauncher.app.agent.data.supabase.SupabaseAgentLogger
import com.tarslauncher.app.agent.domain.engine.AgentBrain
import com.tarslauncher.app.agent.domain.memory.AgentMemoryManager
import com.tarslauncher.app.agent.domain.model.AgentAction
import com.tarslauncher.app.agent.domain.model.AgentEvent
import com.tarslauncher.app.agent.domain.model.AgentPlan
import com.tarslauncher.app.agent.domain.model.ChatMessage
import com.tarslauncher.app.agent.domain.model.PlanStep
import com.tarslauncher.app.agent.domain.parser.ActionParser
import com.tarslauncher.app.agent.domain.prompt.AgentPromptProvider
import com.tarslauncher.app.agent.domain.safety.SafetyGate
import com.tarslauncher.app.agent.domain.skill.AgentSkillManager
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
    private val memoryManager = AgentMemoryManager(context)
    private val skillManager = AgentSkillManager(context)
    private val firebaseLogger = FirebaseAgentLogger(context)
    private val supabaseLogger = SupabaseAgentLogger(context)

    fun runAgent(userGoal: String): Flow<AgentEvent> = flow {
        val service = TarsAccessibilityService.getInstance()
        if (service == null) {
            emit(AgentEvent.Failure("TARS Erişilebilirlik Servisi aktif değil. Lütfen ayarlardan servisi açın."))
            return@flow
        }

        val sessionId = "tars_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        emit(AgentEvent.StatusLog("Hedef başlatılıyor: \"$userGoal\""))

        // 1. Önceki oturum hafızasını (Session Continuity) sorgula
        val lastSession = memoryManager.getLastSessionMemory()
        val memoryContext = lastSession?.let {
            "Son Görev: \"${it.lastGoal}\", Son Durum: \"${it.lastStatus}\", Özet: \"${it.lastSummary}\", Paket: \"${it.lastAppPackage}\""
        }
        if (memoryContext != null) {
            emit(AgentEvent.StatusLog("Önceki görev bağlamı hatırlandı."))
        }

        // 2. İlgili Markdown Beceri (Skill) tarifini ara
        val relevantSkill = skillManager.findRelevantSkill(userGoal)
        if (relevantSkill != null) {
            emit(AgentEvent.StatusLog("Öğrenilmiş ilgili beceri (skill) yüklendi."))
        }

        // 3. Mini-Planlama Aşaması (Plan-and-Solve)
        emit(AgentEvent.StatusLog("Hedef analiz ediliyor ve mini-plan oluşturuluyor..."))
        val planPrompt = listOf(ChatMessage(ChatMessage.Role.USER, "GÖREV: $userGoal"))
        val planResult = agentBrain.decideNextAction(
            systemPrompt = AgentPromptProvider.PLAN_SYSTEM_PROMPT,
            history = planPrompt
        )

        var currentPlan: AgentPlan? = null
        if (planResult.isSuccess) {
            val rawPlan = planResult.getOrThrow().rawResponse
            val stepsList = ActionParser.parsePlan(rawPlan)
            if (!stepsList.isNullOrEmpty()) {
                val planSteps = stepsList.mapIndexed { idx, title ->
                    PlanStep(index = idx + 1, title = title, isCompleted = false, isInProgress = idx == 0)
                }
                currentPlan = AgentPlan(goal = userGoal, steps = planSteps)
                emit(AgentEvent.PlanCreated(currentPlan))
                emit(AgentEvent.StatusLog("Mini-Plan hazırlandı (${planSteps.size} aşama)."))
            }
        }

        if (currentPlan == null) {
            val fallbackSteps = listOf(PlanStep(index = 1, title = userGoal, isCompleted = false, isInProgress = true))
            currentPlan = AgentPlan(goal = userGoal, steps = fallbackSteps)
            emit(AgentEvent.PlanCreated(currentPlan))
        }

        // Supabase Oturum Kaydı Başlatma
        val planJsonArray = JsonArray().apply {
            currentPlan.steps.forEach { s ->
                add(JsonObject().apply {
                    addProperty("step", s.index)
                    addProperty("title", s.title)
                })
            }
        }
        val initialSessionObj = JsonObject().apply {
            addProperty("id", sessionId)
            addProperty("goal", userGoal)
            addProperty("status", "IN_PROGRESS")
            add("plan", planJsonArray)
            addProperty("total_steps", 0)
        }
        supabaseLogger.logSession(initialSessionObj)

        // Firebase Firestore Oturum Başlatma
        firebaseLogger.logSessionStart(
            sessionId = sessionId,
            goal = userGoal,
            plan = currentPlan,
            provider = "AGENT",
            model = "DEFAULT"
        )

        val systemPrompt = AgentPromptProvider.buildSystemPrompt(
            relevantSkillMarkdown = relevantSkill,
            sessionMemoryContext = memoryContext
        )

        val history = mutableListOf<ChatMessage>()
        val planSummary = currentPlan.steps.joinToString("\n") { "${it.index}. ${it.title}" }
        history.add(ChatMessage(ChatMessage.Role.USER, "GÖREV: $userGoal\nPLAN:\n$planSummary"))

        var previousActionSignature = ""
        var identicalActionRepeatCount = 0
        val successfulActionSignatures = mutableListOf<String>()
        var lastPackageName = ""

        for (step in 1..maxSteps) {
            if (!currentCoroutineContext().isActive) {
                return@flow
            }

            // 1. Ekranı dökümle
            val screenDump = service.captureScreenDump()
            lastPackageName = screenDump.packageName
            val sanitizedRawDump = screenDump.formattedContent
                .replace("</untrusted_screen_state>", "[FILTERED_TAG]")
                .replace("<untrusted_screen_state>", "[FILTERED_TAG]")
            val sanitizedScreenContent = "<untrusted_screen_state>\n$sanitizedRawDump\n</untrusted_screen_state>"
            history.add(ChatMessage(ChatMessage.Role.USER, "EKRAN:\n$sanitizedScreenContent"))

            // Bağlam boyutunu esnet (son 25 mesajı tut - önceki adımları unutmasın)
            val boundedHistory = if (history.size > 25) {
                listOf(history.first()) + history.takeLast(24)
            } else {
                history
            }

            emit(AgentEvent.StatusLog("Adım $step/$maxSteps: Ekran inceleniyor ve karar alınıyor..."))

            // 2. Karar al
            val decisionResult = agentBrain.decideNextAction(
                systemPrompt = systemPrompt,
                history = boundedHistory
            )

            if (decisionResult.isFailure) {
                val errorMsg = decisionResult.exceptionOrNull()?.message ?: "Bilinmeyen model hatası"
                datasetCollector.recordStep(
                    sessionId = sessionId,
                    step = step,
                    goal = userGoal,
                    systemPrompt = systemPrompt,
                    screenDump = screenDump.formattedContent,
                    rawModelResponse = "HATA: $errorMsg",
                    parsedAction = null,
                    executionResult = "Model Hatası: $errorMsg",
                    provider = "UNKNOWN",
                    model = "UNKNOWN",
                    status = "FAILED"
                )

                memoryManager.saveLastSession(userGoal, "FAILED", errorMsg, lastPackageName)
                val failedSessionObj = JsonObject().apply {
                    addProperty("id", sessionId)
                    addProperty("status", "FAILED")
                    addProperty("execution_summary", errorMsg)
                    addProperty("total_steps", step)
                }
                supabaseLogger.logSession(failedSessionObj)
                firebaseLogger.logSessionFinish(sessionId, "FAILED", errorMsg, step)

                emit(AgentEvent.Failure("Yapay zeka yanıt veremedi: $errorMsg"))
                return@flow
            }

            val decision = decisionResult.getOrThrow()
            val action = decision.action
            emit(AgentEvent.StatusLog("Adım $step: ${action.thought}"))

            // Plan aşamasını güncelle
            if (action.stepIndex > 0) {
                currentPlan = currentPlan?.markStepInProgress(action.stepIndex)
                currentPlan?.let { emit(AgentEvent.PlanStepUpdated(it)) }
            }

            // 3. Sonlandırma veya Kullanıcıya soru kontrolleri
            when (action) {
                is AgentAction.Done -> {
                    datasetCollector.recordStep(
                        sessionId = sessionId,
                        step = step,
                        goal = userGoal,
                        systemPrompt = systemPrompt,
                        screenDump = screenDump.formattedContent,
                        rawModelResponse = decision.rawResponse,
                        parsedAction = action,
                        executionResult = action.summary,
                        provider = decision.provider,
                        model = decision.model,
                        status = "COMPLETED"
                    )

                    // Planı tamamen tamamlandı işaretle
                    currentPlan = currentPlan?.markAllCompleted()
                    currentPlan?.let { emit(AgentEvent.PlanStepUpdated(it)) }

                    // Oturum hafızasını kaydet
                    memoryManager.saveLastSession(userGoal, "COMPLETED", action.summary, lastPackageName)

                    // Bu başarılı seansı Markdown Skill olarak kaydet
                    skillManager.saveSkill(userGoal, lastPackageName, successfulActionSignatures)
                    emit(AgentEvent.StatusLog("İşlem yeni bir beceri (.md skill) olarak kaydedildi."))

                    // Supabase ve Firebase seansını güncelle
                    val completedSessionObj = JsonObject().apply {
                        addProperty("id", sessionId)
                        addProperty("status", "COMPLETED")
                        addProperty("execution_summary", action.summary)
                        addProperty("total_steps", step)
                    }
                    supabaseLogger.logSession(completedSessionObj)
                    firebaseLogger.logSessionFinish(sessionId, "COMPLETED", action.summary, step)

                    emit(AgentEvent.Success(action.summary))
                    return@flow
                }
                is AgentAction.AskUser -> {
                    datasetCollector.recordStep(
                        sessionId = sessionId,
                        step = step,
                        goal = userGoal,
                        systemPrompt = systemPrompt,
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

            // 4. Döngü kırıcı (Circuit Breaker)
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
                    systemPrompt = systemPrompt,
                    screenDump = screenDump.formattedContent,
                    rawModelResponse = decision.rawResponse,
                    parsedAction = action,
                    executionResult = "Ajan aynı eylemde takıldı ($currentSignature)",
                    provider = decision.provider,
                    model = decision.model,
                    status = "FAILED_STUCK"
                )
                memoryManager.saveLastSession(userGoal, "FAILED_STUCK", "Aynı eylemde takıldı: $currentSignature", lastPackageName)
                firebaseLogger.logSessionFinish(sessionId, "FAILED_STUCK", "Ajan aynı eylemde takıldı ($currentSignature)", step)
                emit(AgentEvent.Failure("Ajan aynı eylemde takıldı ($currentSignature). Güvenlik sebebiyle durduruldu."))
                return@flow
            }

            // 5. Güvenlik Kapısı kontrolü
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
                        systemPrompt = systemPrompt,
                        screenDump = screenDump.formattedContent,
                        rawModelResponse = decision.rawResponse,
                        parsedAction = action,
                        executionResult = "Hassas işlem kullanıcı tarafından reddedildi",
                        provider = decision.provider,
                        model = decision.model,
                        status = "REJECTED_BY_USER"
                    )
                    firebaseLogger.logSessionFinish(sessionId, "REJECTED_BY_USER", "Hassas işlem kullanıcı tarafından reddedildi", step)
                    emit(AgentEvent.Failure("Hassas işlem kullanıcı tarafından reddedildi."))
                    return@flow
                }
            }

            // 6. Eylemi çalıştır
            val executionSummary = actionExecutor.execute(action)
            emit(AgentEvent.StatusLog("Sonuç: $executionSummary"))
            successfulActionSignatures.add(action.toOperationalSignature())

            // Model hafızasına eylem ve sonucunu ekle
            history.add(ChatMessage(ChatMessage.Role.MODEL, "DÜŞÜNCE: ${action.thought}\nEYLEM: ${action.toOperationalSignature()}"))
            history.add(ChatMessage(ChatMessage.Role.USER, "SONUÇ: $executionSummary"))

            // Trajectory Dataset'ine adımı kaydet
            datasetCollector.recordStep(
                sessionId = sessionId,
                step = step,
                goal = userGoal,
                systemPrompt = systemPrompt,
                screenDump = screenDump.formattedContent,
                rawModelResponse = decision.rawResponse,
                parsedAction = action,
                executionResult = executionSummary,
                provider = decision.provider,
                model = decision.model,
                status = "IN_PROGRESS"
            )

            // Supabase'e adımı kaydet
            val stepObj = JsonObject().apply {
                addProperty("id", "${sessionId}_step_$step")
                addProperty("session_id", sessionId)
                addProperty("step", step)
                addProperty("thought", action.thought)
                addProperty("action_signature", action.toOperationalSignature())
                addProperty("execution_result", executionSummary)
                addProperty("status", "IN_PROGRESS")
            }
            supabaseLogger.logStep(stepObj)

            // Firebase'e adımı ve LLM düşüncesini kaydet
            firebaseLogger.logStep(
                sessionId = sessionId,
                step = step,
                thought = action.thought,
                actionSignature = action.toOperationalSignature(),
                executionResult = executionSummary,
                status = "IN_PROGRESS"
            )

            // Dinamik bekleme payı
            val dynamicDelay = when (action) {
                is AgentAction.OpenApp -> 1600L
                is AgentAction.Scroll -> 700L
                else -> 900L
            }
            delay(dynamicDelay)
        }

        memoryManager.saveLastSession(userGoal, "MAX_STEPS_REACHED", "Maksimum adım sınırına ulaşıldı.", lastPackageName)
        firebaseLogger.logSessionFinish(sessionId, "MAX_STEPS_REACHED", "Maksimum adım sınırına ($maxSteps) ulaşıldı.", maxSteps)
        emit(AgentEvent.Failure("Maksimum adım sınırına ($maxSteps) ulaşıldı."))
    }.flowOn(Dispatchers.Default)
}
