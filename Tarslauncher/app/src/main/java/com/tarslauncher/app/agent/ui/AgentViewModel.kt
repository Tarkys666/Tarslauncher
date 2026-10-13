package com.tarslauncher.app.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tarslauncher.app.agent.data.AgentPreferences
import com.tarslauncher.app.agent.data.AgentProvider
import com.tarslauncher.app.agent.data.GeminiAgentBrain
import com.tarslauncher.app.agent.data.GeminiRemoteDataSource
import com.tarslauncher.app.agent.data.GroqAgentBrain
import com.tarslauncher.app.agent.data.GroqRemoteDataSource
import com.tarslauncher.app.agent.domain.engine.AgentBrain
import com.tarslauncher.app.agent.domain.model.AgentEvent
import com.tarslauncher.app.agent.runner.AgentLoopRunner
import com.tarslauncher.app.agent.service.TarsAccessibilityService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentViewModel(
    application: Application,
    private val preferences: AgentPreferences = AgentPreferences(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        AgentUiState(
            provider = preferences.getProvider(),
            geminiApiKey = preferences.getGeminiApiKey(),
            groqApiKey = preferences.getGroqApiKey(),
            groqModel = preferences.getGroqModel(),
            isServiceActive = TarsAccessibilityService.isConnected()
        )
    )
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private var currentExecutionJob: Job? = null

    init {
        viewModelScope.launch {
            TarsAccessibilityService.isServiceActive.collect { active ->
                _uiState.update { it.copy(isServiceActive = active) }
            }
        }
    }

    fun onTaskInputChanged(newInput: String) {
        _uiState.update { it.copy(taskInput = newInput) }
    }

    fun onProviderChanged(newProvider: AgentProvider) {
        preferences.setProvider(newProvider)
        _uiState.update { it.copy(provider = newProvider) }
    }

    fun onSaveApiKey(newApiKey: String) {
        val trimmed = newApiKey.trim()
        when (_uiState.value.provider) {
            AgentProvider.GEMINI -> {
                preferences.setGeminiApiKey(trimmed)
                _uiState.update { it.copy(geminiApiKey = trimmed) }
            }
            AgentProvider.GROQ -> {
                preferences.setGroqApiKey(trimmed)
                _uiState.update { it.copy(groqApiKey = trimmed) }
            }
        }
    }

    fun onSaveGroqModel(model: String) {
        val trimmed = model.trim().ifBlank { "qwen/qwen3.8-27b" }
        preferences.setGroqModel(trimmed)
        _uiState.update { it.copy(groqModel = trimmed) }
    }

    fun startTask(userTask: String = _uiState.value.taskInput) {
        val trimmedTask = userTask.trim()
        if (trimmedTask.isBlank()) return

        val state = _uiState.value
        val activeKey = state.activeApiKey.trim()
        if (activeKey.isBlank()) {
            val keyPrompt = if (state.provider == AgentProvider.GROQ) {
                "Lütfen önce geçerli bir Groq API anahtarı (gsk_...) kaydedin."
            } else {
                "Lütfen önce geçerli bir Gemini API anahtarı kaydedin."
            }
            _uiState.update {
                it.copy(
                    isFailure = true,
                    finalMessage = keyPrompt
                )
            }
            return
        }

        if (!TarsAccessibilityService.isConnected()) {
            _uiState.update {
                it.copy(
                    isFailure = true,
                    finalMessage = "TARS Erişilebilirlik Servisi aktif değil. Lütfen ayarlardan servisi açın."
                )
            }
            return
        }

        currentExecutionJob?.cancel()

        _uiState.update {
            it.copy(
                isRunning = true,
                isFailure = false,
                finalMessage = null,
                pendingQuestion = null,
                pendingConfirmation = null,
                logs = listOf("Başlatılıyor [${state.provider.name}]...")
            )
        }

        val brain: AgentBrain = when (state.provider) {
            AgentProvider.GEMINI -> {
                val remoteDataSource = GeminiRemoteDataSource(apiKeyProvider = { _uiState.value.geminiApiKey })
                GeminiAgentBrain(remoteDataSource)
            }
            AgentProvider.GROQ -> {
                val remoteDataSource = GroqRemoteDataSource(
                    apiKeyProvider = { _uiState.value.groqApiKey },
                    preferredModelProvider = { _uiState.value.groqModel }
                )
                GroqAgentBrain(remoteDataSource)
            }
        }
        val runner = AgentLoopRunner(getApplication(), brain)

        currentExecutionJob = viewModelScope.launch {
            runner.runAgent(trimmedTask).collect { event ->
                handleAgentEvent(event)
            }
        }
    }

    fun stopTask() {
        currentExecutionJob?.cancel()
        currentExecutionJob = null
        _uiState.update {
            it.copy(
                isRunning = false,
                pendingConfirmation = null,
                logs = it.logs + "[Durduruldu] Görev iptal edildi."
            )
        }
    }

    fun onConfirmDecision(approved: Boolean) {
        val confirmation = _uiState.value.pendingConfirmation ?: return
        confirmation.userDecision.complete(approved)
        _uiState.update { it.copy(pendingConfirmation = null) }
    }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.StatusLog -> {
                _uiState.update { it.copy(logs = it.logs + event.message) }
            }
            is AgentEvent.AwaitingConfirmation -> {
                _uiState.update { it.copy(pendingConfirmation = event) }
            }
            is AgentEvent.QuestionToUser -> {
                _uiState.update {
                    it.copy(
                        pendingQuestion = event.question,
                        logs = it.logs + "> [Soru] ${event.question}"
                    )
                }
            }
            is AgentEvent.Success -> {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        finalMessage = event.summary,
                        isFailure = false,
                        logs = it.logs + "> [Tamamlandı] ${event.summary}"
                    )
                }
            }
            is AgentEvent.Failure -> {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        finalMessage = event.errorReason,
                        isFailure = true,
                        logs = it.logs + "> [Hata] ${event.errorReason}"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentExecutionJob?.cancel()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AgentViewModel(application) as T
        }
    }
}
