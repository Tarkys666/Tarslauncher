package com.tarslauncher.app.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

class GestureDispatcher(
    private val serviceProvider: () -> AccessibilityService?
) {

    @Suppress("DEPRECATION")
    fun performClick(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        // 1. Try finding a clickable ancestor or the node itself
        var current: AccessibilityNodeInfo? = node
        val parentsToRecycle = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mutableListOf<AccessibilityNodeInfo>()
        } else null

        try {
            while (current != null) {
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                val parent = current.parent
                if (parent != null && parentsToRecycle != null) {
                    parentsToRecycle.add(parent)
                }
                current = parent
            }
        } finally {
            if (parentsToRecycle != null) {
                parentsToRecycle.forEach { runCatching { it.recycle() } }
            }
        }

        // 2. Fallback to physical touch gesture at the node's center coordinate
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            return clickCoordinate(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }

        return false
    }

    fun clickCoordinate(x: Float, y: Float): Boolean {
        val service = serviceProvider() ?: return false
        val clickPath = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 60))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    fun typeText(node: AccessibilityNodeInfo?, text: String, pressEnter: Boolean = true): Boolean {
        if (node == null) return false

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var succeeded = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (!succeeded) {
            // Fallback: Click first to engage software keyboard focus, then retry set text
            performClick(node)
            succeeded = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        if (succeeded && pressEnter) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }

        return succeeded
    }

    fun performScroll(scrollDown: Boolean): Boolean {
        val service = serviceProvider() ?: return false
        val displayMetrics = Resources.getSystem().displayMetrics
        val centerX = displayMetrics.widthPixels / 2f

        val startY: Float
        val endY: Float

        if (scrollDown) {
            // Swipe up to scroll down
            startY = displayMetrics.heightPixels * 0.72f
            endY = displayMetrics.heightPixels * 0.28f
        } else {
            // Swipe down to scroll up
            startY = displayMetrics.heightPixels * 0.28f
            endY = displayMetrics.heightPixels * 0.72f
        }

        val scrollPath = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(scrollPath, 0, 280))
            .build()

        return service.dispatchGesture(gesture, null, null)
    }

    fun performBack(): Boolean {
        val service = serviceProvider() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun performHome(): Boolean {
        val service = serviceProvider() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }
}
