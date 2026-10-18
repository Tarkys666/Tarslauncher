package com.tarslauncher.app.agent.runner

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
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
import java.util.Calendar
import java.util.Locale
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

        // 1. Mevcut Second Brain (Yerel Kasa + Firebase) kayıtlarını al
        val existingItems = workspaceRepository.getItems()
        val systemPrompt = AgentPromptProvider.buildWorkspaceSystemPrompt(
            existingItems = existingItems,
            userQuery = cleanGoal
        )
        val history = mutableListOf<ChatMessage>().apply {
            synchronized(sessionLock) {
                addAll(recentSessionHistory.takeLast(MAX_SESSION_HISTORY_MESSAGES))
            }
            add(ChatMessage(ChatMessage.Role.USER, cleanGoal))
        }

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

        // 3. Second Brain & Firebase Workspace İşlemini Uygula
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
                    val alarmInfo = resolveAndScheduleAlarm(finalAction, cleanGoal, service)
                    val badge = if (alarmInfo != null) {
                        "⏰ $alarmInfo"
                    } else {
                        when (finalAction.itemType) {
                            WorkspaceItemType.TASK -> "✓ Görev Eklendi"
                            WorkspaceItemType.NOTE -> "✓ Not Kaydedildi"
                            WorkspaceItemType.IDEA -> "✓ Fikir Eklendi"
                        }
                    }
                    val summary = if (alarmInfo != null) {
                        "'${finalAction.title}' kaydedildi ve $alarmInfo için alarm + geri sayım başlatıldı."
                    } else {
                        "[${finalAction.itemType.labelTr}] '${finalAction.title}' Second Brain kasasına kaydedildi."
                    }
                    recordSessionTurn(cleanGoal, summary)
                    currentPlan = currentPlan.markAllCompleted()
                    emit(AgentEvent.PlanStepUpdated(currentPlan))
                    if (alarmInfo == null) {
                        service?.overlayNotch?.showOrUpdate(
                            stepNumber = 2,
                            plan = currentPlan,
                            liveDetail = badge,
                            state = AgentOverlayNotchManager.NotchState.COMPLETED
                        )
                    }
                    emit(AgentEvent.Success(summary))
                } else {
                    val err = saveResult.exceptionOrNull()?.message ?: "Kayıt hatası"
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "Kayıt Hatası",
                        state = AgentOverlayNotchManager.NotchState.FAILED
                    )
                    emit(AgentEvent.Failure("Kaydedilemedi: $err"))
                }
            }

            is AgentAction.UpdateWorkspaceItem -> {
                val res = workspaceRepository.appendOrUpdateByQuery(
                    idOrQuery = finalAction.targetQuery,
                    newTitle = finalAction.title,
                    additionalContent = finalAction.content,
                    additionalChecklist = finalAction.checklist,
                    additionalTags = finalAction.tags,
                    appendMode = finalAction.appendMode
                )
                if (res.isSuccess) {
                    val item = res.getOrThrow()
                    val summary = "'${item.title}' notu Second Brain kasasında güncellendi."
                    recordSessionTurn(cleanGoal, summary)
                    currentPlan = currentPlan.markAllCompleted()
                    emit(AgentEvent.PlanStepUpdated(currentPlan))
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "✓ Güncellendi",
                        state = AgentOverlayNotchManager.NotchState.COMPLETED
                    )
                    emit(AgentEvent.Success(summary))
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Güncelleme hatası"
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "Hata",
                        state = AgentOverlayNotchManager.NotchState.FAILED
                    )
                    emit(AgentEvent.Failure(err))
                }
            }

            is AgentAction.CompleteWorkspaceItem -> {
                val res = workspaceRepository.completeByQuery(finalAction.targetQuery)
                if (res.isSuccess) {
                    val item = res.getOrThrow()
                    val summary = "'${item.title}' tamamlandı olarak işaretlendi."
                    recordSessionTurn(cleanGoal, summary)
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
                    val summary = "'${item.title}' Second Brain kasasından silindi."
                    recordSessionTurn(cleanGoal, summary)
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
                    val summary = "$count tamamlanmış görev temizlendi."
                    recordSessionTurn(cleanGoal, summary)
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
                val confirmText = "Tüm notlarınızı kalıcı olarak silmek istediğinize emin misiniz?"
                val confirmDeferred = CompletableDeferred<String?>()
                service?.overlayNotch?.promptUserQuestion(confirmText) { reply ->
                    if (!confirmDeferred.isCompleted) {
                        confirmDeferred.complete(reply)
                    }
                }
                emit(AgentEvent.QuestionToUser(confirmText, confirmDeferred))

                val confirmation = withTimeoutOrNull(60_000L) {
                    confirmDeferred.await()
                }?.trim()?.lowercase(Locale.forLanguageTag("tr-TR")).orEmpty()

                val isConfirmed = confirmation in listOf("evet", "onay", "onayla", "yes", "sil", "tamam")
                if (!isConfirmed) {
                    service?.overlayNotch?.showOrUpdate(
                        stepNumber = 2,
                        plan = currentPlan,
                        liveDetail = "İptal",
                        state = AgentOverlayNotchManager.NotchState.FAILED
                    )
                    emit(AgentEvent.Failure("Tüm kayıtları silme işlemi iptal edildi."))
                    return@flow
                }

                val res = workspaceRepository.clearAll()
                if (res.isSuccess) {
                    val summary = "Tüm Second Brain kayıtları temizlendi."
                    synchronized(sessionLock) { recentSessionHistory.clear() }
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
                        "Second Brain kasanızda henüz kayıtlı not, görev veya fikir bulunmuyor."
                    } else {
                        existingItems.take(5).joinToString("\n") { "- ${it.title}" }
                    }
                }
                recordSessionTurn(cleanGoal, summary)
                currentPlan = currentPlan.markAllCompleted()
                emit(AgentEvent.PlanStepUpdated(currentPlan))
                service?.overlayNotch?.showAgentReply(summary)
                emit(AgentEvent.Success(summary))
            }

            is AgentAction.Done -> {
                recordSessionTurn(cleanGoal, finalAction.summary)
                currentPlan = currentPlan.markAllCompleted()
                emit(AgentEvent.PlanStepUpdated(currentPlan))
                service?.overlayNotch?.showAgentReply(finalAction.summary)
                emit(AgentEvent.Success(finalAction.summary))
            }

            else -> {
                // Fallback: Eğer model bilinmeyen bir eylem döndürdüyse bile kullanıcının girdisini NOT olarak Second Brain'e kaydet
                val fallbackItem = WorkspaceItem(
                    type = WorkspaceItemType.NOTE,
                    title = cleanGoal.take(48),
                    content = if (cleanGoal.length > 48) cleanGoal else ""
                )
                val saveRes = workspaceRepository.addItem(fallbackItem)
                if (saveRes.isSuccess) {
                    recordSessionTurn(cleanGoal, "Not olarak kaydedildi: ${fallbackItem.title}")
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

    private fun recordSessionTurn(userText: String, modelSummary: String) {
        synchronized(sessionLock) {
            recentSessionHistory.add(ChatMessage(ChatMessage.Role.USER, userText.take(300)))
            recentSessionHistory.add(ChatMessage(ChatMessage.Role.MODEL, modelSummary.take(400)))
            while (recentSessionHistory.size > MAX_SESSION_HISTORY_MESSAGES) {
                recentSessionHistory.removeAt(0)
            }
        }
    }

    private fun resolveAndScheduleAlarm(
        action: AgentAction.CreateWorkspaceItem,
        rawInput: String,
        service: TarsAccessibilityService?
    ): String? {
        val now = System.currentTimeMillis()
        val lowerInput = rawInput.lowercase(Locale.forLanguageTag("tr-TR"))

        // 1. Check relative duration ("10 dakika sonra", "30 saniye sonra", etc.)
        var durationSec = action.durationSeconds ?: 0L
        if (durationSec <= 0L) {
            val relMatch = RELATIVE_TIME_REGEX.find(lowerInput)
            if (relMatch != null) {
                val amount = relMatch.groupValues[1].toLongOrNull() ?: 0L
                val unit = relMatch.groupValues[2]
                durationSec = when {
                    unit.startsWith("sa") && unit != "saniye" -> amount * 3600L
                    unit.startsWith("d") -> amount * 60L
                    else -> amount
                }
            }
        }

        if (durationSec > 0L) {
            val targetMs = now + (durationSec * 1000L)
            val cal = Calendar.getInstance().apply { timeInMillis = targetMs }
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val m = cal.get(Calendar.MINUTE)
            val timeFormatted = String.format(Locale.US, "%02d:%02d", h, m)

            if (durationSec >= 60L) {
                setSystemClockAlarm(h, m, action.title)
            }
            service?.overlayNotch?.startAlarmCountdown(targetMs, action.title, timeFormatted)
            return timeFormatted
        }

        // 2. Check explicit clock time ("yarın saat 8 de", "14:30", etc.)
        var hour = action.alarmHour
        var minute = action.alarmMinute ?: 0

        if (hour == null) {
            val saatMatch = SAAT_TIME_REGEX.find(lowerInput) ?: COLON_TIME_REGEX.find(lowerInput)
            if (saatMatch != null) {
                val parsedH = saatMatch.groupValues[1].toIntOrNull()
                val parsedM = saatMatch.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
                if (parsedH != null && parsedH in 0..23 && parsedM in 0..59) {
                    hour = parsedH
                    minute = parsedM
                }
            }
        }

        val finalHour = hour ?: return null
        val finalMinute = minute.coerceIn(0, 59)

        val mentionsTomorrow = action.isTomorrow || lowerInput.contains("yarın") || lowerInput.contains("yarin")
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, finalHour)
            set(Calendar.MINUTE, finalMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (mentionsTomorrow || cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val targetMs = cal.timeInMillis
        val timeFormatted = String.format(Locale.US, "%02d:%02d", finalHour, finalMinute)

        setSystemClockAlarm(finalHour, finalMinute, action.title)
        service?.overlayNotch?.startAlarmCountdown(targetMs, action.title, timeFormatted)
        return timeFormatted
    }

    private fun setSystemClockAlarm(hour: Int, minute: Int, label: String) {
        runCatching {
            val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label.take(60))
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(alarmIntent)
        }
    }

    companion object {
        private const val MAX_PROMPT_LENGTH = 2_000
        private const val MAX_SESSION_HISTORY_MESSAGES = 8
        private val sessionLock = Any()
        private val recentSessionHistory = mutableListOf<ChatMessage>()
        private val RELATIVE_TIME_REGEX = Regex("""\b(\d{1,4})\s*(saniye|sn|dakika|dk|saat)\s*sonra\b""")
        private val SAAT_TIME_REGEX = Regex("""\bsaat\s*(\d{1,2})(?:[:.](\d{2}))?\b""")
        private val COLON_TIME_REGEX = Regex("""\b([01]?\d|2[0-3])[:.]([0-5]\d)\b""")
    }
}

