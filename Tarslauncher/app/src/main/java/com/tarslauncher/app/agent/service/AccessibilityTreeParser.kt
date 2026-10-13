package com.tarslauncher.app.agent.service

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.tarslauncher.app.agent.domain.model.ScreenDump

class AccessibilityTreeParser {

    private val activeNodeMap = HashMap<Int, AccessibilityNodeInfo>()

    @Synchronized
    fun getNode(id: Int): AccessibilityNodeInfo? = activeNodeMap[id]

    @Synchronized
    @Suppress("DEPRECATION")
    fun parseScreenTree(rootNode: AccessibilityNodeInfo?): ScreenDump {
        recycleOldNodes()

        if (rootNode == null) {
            return ScreenDump(
                packageName = "unknown",
                formattedContent = "UYARI: Ekran okunamadı (root pencere boş veya kilitli).",
                elementCount = 0
            )
        }

        val packageName = rootNode.packageName?.toString() ?: "unknown"
        val stringBuilder = StringBuilder()
        stringBuilder.append("PAKET: ").append(packageName).append("\n")

        var elementCounter = 0
        val screenBounds = Rect()

        fun traverse(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > 18 || elementCounter >= 100) return false

            val isVisible = node.isVisibleToUser
            val text = node.text?.toString()?.trim()?.take(70).orEmpty()
            val contentDesc = node.contentDescription?.toString()?.trim()?.take(70).orEmpty()
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable

            val isRelevant = isVisible && (isClickable || isEditable || isScrollable || text.isNotEmpty() || contentDesc.isNotEmpty())

            if (isRelevant) {
                elementCounter++
                activeNodeMap[elementCounter] = node

                node.getBoundsInScreen(screenBounds)

                val attributes = buildList {
                    if (isClickable) add("tık")
                    if (isEditable) add("yazılabilir")
                    if (isScrollable) add("kaydırılabilir")
                }.joinToString(",")

                stringBuilder.append("[$elementCounter] ")
                if (text.isNotEmpty()) {
                    stringBuilder.append("\"").append(text).append("\" ")
                }
                if (contentDesc.isNotEmpty() && contentDesc != text) {
                    stringBuilder.append("(açıklama: ").append(contentDesc).append(") ")
                }
                if (attributes.isNotEmpty()) {
                    stringBuilder.append("<").append(attributes).append(">")
                }
                stringBuilder.append("\n")
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val isChildRetained = traverse(child, depth + 1)
                    // If on API < 34 and child was not retained in activeNodeMap, recycle it
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !isChildRetained) {
                        runCatching { child.recycle() }
                    }
                }
            }

            return isRelevant
        }

        val isRootRetained = traverse(rootNode, 0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !isRootRetained) {
            runCatching { rootNode.recycle() }
        }

        return ScreenDump(
            packageName = packageName,
            formattedContent = stringBuilder.toString(),
            elementCount = elementCounter
        )
    }

    @Synchronized
    fun clear() {
        recycleOldNodes()
    }

    @Suppress("DEPRECATION")
    private fun recycleOldNodes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activeNodeMap.values.forEach { node ->
                runCatching { node.recycle() }
            }
        }
        activeNodeMap.clear()
    }
}
