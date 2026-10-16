package com.tarslauncher.app.agent.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference
import com.tarslauncher.app.agent.data.AgentPreferences
import com.tarslauncher.app.agent.data.GroqAgentBrain
import com.tarslauncher.app.agent.data.GroqRemoteDataSource
import com.tarslauncher.app.agent.data.logging.AgentDatasetCollector
import com.tarslauncher.app.agent.domain.engine.AgentBrain
import com.tarslauncher.app.agent.domain.model.ScreenDump
import com.tarslauncher.app.agent.runner.AgentLoopRunner
import com.tarslauncher.app.agent.ui.AgentViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TarsAccessibilityService : AccessibilityService() {

    val treeParser = AccessibilityTreeParser()
    val gestureDispatcher = GestureDispatcher { this }
    val overlayNotch: AgentOverlayNotchManager by lazy { AgentOverlayNotchManager(this) }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeOverlayJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentInstance = WeakReference(this)
        _isServiceActive.value = true

        overlayNotch.onStopRequested = {
            stopAgentTask()
        }

        overlayNotch.onTaskSubmitted = { prompt ->
            val vm = AgentViewModel.getInstance()
            if (vm != null) {
                vm.startTask(prompt)
            } else {
                startAgentTaskFromOverlay(prompt)
            }
        }

        val prefs = AgentPreferences(this)
        if (prefs.isAgentEnabled()) {
            overlayNotch.showIdle()
        } else {
            overlayNotch.hide()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopAgentTask()
        overlayNotch.hide()
        currentInstance = null
        _isServiceActive.value = false
        treeParser.clear()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive monitoring; tree is polled on-demand during agent reasoning steps.
    }

    override fun onInterrupt() {
        treeParser.clear()
    }

    override fun onDestroy() {
        stopAgentTask()
        overlayNotch.destroyImmediately()
        serviceScope.cancel()
        super.onDestroy()
        currentInstance = null
        _isServiceActive.value = false
        treeParser.clear()
    }

    fun captureScreenDump(): ScreenDump {
        if (!AgentPreferences(this).isAgentEnabled()) {
            return ScreenDump(packageName = "", formattedContent = "", elementCount = 0)
        }
        val root = rootInActiveWindow
        return treeParser.parseScreenTree(root)
    }

    fun startAgentTaskFromOverlay(prompt: String) {
        val prefs = AgentPreferences(this)
        if (!prefs.isAgentEnabled()) {
            overlayNotch.hide()
            return
        }
        val apiKey = prefs.getGroqApiKey().trim()
        if (apiKey.isBlank()) {
            overlayNotch.showOrUpdate(
                stepNumber = 1,
                liveDetail = "API Anahtarı Yok",
                state = AgentOverlayNotchManager.NotchState.FAILED
            )
            return
        }

        activeOverlayJob?.cancel()

        val remoteDataSource = GroqRemoteDataSource(
            apiKeyProvider = { apiKey },
            preferredModelProvider = { GroqRemoteDataSource.DEFAULT_GROQ_MODEL }
        )
        val brain: AgentBrain = GroqAgentBrain(remoteDataSource, modelName = GroqRemoteDataSource.DEFAULT_GROQ_MODEL)
        val collector = AgentDatasetCollector(applicationContext)

        val runner = AgentLoopRunner(
            context = applicationContext,
            agentBrain = brain,
            datasetCollector = collector,
            providerName = "GROQ",
            modelName = GroqRemoteDataSource.DEFAULT_GROQ_MODEL
        )

        activeOverlayJob = serviceScope.launch {
            runner.runAgent(prompt).collect {
                // AgentLoopRunner updates overlayNotch automatically
            }
        }
    }

    fun stopAgentTask() {
        activeOverlayJob?.cancel()
        activeOverlayJob = null
        treeParser.clear()
        AgentViewModel.getInstance()?.stopTask()
        if (AgentPreferences(this).isAgentEnabled()) {
            overlayNotch.showIdle()
        } else {
            overlayNotch.hide()
        }
    }

    companion object {
        @Volatile
        private var currentInstance: WeakReference<TarsAccessibilityService>? = null

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        fun getInstance(): TarsAccessibilityService? = currentInstance?.get()

        fun isConnected(): Boolean = currentInstance?.get() != null
    }
}
