package com.tarslauncher.app.agent.service

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.tarslauncher.app.agent.domain.model.ScreenDump

class AccessibilityTreeParser {

    private val activeNodeMap = HashMap<Int, AccessibilityNodeInfo>()
    private val nodeSummaryMap = HashMap<Int, String>()

    @Synchronized
    fun getNode(id: Int): AccessibilityNodeInfo? = activeNodeMap[id]

    @Synchronized
    fun getNodeSummary(id: Int): String? = nodeSummaryMap[id]

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

        fun traverse(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > 20 || elementCounter >= 130) return false

            val isVisible = node.isVisibleToUser
            val isPassword = node.isPassword
            val text = if (isPassword) "•••••• [ŞİFRE]" else node.text?.toString()?.trim()?.take(80).orEmpty()
            val contentDesc = if (isPassword) "" else node.contentDescription?.toString()?.trim()?.take(80).orEmpty()
            val hintText = if (isPassword) "" else node.hintText?.toString()?.trim()?.take(60).orEmpty()
            val isClickable = node.isClickable
            val isLongClickable = node.isLongClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable
            val isChecked = node.isCheckable && node.isChecked
            val isSelected = node.isSelected

            val isRelevant = isVisible && (
                isClickable || isLongClickable || isEditable || isScrollable ||
                    text.isNotEmpty() || contentDesc.isNotEmpty() || hintText.isNotEmpty()
                )

            if (isRelevant) {
                elementCounter++
                activeNodeMap[elementCounter] = node

                val attributes = buildList {
                    if (isClickable) add("tık")
                    if (isLongClickable) add("uzun-tık")
                    if (isEditable) add("yazılabilir")
                    if (isScrollable) add("kaydırılabilir")
                    if (isChecked) add("seçili")
                    if (isSelected) add("aktif")
                }.joinToString(",")

                val lineBuilder = StringBuilder()
                if (text.isNotEmpty()) {
                    lineBuilder.append("\"").append(text).append("\" ")
                }
                if (contentDesc.isNotEmpty() && contentDesc != text) {
                    lineBuilder.append("(açıklama: ").append(contentDesc).append(") ")
                }
                if (hintText.isNotEmpty() && hintText != text && hintText != contentDesc) {
                    lineBuilder.append("[ipucu: ").append(hintText).append("] ")
                }
                if (attributes.isNotEmpty()) {
                    lineBuilder.append("<").append(attributes).append(">")
                }

                val summary = lineBuilder.toString().trim()
                nodeSummaryMap[elementCounter] = summary
                stringBuilder.append("[$elementCounter] ").append(summary).append("\n")
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
        nodeSummaryMap.clear()
    }
}

