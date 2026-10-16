package com.tarslauncher.app.agent.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.tarslauncher.app.agent.domain.model.ScreenDump
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TarsAccessibilityService : AccessibilityService() {

    val treeParser = AccessibilityTreeParser()
    val gestureDispatcher = GestureDispatcher { this }
    val overlayNotch: AgentOverlayNotchManager by lazy { AgentOverlayNotchManager(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentInstance = this
        _isServiceActive.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
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
        overlayNotch.hide()
        super.onDestroy()
        currentInstance = null
        _isServiceActive.value = false
        treeParser.clear()
    }

    fun captureScreenDump(): ScreenDump {
        val root = rootInActiveWindow
        return treeParser.parseScreenTree(root)
    }

    companion object {
        @Volatile
        private var currentInstance: TarsAccessibilityService? = null

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        fun getInstance(): TarsAccessibilityService? = currentInstance

        fun isConnected(): Boolean = currentInstance != null
    }
}
