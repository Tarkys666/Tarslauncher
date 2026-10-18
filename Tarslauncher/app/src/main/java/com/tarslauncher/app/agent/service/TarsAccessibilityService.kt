package com.tarslauncher.app.agent.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.tarslauncher.app.agent.data.AgentPreferences
import com.tarslauncher.app.agent.data.GroqAgentBrain
import com.tarslauncher.app.agent.data.GroqRemoteDataSource
import com.tarslauncher.app.agent.domain.engine.AgentBrain
import com.tarslauncher.app.agent.runner.AgentLoopRunner
import com.tarslauncher.app.agent.ui.AgentViewModel
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hosts the Dynamic Island overlay capsule (`TYPE_ACCESSIBILITY_OVERLAY`) and
 * routes quick workspace prompts to the TARS Workspace + LLM engine.
 */
class TarsAccessibilityService : AccessibilityService() {

    val overlayNotch: AgentOverlayNotchManager by lazy { AgentOverlayNotchManager(this) }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeOverlayJob: Job? = null
    private var fullscreenCheckJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentInstance = WeakReference(this)
        _isServiceActive.value = true

        try {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        } catch (e: Exception) {
            Log.w("TarsAccessibility", "Could not configure serviceInfo flags", e)
        }

        try {
            overlayNotch.onStopRequested = {
                stopAgentTask()
            }

            overlayNotch.onTaskSubmitted = { prompt ->
                startAgentTaskFromOverlay(prompt)
            }

            val prefs = AgentPreferences(this)
            if (prefs.isAgentEnabled()) {
                overlayNotch.showIdle()
            } else {
                overlayNotch.hide()
            }
        } catch (e: Exception) {
            Log.e("TarsAccessibility", "Failed to initialize overlay in onServiceConnected", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopAgentTask()
        overlayNotch.destroyImmediately()
        currentInstance = null
        _isServiceActive.value = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                scheduleFullscreenCheck()
            }
        }
    }

    private fun scheduleFullscreenCheck() {
        fullscreenCheckJob?.cancel()
        fullscreenCheckJob = serviceScope.launch {
            delay(150L)
            runCatching {
                checkFullscreenState()
            }.onFailure { e ->
                Log.w("TarsAccessibility", "Fullscreen check error suppressed", e)
            }
        }
    }

    private fun checkFullscreenState() {
        val windowList = runCatching { windows }.getOrNull() ?: return
        val (screenWidth, screenHeight) = getScreenDimensions()

        var hasVisibleStatusBar = false
        var isAppFullScreen = false

        for (window in windowList) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && window.isInPictureInPictureMode) {
                continue
            }

            val bounds = Rect()
            runCatching { window.getBoundsInScreen(bounds) }.getOrNull() ?: continue

            if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                if (bounds.top == 0 && bounds.height() in 1..(screenHeight / 4) && bounds.width() >= (screenWidth / 2)) {
                    hasVisibleStatusBar = true
                }
            } else if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && (window.isFocused || window.isActive)) {
                if (bounds.top == 0 && bounds.height() >= (screenHeight * 0.90)) {
                    isAppFullScreen = true
                }
            }
        }

        val isFullscreen = isAppFullScreen && !hasVisibleStatusBar
        overlayNotch.setFullscreenSuppressed(isFullscreen)
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopAgentTask()
        overlayNotch.destroyImmediately()
        serviceScope.cancel()
        super.onDestroy()
        currentInstance = null
        _isServiceActive.value = false
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
        val runner = AgentLoopRunner(
            context = applicationContext,
            agentBrain = brain
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
