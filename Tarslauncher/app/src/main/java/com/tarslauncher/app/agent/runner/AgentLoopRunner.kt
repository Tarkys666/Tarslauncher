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
    private val maxSteps: Int = 25,
    private val providerName: String = "GEMINI",
    private val modelName: String = "DEFAULT"
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

        // 3. Mini-Planlama Aşaması (Plan-and-Solve) — agentBrain.generatePlan ile doğrudan ayrıştırma
        emit(AgentEvent.StatusLog("Hedef analiz ediliyor ve mini-plan oluşturuluyor..."))
        val planInputText = buildString {
            append("GÖREV: $userGoal")
            if (!memoryContext.isNullOrBlank()) {
                append("\nÖNCEKİ BAĞLAM: $memoryContext")
            }
        }
        val planPrompt = listOf(ChatMessage(ChatMessage.Role.USER, planInputText))
        val planResult = agentBrain.generatePlan(
            systemPrompt = AgentPromptProvider.PLAN_SYSTEM_PROMPT,
            history = planPrompt
        )

        var currentPlan: AgentPlan? = null
        if (planResult.isSuccess) {
            val stepsList = planResult.getOrNull()
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

        // Supabase & Firebase Oturum Kaydı Başlatma
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

        firebaseLogger.logSessionStart(
            sessionId = sessionId,
            goal = userGoal,
            plan = currentPlan,
            provider = providerName,
            model = modelName
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
        var previousScreenContent = ""
        var lastActionWasWait = false
        val successfulActionSignatures = mutableListOf<String>()
        var lastPackageName = ""

        for (step in 1..maxSteps) {
            if (!currentCoroutineContext().isActive) {
                return@flow
            }

            // 1. Ekranı dökümle ve değişim kontrolü yap (Screen State Diffing)
            val screenDump = service.captureScreenDump()
            lastPackageName = screenDump.packageName
            val screenUnchanged = step > 1 && !lastActionWasWait && screenDump.formattedContent == previousScreenContent
            previousScreenContent = screenDump.formattedContent

            val sanitizedRawDump = screenDump.formattedContent
                .replace("</untrusted_screen_state>", "[FILTERED_TAG]")
                .replace("<untrusted_screen_state>", "[FILTERED_TAG]")
            val unchangedWarning = if (screenUnchanged) {
                "[SİSTEM UYARISI: Bir önceki eylemden sonra ekran içeriği DEĞİŞMEDİ! Aynı öğeye tekrar dokunmak yerine alternatif bir öğe deneyin, kaydırın (scroll) veya geri (back) gidin.]\n"
            } else {
                ""
            }
            val sanitizedScreenContent = "$unchangedWarning<untrusted_screen_state>\n$sanitizedRawDump\n</untrusted_screen_state>"
            history.add(ChatMessage(ChatMessage.Role.USER, "EKRAN (Adım $step):\n$sanitizedScreenContent"))

            // Bağlam boyutunu koru (ilk görev/plan mesajı + son 24 mesaj)
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
                    provider = providerName,
                    model = modelName,
                    status = "FAILED"
                )

                logSessionTermination(sessionId, userGoal, "FAILED", errorMsg, step, lastPackageName)
                emit(AgentEvent.Failure("Yapay zeka yanıt veremedi: $errorMsg"))
                return@flow
            }

            val decision = decisionResult.getOrThrow()
            val action = decision.action

            if (decision.reflection.isNotBlank()) {
                emit(AgentEvent.StatusLog("Doğrulama: ${decision.reflection}"))
            }
            emit(AgentEvent.StatusLog("Adım $step: ${action.thought}"))

            // Dinamik Yeniden Planlama (Re-Plan) kontrolü
            if (!decision.replan.isNullOrEmpty()) {
                currentPlan = currentPlan?.replaceRemainingSteps(decision.replan)
                currentPlan?.let { updated ->
                    emit(AgentEvent.PlanStepUpdated(updated))
                    val updatedSummary = updated.steps.joinToString("\n") { "${it.index}. ${it.title}" }
                    history.add(ChatMessage(ChatMessage.Role.USER, "GÜNCELLENEN PLAN:\n$updatedSummary"))
                    emit(AgentEvent.StatusLog("Plan dinamik olarak güncellendi (${decision.replan.size} kalan aşama)."))
                }
            } else if (action.stepIndex > 0) {
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

                    currentPlan = currentPlan?.markAllCompleted()
                    currentPlan?.let { emit(AgentEvent.PlanStepUpdated(it)) }

                    if (successfulActionSignatures.isNotEmpty()) {
                        skillManager.saveSkill(userGoal, lastPackageName, successfulActionSignatures)
                        emit(AgentEvent.StatusLog("İşlem yeni bir beceri (.md skill) olarak kaydedildi."))
                    }

                    logSessionTermination(sessionId, userGoal, "COMPLETED", action.summary, step, lastPackageName)
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
                    val answerDeferred = CompletableDeferred<String?>()
                    emit(AgentEvent.QuestionToUser(action.question, answerDeferred))

                    val userReply = answerDeferred.await()?.trim()
                    if (userReply.isNullOrBlank()) {
                        val cancelMsg = "Kullanıcı soruyu yanıtlamadı (${action.question})."
                        logSessionTermination(sessionId, userGoal, "ASK_USER_CANCELLED", cancelMsg, step, lastPackageName)
                        emit(AgentEvent.Failure(cancelMsg))
                        return@flow
                    }

                    emit(AgentEvent.StatusLog("Kullanıcı yanıtı: \"$userReply\""))
                    history.add(ChatMessage(ChatMessage.Role.MODEL, "SORU: ${action.question}"))
                    history.add(ChatMessage(ChatMessage.Role.USER, "KULLANICI YANITI: $userReply"))
                    lastActionWasWait = true
                    continue
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
                val stuckMsg = "Ajan aynı eylemde takıldı ($currentSignature)"
                datasetCollector.recordStep(
                    sessionId = sessionId,
                    step = step,
                    goal = userGoal,
                    systemPrompt = systemPrompt,
                    screenDump = screenDump.formattedContent,
                    rawModelResponse = decision.rawResponse,
                    parsedAction = action,
                    executionResult = stuckMsg,
                    provider = decision.provider,
                    model = decision.model,
                    status = "FAILED_STUCK"
                )
                logSessionTermination(sessionId, userGoal, "FAILED_STUCK", stuckMsg, step, lastPackageName)
                emit(AgentEvent.Failure("$stuckMsg. Güvenlik sebebiyle durduruldu."))
                return@flow
            }

            // 5. Güvenlik Kapısı kontrolü
            val (needsConfirm, promptReason) = safetyGate.evaluate(action, screenDump.formattedContent)
            if (needsConfirm) {
                val confirmationDeferred = CompletableDeferred<Boolean>()
                emit(AgentEvent.AwaitingConfirmation(promptReason, confirmationDeferred))

                val isApproved = confirmationDeferred.await()
                if (!isApproved) {
                    val rejectMsg = "Hassas işlem kullanıcı tarafından reddedildi"
                    datasetCollector.recordStep(
                        sessionId = sessionId,
                        step = step,
                        goal = userGoal,
                        systemPrompt = systemPrompt,
                        screenDump = screenDump.formattedContent,
                        rawModelResponse = decision.rawResponse,
                        parsedAction = action,
                        executionResult = rejectMsg,
                        provider = decision.provider,
                        model = decision.model,
                        status = "REJECTED_BY_USER"
                    )
                    logSessionTermination(sessionId, userGoal, "REJECTED_BY_USER", rejectMsg, step, lastPackageName)
                    emit(AgentEvent.Failure("$rejectMsg."))
                    return@flow
                }
            }

            // 6. Eylemi çalıştır (Semantik etiket zenginleştirmesi ile)
            val semanticSignature = buildSemanticSignature(action, service)
            val executionSummary = actionExecutor.execute(action)
            emit(AgentEvent.StatusLog("Sonuç: $executionSummary"))

            // Sadece BAŞARILI eylemleri skill olarak kaydet
            if (executionSummary.startsWith("BAŞARILI")) {
                successfulActionSignatures.add(semanticSignature)
            }
            lastActionWasWait = action is AgentAction.Wait

            // Model hafızasına eylem ve sonucunu ekle
            history.add(
                ChatMessage(
                    ChatMessage.Role.MODEL,
                    buildString {
                        if (decision.reflection.isNotBlank()) append("DOĞRULAMA: ${decision.reflection}\n")
                        append("DÜŞÜNCE: ${action.thought}\nEYLEM: $semanticSignature")
                    }
                )
            )
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
                addProperty("action_signature", semanticSignature)
                addProperty("execution_result", executionSummary)
                addProperty("status", "IN_PROGRESS")
            }
            supabaseLogger.logStep(stepObj)

            // Firebase'e adımı ve LLM düşüncesini kaydet
            firebaseLogger.logStep(
                sessionId = sessionId,
                step = step,
                thought = action.thought,
                actionSignature = semanticSignature,
                executionResult = executionSummary,
                status = "IN_PROGRESS",
                screenDumpSnippet = screenDump.formattedContent.take(500)
            )

            // Dinamik bekleme payı
            val dynamicDelay = when (action) {
                is AgentAction.OpenApp, is AgentAction.OpenUrl -> 1600L
                is AgentAction.Scroll -> 700L
                is AgentAction.Wait -> 200L
                else -> 900L
            }
            delay(dynamicDelay)
        }

        val maxStepsMsg = "Maksimum adım sınırına ($maxSteps) ulaşıldı."
        logSessionTermination(sessionId, userGoal, "MAX_STEPS_REACHED", maxStepsMsg, maxSteps, lastPackageName)
        emit(AgentEvent.Failure(maxStepsMsg))
    }.flowOn(Dispatchers.Default)

    private fun buildSemanticSignature(action: AgentAction, service: TarsAccessibilityService): String {
        return when (action) {
            is AgentAction.Tap -> {
                val summary = service.treeParser.getNodeSummary(action.targetId)
                if (!summary.isNullOrBlank()) "Tap(${action.targetId} [$summary])" else action.toOperationalSignature()
            }
            is AgentAction.LongPress -> {
                val summary = service.treeParser.getNodeSummary(action.targetId)
                if (!summary.isNullOrBlank()) "LongPress(${action.targetId} [$summary])" else action.toOperationalSignature()
            }
            is AgentAction.TypeText -> {
                val summary = service.treeParser.getNodeSummary(action.targetId)
                if (!summary.isNullOrBlank()) {
                    "TypeText(${action.targetId} [$summary], \"${action.text}\", enter=${action.pressEnter})"
                } else {
                    action.toOperationalSignature()
                }
            }
            else -> action.toOperationalSignature()
        }
    }

    private suspend fun logSessionTermination(
        sessionId: String,
        userGoal: String,
        status: String,
        summary: String,
        totalSteps: Int,
        lastPackageName: String
    ) {
        memoryManager.saveLastSession(userGoal, status, summary, lastPackageName)
        val sessionObj = JsonObject().apply {
            addProperty("id", sessionId)
            addProperty("status", status)
            addProperty("execution_summary", summary)
            addProperty("total_steps", totalSteps)
        }
        supabaseLogger.logSession(sessionObj)
        firebaseLogger.logSessionFinish(sessionId, status, summary, totalSteps)
    }
}

