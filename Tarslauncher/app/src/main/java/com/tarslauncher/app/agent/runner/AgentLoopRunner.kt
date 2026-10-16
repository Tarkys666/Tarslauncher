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
import com.tarslauncher.app.agent.service.AgentOverlayNotchManager
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

        val prefs = com.tarslauncher.app.agent.data.AgentPreferences(context)
        if (!prefs.isAgentEnabled()) {
            emit(AgentEvent.Failure("Güvenlik Kilidi: TARS Ajanı ayarlardan tamamen kapatılmış durumda."))
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

        // 3. Başlangıç Planı (Tek Geçişli / Single-Pass Planlama ile ekstra API çağrısı ve Rate Limit önlenir)
        val initialSteps = listOf(PlanStep(index = 1, title = userGoal, isCompleted = false, isInProgress = true))
        var currentPlan: AgentPlan? = AgentPlan(goal = userGoal, steps = initialSteps)
        emit(AgentEvent.PlanCreated(currentPlan!!))
        service.overlayNotch.showOrUpdate(
            stepNumber = 1,
            plan = currentPlan,
            liveDetail = "Planlıyor",
            state = AgentOverlayNotchManager.NotchState.THINKING
        )

        // Supabase & Firebase Oturum Kaydı Başlatma
        val planJsonArray = JsonArray().apply {
            currentPlan?.steps?.forEach { s ->
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
        history.add(ChatMessage(ChatMessage.Role.USER, "HEDEF GÖREV: $userGoal"))

        var previousActionSignature = ""
        var identicalActionRepeatCount = 0
        var previousScreenContent = ""
        var lastActionWasWait = false
        val successfulActionSignatures = mutableListOf<String>()
        var lastPackageName = ""

        for (step in 1..maxSteps) {
            if (!currentCoroutineContext().isActive || !prefs.isAgentEnabled()) {
                if (!prefs.isAgentEnabled()) {
                    emit(AgentEvent.Failure("Ajan güvenlik kilidi devreye girdi ve işlem durduruldu."))
                }
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
                "[SİSTEM UYARISI: Önceki eylemden sonra ekran DEĞİŞMEDİ! Aynı öğeye tekrar dokunmak yerine farklı bir öğe seçin, scroll yapın veya back kullanın.]\n"
            } else {
                ""
            }
            val firstStepPlanHint = if (step == 1) {
                "[İLK ADIM NOTU: Hedefi 2-4 kısa aşamaya bölerek JSON yanıtına \"replan\": [\"1. aşama\", \"2. aşama\", ...] dizisini ekle ve ilk eylemini (action) üret.]\n"
            } else {
                ""
            }
            val sanitizedScreenContent = "$firstStepPlanHint$unchangedWarning<untrusted_screen_state>\n$sanitizedRawDump\n</untrusted_screen_state>"

            // Token Tasarrufu (Rate Limit Koruması):
            // Geçmiş adımların tam ekran ağaçları yerine sadece kompakt özetleri history'de tutulur;
            // tam ekran ağacı YALNIZCA mevcut adım (currentScreenMsg) için gönderilir!
            val boundedHistory = if (history.size > 13) {
                listOf(history.first()) + history.takeLast(12)
            } else {
                history
            }
            val currentScreenMsg = ChatMessage(ChatMessage.Role.USER, "AKTİF EKRAN (Adım $step):\n$sanitizedScreenContent")
            val requestHistory = boundedHistory + currentScreenMsg

            emit(AgentEvent.StatusLog("Adım $step/$maxSteps: Ekran inceleniyor ve karar alınıyor..."))
            service.overlayNotch.showOrUpdate(
                stepNumber = step,
                plan = currentPlan,
                liveDetail = "İnceliyor",
                state = AgentOverlayNotchManager.NotchState.THINKING
            )

            // 2. Karar al
            val decisionResult = agentBrain.decideNextAction(
                systemPrompt = systemPrompt,
                history = requestHistory
            )

            if (decisionResult.isFailure) {
                val errorMsg = decisionResult.exceptionOrNull()?.message ?: "Bilinmeyen model hatası"
                service.overlayNotch.showOrUpdate(
                    stepNumber = step,
                    plan = currentPlan,
                    liveDetail = errorMsg,
                    state = AgentOverlayNotchManager.NotchState.FAILED
                )
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

            // Dinamik Planlama & Yeniden Planlama (Re-Plan) kontrolü
            if (!decision.replan.isNullOrEmpty()) {
                if (step == 1) {
                    val planSteps = decision.replan.mapIndexed { idx, title ->
                        PlanStep(index = idx + 1, title = title, isCompleted = false, isInProgress = idx == 0)
                    }
                    currentPlan = AgentPlan(goal = userGoal, steps = planSteps)
                    emit(AgentEvent.PlanCreated(currentPlan!!))
                    emit(AgentEvent.StatusLog("Mini-Plan hazırlandı (${planSteps.size} aşama)."))
                } else {
                    currentPlan = currentPlan?.replaceRemainingSteps(decision.replan)
                    currentPlan?.let { updated ->
                        emit(AgentEvent.PlanStepUpdated(updated))
                        emit(AgentEvent.StatusLog("Plan güncellendi (${decision.replan.size} kalan aşama)."))
                    }
                }
            } else if (action.stepIndex > 0) {
                currentPlan = currentPlan?.markStepInProgress(action.stepIndex)
                currentPlan?.let { emit(AgentEvent.PlanStepUpdated(it)) }
            }

            val actionWord = when (action) {
                is AgentAction.Tap, is AgentAction.LongPress -> "Tıklıyor"
                is AgentAction.TypeText -> "Yazıyor"
                is AgentAction.Scroll -> "Kaydırıyor"
                is AgentAction.OpenApp, is AgentAction.OpenUrl -> "Açıyor"
                is AgentAction.SearchWeb -> "Arıyor"
                is AgentAction.SetVolume -> "Ses"
                is AgentAction.MediaControl -> "Medya"
                is AgentAction.Flashlight -> "Fener"
                is AgentAction.TakeScreenshot -> "Görüntü"
                is AgentAction.LockScreen -> "Kilitliyor"
                is AgentAction.OpenSettings -> "Ayarlar"
                is AgentAction.SetAlarm -> "Alarm"
                is AgentAction.SetTimer -> "Sayaç"
                is AgentAction.Wait -> "Bekliyor"
                is AgentAction.Back -> "Geri"
                is AgentAction.Home -> "Ana Ekran"
                is AgentAction.Recents -> "Son Görevler"
                is AgentAction.Notifications, is AgentAction.QuickSettings -> "Bildirimler"
                is AgentAction.ClipboardSet -> "Kopyalıyor"
                is AgentAction.Done -> "Tamamlandı"
                is AgentAction.AskUser -> "Soru"
                else -> "İşliyor"
            }

            service.overlayNotch.showOrUpdate(
                stepNumber = step,
                plan = currentPlan,
                liveDetail = actionWord,
                state = AgentOverlayNotchManager.NotchState.ACTIVE
            )

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
                    service.overlayNotch.showOrUpdate(
                        stepNumber = step,
                        plan = currentPlan,
                        liveDetail = "✓ ${action.summary}",
                        state = AgentOverlayNotchManager.NotchState.COMPLETED
                    )

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
                    service.overlayNotch.showOrUpdate(
                        stepNumber = step,
                        plan = currentPlan,
                        liveDetail = "? ${action.question}",
                        state = AgentOverlayNotchManager.NotchState.THINKING
                    )
                    val answerDeferred = CompletableDeferred<String?>()
                    emit(AgentEvent.QuestionToUser(action.question, answerDeferred))

                    val userReply = answerDeferred.await()?.trim()
                    if (userReply.isNullOrBlank()) {
                        val cancelMsg = "Kullanıcı soruyu yanıtlamadı (${action.question})."
                        service.overlayNotch.showOrUpdate(
                            stepNumber = step,
                            plan = currentPlan,
                            liveDetail = cancelMsg,
                            state = AgentOverlayNotchManager.NotchState.FAILED
                        )
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
                service.overlayNotch.showOrUpdate(
                    stepNumber = step,
                    plan = currentPlan,
                    liveDetail = stuckMsg,
                    state = AgentOverlayNotchManager.NotchState.FAILED
                )
                logSessionTermination(sessionId, userGoal, "FAILED_STUCK", stuckMsg, step, lastPackageName)
                emit(AgentEvent.Failure("$stuckMsg. Güvenlik sebebiyle durduruldu."))
                return@flow
            }

            // 5. Güvenlik Kapısı kontrolü
            val (needsConfirm, promptReason) = safetyGate.evaluate(action, screenDump.formattedContent)
            if (needsConfirm) {
                service.overlayNotch.showOrUpdate(
                    stepNumber = step,
                    plan = currentPlan,
                    liveDetail = "⚠ Onay bekleniyor: $promptReason",
                    state = AgentOverlayNotchManager.NotchState.THINKING
                )
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
                    service.overlayNotch.showOrUpdate(
                        stepNumber = step,
                        plan = currentPlan,
                        liveDetail = rejectMsg,
                        state = AgentOverlayNotchManager.NotchState.FAILED
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
            service.overlayNotch.showOrUpdate(
                stepNumber = step,
                plan = currentPlan,
                liveDetail = actionWord,
                state = AgentOverlayNotchManager.NotchState.ACTIVE
            )

            // Sadece BAŞARILI eylemleri skill olarak kaydet
            if (executionSummary.startsWith("BAŞARILI")) {
                successfulActionSignatures.add(semanticSignature)
            }
            lastActionWasWait = action is AgentAction.Wait

            // Model hafızasına kompakt ekran özeti, eylem ve sonucunu ekle (Token şişmesini önler)
            history.add(
                ChatMessage(
                    ChatMessage.Role.USER,
                    "ADIM $step ÖZETİ [Paket: ${screenDump.packageName}, ${screenDump.elementCount} öğe]"
                )
            )
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

            // Dinamik bekleme payı (Ekran yüklenmesi ve RPM/TPM koruması)
            val dynamicDelay = when (action) {
                is AgentAction.OpenApp, is AgentAction.OpenUrl -> 1800L
                is AgentAction.Scroll -> 1000L
                is AgentAction.Wait -> 300L
                else -> 1200L
            }
            delay(dynamicDelay)
        }

        val maxStepsMsg = "Maksimum adım sınırına ($maxSteps) ulaşıldı."
        service.overlayNotch.showOrUpdate(
            stepNumber = maxSteps,
            plan = currentPlan,
            liveDetail = maxStepsMsg,
            state = AgentOverlayNotchManager.NotchState.FAILED
        )
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

