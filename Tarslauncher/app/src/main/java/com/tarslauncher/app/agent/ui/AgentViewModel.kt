package com.tarslauncher.app.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tarslauncher.app.agent.data.AgentPreferences
import com.tarslauncher.app.agent.data.GroqAgentBrain
import com.tarslauncher.app.agent.data.GroqRemoteDataSource
import com.tarslauncher.app.agent.domain.engine.AgentBrain
import com.tarslauncher.app.agent.domain.model.AgentEvent
import com.tarslauncher.app.agent.runner.AgentLoopRunner
import com.tarslauncher.app.agent.service.TarsAccessibilityService
import com.tarslauncher.app.data.sync.GoogleAuthManager
import com.tarslauncher.app.data.workspace.FirebaseWorkspaceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentViewModel(
    application: Application,
    private val preferences: AgentPreferences = AgentPreferences(application),
    private val authManager: GoogleAuthManager = GoogleAuthManager(application),
    private val workspaceRepository: FirebaseWorkspaceRepository = FirebaseWorkspaceRepository(application, authManager)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        AgentUiState(
            provider = preferences.getProvider(),
            isQwenEnabled = preferences.isQwenEnabled(),
            groqApiKey = preferences.getActiveApiKey(),
            groqModel = preferences.getActiveModel(),
            googleWebClientId = preferences.getGoogleWebClientId(),
            isGoogleSignedIn = authManager.isGoogleSignedIn,
            userEmail = authManager.currentUser?.email,
            isServiceActive = TarsAccessibilityService.isConnected(),
            isAgentEnabled = preferences.isAgentEnabled()
        )
    )
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private var currentExecutionJob: Job? = null

    init {
        activeInstance = this
        viewModelScope.launch {
            TarsAccessibilityService.isServiceActive.collect { active ->
                _uiState.update { it.copy(isServiceActive = active) }
            }
        }
        viewModelScope.launch {
            authManager.authUserFlow.collect { user ->
                _uiState.update {
                    it.copy(
                        isGoogleSignedIn = user != null && !user.isAnonymous,
                        userEmail = user?.email
                    )
                }
            }
        }
        viewModelScope.launch {
            workspaceRepository.workspaceItemsFlow.collect { items ->
                _uiState.update { it.copy(workspaceCount = items.size) }
            }
        }
    }

    fun onTaskInputChanged(newInput: String) {
        _uiState.update { it.copy(taskInput = newInput) }
    }

    fun onSaveApiKey(newApiKey: String) {
        val trimmed = newApiKey.trim()
        preferences.setActiveApiKey(trimmed)
        _uiState.update {
            it.copy(
                groqApiKey = preferences.getActiveApiKey(),
                provider = preferences.getProvider(),
                groqModel = preferences.getActiveModel()
            )
        }
    }

    fun onToggleQwenEnabled(enabled: Boolean) {
        preferences.setQwenEnabled(enabled)
        _uiState.update {
            it.copy(
                isQwenEnabled = enabled,
                provider = preferences.getProvider(),
                groqModel = preferences.getActiveModel(),
                groqApiKey = preferences.getActiveApiKey()
            )
        }
    }

    fun onSaveGoogleWebClientId(clientId: String) {
        val trimmed = clientId.trim()
        preferences.setGoogleWebClientId(trimmed)
        _uiState.update { it.copy(googleWebClientId = preferences.getGoogleWebClientId()) }
    }

    fun onToggleAgentEnabled(enabled: Boolean) {
        preferences.setAgentEnabled(enabled)
        _uiState.update { it.copy(isAgentEnabled = enabled) }
        if (!enabled) {
            stopTask()
            TarsAccessibilityService.getInstance()?.stopAgentTask()
            TarsAccessibilityService.getInstance()?.overlayNotch?.hide()
        } else {
            TarsAccessibilityService.getInstance()?.overlayNotch?.showIdle()
        }
    }

    fun startTask(userTask: String = _uiState.value.taskInput) {
        val trimmedTask = userTask.trim()
        if (trimmedTask.isBlank()) return

        if (!preferences.isAgentEnabled()) {
            _uiState.update {
                it.copy(
                    isFailure = true,
                    finalMessage = "TARS Ajanı güvenlik ayarlarından tamamen kapatıldı. Çalıştırmak için ayarlardan açın."
                )
            }
            return
        }

        val activeModel = preferences.getActiveModel()

        currentExecutionJob?.cancel()

        _uiState.update {
            it.copy(
                isRunning = true,
                isFailure = false,
                finalMessage = null,
                currentPlan = null,
                pendingQuestion = null,
                taskInput = "",
                logs = listOf("Düşünüyor...")
            )
        }

        val remoteDataSource = GroqRemoteDataSource(
            apiKeyProvider = { preferences.getActiveApiKey() },
            preferredModelProvider = { preferences.getActiveModel() },
            userIdProvider = { authManager.currentUser?.uid.orEmpty() }
        )
        val brain: AgentBrain = GroqAgentBrain(remoteDataSource, modelName = activeModel)

        val runner = AgentLoopRunner(
            context = getApplication(),
            agentBrain = brain,
            workspaceRepository = workspaceRepository
        )

        currentExecutionJob = viewModelScope.launch {
            runner.runAgent(trimmedTask).collect { event ->
                handleAgentEvent(event)
            }
        }
    }

    fun stopTask() {
        _uiState.value.pendingQuestion?.userAnswer?.complete(null)
        currentExecutionJob?.cancel()
        currentExecutionJob = null
        TarsAccessibilityService.getInstance()?.overlayNotch?.showIdle()
        _uiState.update {
            it.copy(
                isRunning = false,
                pendingQuestion = null,
                logs = it.logs + "İşlem durduruldu."
            )
        }
    }

    fun clearLogs() {
        _uiState.update {
            it.copy(
                logs = emptyList(),
                finalMessage = null,
                isFailure = false,
                currentPlan = null
            )
        }
    }

    fun onAnswerQuestion(answer: String?) {
        val questionEvent = _uiState.value.pendingQuestion ?: return
        questionEvent.userAnswer.complete(answer?.trim()?.takeIf { it.isNotBlank() })
        _uiState.update { it.copy(pendingQuestion = null) }
    }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.StatusLog -> {
                _uiState.update { it.copy(logs = it.logs + event.message) }
            }
            is AgentEvent.AwaitingConfirmation -> {
                event.userDecision.complete(true)
            }
            is AgentEvent.QuestionToUser -> {
                _uiState.update {
                    it.copy(
                        pendingQuestion = event,
                        logs = it.logs + event.question
                    )
                }
            }
            is AgentEvent.PlanCreated -> {
                _uiState.update { it.copy(currentPlan = event.plan) }
            }
            is AgentEvent.PlanStepUpdated -> {
                _uiState.update { it.copy(currentPlan = event.plan) }
            }
            is AgentEvent.Success -> {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        pendingQuestion = null,
                        finalMessage = event.summary,
                        isFailure = false,
                        logs = it.logs + event.summary
                    )
                }
            }
            is AgentEvent.Failure -> {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        pendingQuestion = null,
                        finalMessage = event.errorReason,
                        isFailure = true,
                        logs = it.logs + event.errorReason
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentExecutionJob?.cancel()
        activeInstance = null
    }

    companion object {
        @Volatile
        private var activeInstance: AgentViewModel? = null

        fun getInstance(): AgentViewModel? = activeInstance
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AgentViewModel(application) as T
        }
    }
}
