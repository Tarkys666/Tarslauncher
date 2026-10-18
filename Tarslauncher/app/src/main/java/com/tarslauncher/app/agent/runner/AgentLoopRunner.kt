package com.tarslauncher.app.agent.runner

import android.content.Context
import com.tarslauncher.app.agent.data.AgentPreferences
import com.tarslauncher.app.agent.domain.engine.AgentBrain
import com.tarslauncher.app.agent.domain.model.AgentAction
import com.tarslauncher.app.agent.domain.model.AgentEvent
import com.tarslauncher.app.agent.domain.model.AgentPlan
import com.tarslauncher.app.agent.domain.model.ChatMessage
import com.tarslauncher.app.agent.domain.model.PlanStep
import com.tarslauncher.app.agent.domain.prompt.AgentPromptProvider
import com.tarslauncher.app.agent.service.AgentOverlayNotchManager
import com.tarslauncher.app.agent.service.TarsAccessibilityService
import com.tarslauncher.app.data.model.ChecklistItem
import com.tarslauncher.app.data.model.WorkspaceItem
import com.tarslauncher.app.data.model.WorkspaceItemType
import com.tarslauncher.app.data.workspace.FirebaseWorkspaceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Executes Notion-style Workspace + LLM requests and updates the Dynamic Island overlay.
 */
class AgentLoopRunner(
    private val context: Context,
    private val agentBrain: AgentBrain,
    private val workspaceRepository: FirebaseWorkspaceRepository = FirebaseWorkspaceRepository(context)
) {

    fun runAgent(userGoal: String): Flow<AgentEvent> = flow {
        val prefs = AgentPreferences(context)
        if (!prefs.isAgentEnabled()) {
            emit(AgentEvent.Failure("Güvenlik Kilidi: TARS Ajanı ayarlardan tamamen kapatılmış durumda."))
            return@flow
        }

        val cleanGoal = userGoal.trim().take(MAX_PROMPT_LENGTH)
        if (cleanGoal.isBlank()) {
            emit(AgentEvent.Failure("Boş komut işlenemez."))
            return@flow
        }

        val service = TarsAccessibilityService.getInstance()
        emit(AgentEvent.StatusLog("Girdi analiz ediliyor: \"${cleanGoal.take(120)}\""))

        val initialSteps = listOf(
            PlanStep(index = 1, title = "Niyet ve içerik analizi (LLM)", isCompleted = false, isInProgress = true),
            PlanStep(index = 2, title = "Firebase Workspace senkronizasyonu", isCompleted = false, isInProgress = false)
        )
        var currentPlan = AgentPlan(goal = cleanGoal, steps = initialSteps)
        emit(AgentEvent.PlanCreated(currentPlan))

        service?.overlayNotch?.showOrUpdate(
            stepNumber = 1,
            plan = currentPlan,
            liveDetail = "Düşünüyor",
            state = AgentOverlayNotchManager.NotchState.THINKING
        )

        // 1. Mevcut Firebase Workspace kayıtlarını al
        val existingItems = workspaceRepository.getItems()
        val systemPrompt = AgentPromptProvider.buildWorkspaceSystemPrompt(existingItems)
        val history = mutableListOf(
            ChatMessage(ChatMessage.Role.USER, cleanGoal)
        )

        if (!currentCoroutineContext().isActive) return@flow

        // 2. LLM Kararını Al
        val decisionResult = agentBrain.decideNextAction(
            systemPrompt = systemPrompt,
            history = history
        )

        if (decisionResult.isFailure) {
            val errorMsg = decisionResult.exceptionOrNull()?.message ?: "Bilinmeyen model hatası"
            service?.overlayNotch?.showOrUpdate(
                stepNumber = 1,
                plan = currentPlan,
                liveDetail = "Hata",
                state = AgentOverlayNotchManager.NotchState.FAILED
            )
            emit(AgentEvent.Failure("Yapay zeka yanıt veremedi: $errorMsg"))
            return@flow
        }

        val decision = decisionResult.getOrThrow()
        var action = decision.action

        if (!decision.replan.isNullOrEmpty()) {
            val planSteps = decision.replan.mapIndexed { idx, title ->
                PlanStep(index = idx + 1, title = title, isCompleted = idx == 0, isInProgress = idx == 1)
            }
            currentPlan = AgentPlan(goal = userGoal, steps = planSteps)
            emit(AgentEvent.PlanStepUpdated(currentPlan))
        } else {
            currentPlan = currentPlan.markStepInProgress(2)
            emit(AgentEvent.PlanStepUpdated(currentPlan))
        }

        if (action.thought.isNotBlank()) {
            emit(AgentEvent.StatusLog("Analiz: ${action.thought}"))
        }

        // Eğer LLM ek soru sorduysa (AskUser) - Dynamic Island üzerinden etkileşimli soru-cevap
        var questionTurn = 0
        while (action is AgentAction.AskUser && questionTurn < 3) {
            questionTurn++
            val askAction = action as AgentAction.AskUser
            val answerDeferred = CompletableDeferred<String?>()

            service?.overlayNotch?.promptUserQuestion(askAction.question) { reply ->
                if (!answerDeferred.isCompleted) {
                    answerDeferred.complete(reply)
                }
            }
            emit(AgentEvent.QuestionToUser(askAction.question, answerDeferred))

            val userReply = withTimeoutOrNull(90_000L) {
                answerDeferred.await()
            }?.trim()

            if (userReply.isNullOrBlank()) {
                val cancelMsg = "Soru yanıtlanmadı veya iptal edildi."
                service?.overlayNotch?.showOrUpdate(
                    stepNumber = 1,
                    plan = currentPlan,
                    liveDetail = "İptal",
                    state = AgentOverlayNotchManager.NotchState.FAILED
                )
                emit(AgentEvent.Failure(cancelMsg))
                return@flow
            }

            emit(AgentEvent.StatusLog("Kullanıcı yanıtı: \"$userReply\""))
            history.add(ChatMessage(ChatMessage.Role.MODEL, "SORU: ${askAction.question}"))
            history.add(ChatMessage(ChatMessage.Role.USER, "YANIT: $userReply"))

            service?.overlayNotch?.showOrUpdate(
                stepNumber = 2,
                plan = currentPlan,
                liveDetail = "Düşünüyor",
                state = AgentOverlayNotchManager.NotchState.THINKING
            )

            val nextDecision = agentBrain.decideNextAction(systemPrompt, history)
            if (nextDecision.isFailure) {
                val err = nextDecision.exceptionOrNull()?.message ?: "Model hatası"
                service?.overlayNotch?.showOrUpdate(
                    stepNumber = 2,
                    plan = currentPlan,
                    liveDetail = "Hata",
                    state = AgentOverlayNotchManager.NotchState.FAILED
                )
                emit(AgentEvent.Failure(err))
                return@flow
            }
            action = nextDecision.getOrThrow().action
        }

        // 3. Firebase Workspace İşlemini Uygula
        service?.overlayNotch?.showOrUpdate(
            stepNumber = 2,
            plan = currentPlan,
            liveDetail = "Kaydediyor",
            state = AgentOverlayNotchManager.NotchState.ACTIVE
        )

        when (val finalAction = action) {
            is AgentAction.CreateWorkspaceItem -> {
                val checklistItems = finalAction.checklist.map { ChecklistItem(text = it) }
                val newItem = WorkspaceItem(
                    type = finalAction.itemType,
                    title = finalAction.title,
                    content = finalAction.content,
                    checklist = checklistItems,
                    priority = finalAction.priority,
                    tags = finalAction.tags
                )
                val saveResult = workspaceRepository.addItem(newItem)
                if (saveResult.isSuccess) {
                    val badge = when (finalAction.itemType) {
                        WorkspaceItemType.TASK -> "✓ Görev Eklendi"
                        WorkspaceItemType.NOTE -> "✓ Not Kaydedildi"
                        WorkspaceItemType.IDEA -> "✓ Fikir Eklendi"
                    }
                    val summary = "[${finalAction.itemType.labelTr}] '${finalAction.title}' Firebase çalışma alanına kaydedildi."
                    currentPlan = currentPlan.markAllCompleted()
                    emit(AgentEvent.PlanStepUpdated(currentPlan))
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = badge,
                        state = AgentOverlayNotchManager.NotchState.COMPLETED
                    )
                    emit(AgentEvent.Success(summary))
                } else {
                    val err = saveResult.exceptionOrNull()?.message ?: "Firebase kayıt hatası"
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "Kayıt Hatası",
                        state = AgentOverlayNotchManager.NotchState.FAILED
                    )
                    emit(AgentEvent.Failure("Firebase'e kaydedilemedi: $err"))
                }
            }

            is AgentAction.CompleteWorkspaceItem -> {
                val res = workspaceRepository.completeByQuery(finalAction.targetQuery)
                if (res.isSuccess) {
                    val item = res.getOrThrow()
                    val summary = "'${item.title}' tamamlandı olarak işaretlendi."
                    currentPlan = currentPlan.markAllCompleted()
                    emit(AgentEvent.PlanStepUpdated(currentPlan))
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "✓ Tamamlandı",
                        state = AgentOverlayNotchManager.NotchState.COMPLETED
                    )
                    emit(AgentEvent.Success(summary))
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Kayıt bulunamadı"
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "Bulunamadı",
                        state = AgentOverlayNotchManager.NotchState.FAILED
                    )
                    emit(AgentEvent.Failure(err))
                }
            }

            is AgentAction.DeleteWorkspaceItem -> {
                val res = workspaceRepository.deleteByQuery(finalAction.targetQuery)
                if (res.isSuccess) {
                    val item = res.getOrThrow()
                    val summary = "'${item.title}' çalışma alanından silindi."
                    currentPlan = currentPlan.markAllCompleted()
                    emit(AgentEvent.PlanStepUpdated(currentPlan))
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "✓ Silindi",
                        state = AgentOverlayNotchManager.NotchState.COMPLETED
                    )
                    emit(AgentEvent.Success(summary))
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Silinecek kayıt bulunamadı"
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "Bulunamadı",
                        state = AgentOverlayNotchManager.NotchState.FAILED
                    )
                    emit(AgentEvent.Failure(err))
                }
            }

            is AgentAction.ClearCompletedTasks -> {
                val res = workspaceRepository.clearCompletedTasks()
                if (res.isSuccess) {
                    val count = res.getOrDefault(0)
                    val summary = "$count tamamlanmış görev çalışma alanından temizlendi."
                    currentPlan = currentPlan.markAllCompleted()
                    emit(AgentEvent.PlanStepUpdated(currentPlan))
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "✓ Temizlendi",
                        state = AgentOverlayNotchManager.NotchState.COMPLETED
                    )
                    emit(AgentEvent.Success(summary))
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Temizleme hatası"
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "Hata",
                        state = AgentOverlayNotchManager.NotchState.FAILED
                    )
                    emit(AgentEvent.Failure(err))
                }
            }

            is AgentAction.ClearAllWorkspace -> {
                service?.overlayNotch?.showOrUpdate(
                    stepNumber = 2,
                    plan = currentPlan,
                    liveDetail = "Onay?",
                    state = AgentOverlayNotchManager.NotchState.THINKING
                )
                val confirmDeferred = CompletableDeferred<String?>()
                emit(
                    AgentEvent.QuestionToUser(
                        "Tüm çalışma alanı kayıtlarınızı kalıcı olarak silmek istediğinize emin misiniz? Onaylamak için 'Evet' yazın.",
                        confirmDeferred
                    )
                )
                val confirmation = withTimeoutOrNull(45_000L) {
                    confirmDeferred.await()
                }?.trim()?.lowercase().orEmpty()

                val isConfirmed = confirmation in listOf("evet", "onay", "yes", "sil", "tamam")
                if (!isConfirmed) {
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "İptal",
                        state = AgentOverlayNotchManager.NotchState.FAILED
                    )
                    emit(AgentEvent.Failure("Tüm kayıtları silme işlemi kullanıcı tarafından onaylanmadı."))
                    return@flow
                }

                val res = workspaceRepository.clearAll()
                if (res.isSuccess) {
                    val summary = "Tüm çalışma alanı kayıtları temizlendi."
                    currentPlan = currentPlan.markAllCompleted()
                    emit(AgentEvent.PlanStepUpdated(currentPlan))
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "✓ Sıfırlandı",
                        state = AgentOverlayNotchManager.NotchState.COMPLETED
                    )
                    emit(AgentEvent.Success(summary))
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Sıfırlama hatası"
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "Hata",
                        state = AgentOverlayNotchManager.NotchState.FAILED
                    )
                    emit(AgentEvent.Failure(err))
                }
            }

            is AgentAction.QueryWorkspace -> {
                val summary = finalAction.summaryResponse.ifBlank {
                    if (existingItems.isEmpty()) {
                        "Çalışma alanınızda henüz kayıtlı görev, not veya fikir bulunmuyor."
                    } else {
                        val tasks = existingItems.count { it.type == WorkspaceItemType.TASK && !it.isCompleted }
                        val notes = existingItems.count { it.type == WorkspaceItemType.NOTE }
                        val ideas = existingItems.count { it.type == WorkspaceItemType.IDEA }
                        "Aktif: $tasks Görev, $notes Not, $ideas Fikir bulunuyor."
                    }
                }
                currentPlan = currentPlan.markAllCompleted()
                emit(AgentEvent.PlanStepUpdated(currentPlan))
                service?.overlayNotch?.showAgentReply(summary)
                service?.overlayNotch?.showOrUpdate(
                    stepNumber = 2,
                    plan = currentPlan,
                    liveDetail = "✓ Özetlendi",
                    state = AgentOverlayNotchManager.NotchState.COMPLETED
                )
                emit(AgentEvent.Success(summary))
            }

            is AgentAction.Done -> {
                currentPlan = currentPlan.markAllCompleted()
                emit(AgentEvent.PlanStepUpdated(currentPlan))
                service?.overlayNotch?.showAgentReply(finalAction.summary)
                service?.overlayNotch?.showOrUpdate(
                    stepNumber = 2,
                    plan = currentPlan,
                    liveDetail = "✓ Tamamlandı",
                    state = AgentOverlayNotchManager.NotchState.COMPLETED
                )
                emit(AgentEvent.Success(finalAction.summary))
            }

            else -> {
                // Fallback: Eğer model bilinmeyen bir eylem döndürdüyse bile kullanıcının girdisini NOT olarak kaydet
                val fallbackItem = WorkspaceItem(
                    type = WorkspaceItemType.NOTE,
                    title = cleanGoal.take(36),
                    content = cleanGoal
                )
                val saveRes = workspaceRepository.addItem(fallbackItem)
                if (saveRes.isSuccess) {
                    currentPlan = currentPlan.markAllCompleted()
                    emit(AgentEvent.PlanStepUpdated(currentPlan))
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "✓ Not Kaydedildi",
                        state = AgentOverlayNotchManager.NotchState.COMPLETED
                    )
                    emit(AgentEvent.Success("Not olarak kaydedildi: '${fallbackItem.title}'"))
                } else {
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "Hata",
                        state = AgentOverlayNotchManager.NotchState.FAILED
                    )
                    emit(AgentEvent.Failure("İşlem yürütülemedi."))
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    companion object {
        private const val MAX_PROMPT_LENGTH = 2_000
    }
}
