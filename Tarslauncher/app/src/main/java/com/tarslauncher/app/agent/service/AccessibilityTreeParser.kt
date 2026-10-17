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
            if (node == null || depth > 18 || elementCounter >= 90) return false

            val isVisible = node.isVisibleToUser
            if (!isVisible) {
                for (i in 0 until node.childCount) {
                    val reachedLimit = traverse(node.getChild(i), depth + 1)
                    if (reachedLimit) return true
                }
                return false
            }

            val isPassword = node.isPassword
            val rawText = if (isPassword) "•••••• [ŞİFRE]" else node.text?.toString()?.trim().orEmpty()
            val text = if (isPassword) rawText else scrubSensitiveTokens(rawText).take(60)

            val rawDesc = if (isPassword) "" else node.contentDescription?.toString()?.trim().orEmpty()
            val contentDesc = if (isPassword) "" else scrubSensitiveTokens(rawDesc).take(60)

            val rawHint = if (isPassword) "" else node.hintText?.toString()?.trim().orEmpty()
            val hintText = if (isPassword) "" else scrubSensitiveTokens(rawHint).take(45)

            val isClickable = node.isClickable
            val isLongClickable = node.isLongClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable
            val isChecked = node.isCheckable && node.isChecked
            val isSelected = node.isSelected

            val isRelevant = isClickable || isLongClickable || isEditable || isScrollable ||
                text.isNotEmpty() || contentDesc.isNotEmpty() || hintText.isNotEmpty()

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

    private fun scrubSensitiveTokens(input: String): String {
        if (input.length < 4) return input

        var result = input
        val hasDigits = input.any { it.isDigit() }

        if (input.contains("gsk_")) {
            result = result.replace(REGEX_GROQ_KEY, "gsk_***[GİZLİ]***")
        }
        if (input.contains("AIza")) {
            result = result.replace(REGEX_AIZA_KEY, "AIza***[GİZLİ]***")
        }
        if (input.contains("eyJ")) {
            result = result.replace(REGEX_JWT, "***[JWT_GİZLİ]***")
        }

        if (hasDigits) {
            result = result
                .replace(REGEX_IBAN, "***[IBAN_GİZLİ]***")
                .replace(REGEX_CREDIT_CARD, "***[KART_GİZLİ]***")
                .replace(REGEX_TCKN, "***[TCKN_GİZLİ]***")
                .replace(REGEX_OTP, "***[OTP_GİZLİ]***")
                .replace(REGEX_CVV, "***[CVV_GİZLİ]***")
        }
        return result
    }

    companion object {
        private val REGEX_GROQ_KEY = Regex("""gsk_[A-Za-z0-9_-]{8,}""")
        private val REGEX_AIZA_KEY = Regex("""AIza[A-Za-z0-9_-]{10,}""")
        private val REGEX_JWT = Regex("""eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}""")
        private val REGEX_IBAN = Regex("""\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]){12,30}\b""", RegexOption.IGNORE_CASE)
        private val REGEX_CREDIT_CARD = Regex("""\b(?:\d{4}[ -]?){3}\d{4}\b|\b\d{15,19}\b""")
        private val REGEX_TCKN = Regex("""\b[1-9]\d{10}\b""")
        private val REGEX_OTP = Regex("""(?i)\b(?:otp|kod|code|doğrulama|şifre|onay|pin)[\s:=#-]*\d{4,8}\b|\b\d{4,8}\s*(?:kodu|kod|otp|pin)\b""")
        private val REGEX_CVV = Regex("""(?i)\b(?:cvv|cvc|güvenlik\s*kodu)[\s:=]*\d{3,4}\b""")
    }
}

