package com.tarslauncher.app.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tarslauncher.app.agent.data.AgentPreferences
import com.tarslauncher.app.agent.data.GeminiAgentBrain
import com.tarslauncher.app.agent.data.GeminiRemoteDataSource
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
            apiKey = preferences.getGeminiApiKey(),
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

    fun onSaveApiKey(newApiKey: String) {
        preferences.setGeminiApiKey(newApiKey)
        _uiState.update { it.copy(apiKey = newApiKey.trim()) }
    }

    fun startTask(userTask: String = _uiState.value.taskInput) {
        val trimmedTask = userTask.trim()
        if (trimmedTask.isBlank()) return

        val apiKey = _uiState.value.apiKey.trim()
        if (apiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    isFailure = true,
                    finalMessage = "Lütfen önce geçerli bir Gemini API anahtarı kaydedin."
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
                logs = listOf("Başlatılıyor...")
            )
        }

        val remoteDataSource = GeminiRemoteDataSource(apiKeyProvider = { _uiState.value.apiKey })
        val brain = GeminiAgentBrain(remoteDataSource)
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
                _uiState.update {
                    it.copy(logs = it.logs + event.message)
                }
            }

            is AgentEvent.AwaitingConfirmation -> {
                _uiState.update {
                    it.copy(pendingConfirmation = event)
                }
            }

            is AgentEvent.QuestionToUser -> {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        pendingQuestion = event.question,
                        logs = it.logs + "[Soru] ${event.question}"
                    )
                }
            }

            is AgentEvent.Success -> {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        isFailure = false,
                        finalMessage = event.summary,
                        logs = it.logs + "[Başarılı] ${event.summary}"
                    )
                }
            }

            is AgentEvent.Failure -> {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        isFailure = true,
                        finalMessage = event.errorReason,
                        logs = it.logs + "[Hata] ${event.errorReason}"
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
            if (modelClass.isAssignableFrom(AgentViewModel::class.java)) {
                return AgentViewModel(application) as T
            }
            throw IllegalArgumentException("Bilinmeyen ViewModel sınıfı: ${modelClass.name}")
        }
    }
}
