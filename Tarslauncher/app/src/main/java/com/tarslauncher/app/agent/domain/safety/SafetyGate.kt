package com.tarslauncher.app.agent.domain.safety

import com.tarslauncher.app.agent.domain.model.AgentAction
import java.util.Locale

/**
 * Evaluates whether a proposed agent action poses risks (financial, deletion, external communication)
 * and requires explicit user consent before proceeding.
 */
class SafetyGate {

    private val trLocale = Locale.forLanguageTag("tr-TR")

    private val highRiskTokens = setOf(
        // Destructive
        "sil", "delete", "kaldır", "uninstall", "sıfırla", "reset", "wipe", "format",
        // Financial & transactions
        "öde", "pay", "buy", "sipariş", "order", "transfer", "iban"
    )

    private val sensitiveTokens = highRiskTokens + setOf(
        // Outgoing actions
        "gönder", "send", "paylaş", "share",
        // Permissions & admin
        "izin", "permission", "yönetici", "admin", "erişim", "güvenlik", "security"
    )

    private val multiWordPhrases = listOf(
        "satın al",
        "hesabı sil",
        "kalıcı olarak sil",
        "delete account"
    )

    private val sensitiveAppKeywords = listOf(
        "bank", "cüzdan", "wallet", "binance", "paribu", "btcturk",
        "ayar", "setting", "installer", "packageinstaller", "play store", "google play"
    )

    /**
     * Checks if a package belongs to a sensitive application (banking, settings, installer, etc.)
     * for pre-flight privacy gating.
     */
    fun isSensitiveAppPackage(packageName: String): Boolean {
        if (packageName.isBlank() || packageName == "unknown") return false
        val lowerPkg = packageName.lowercase(Locale.ROOT)
        return sensitiveAppKeywords.any { kw -> lowerPkg.contains(kw) } || lowerPkg.contains("vending")
    }

    private val confirmationButtons = setOf(
        "onayla", "evet", "öde", "sil", "kabul", "confirm", "accept", "submit", "yes"
    )

    private val nonWordRegex = Regex("""[^\p{L}\p{Nd}]+""")

    /**
     * Checks if the action should be gated behind user confirmation.
     * @param action The pending agent action.
     * @param screenContent The latest raw text dump of the screen.
     * @return Pair of (needsConfirmation: Boolean, reasonPrompt: String)
     */
    fun evaluate(action: AgentAction, screenContent: String): Pair<Boolean, String> {
        when (action) {
            is AgentAction.OpenApp -> {
                val lowerVariants = normalizeVariants(action.appName)
                if (sensitiveAppKeywords.any { kw -> lowerVariants.any { it.contains(kw) } }) {
                    return true to "Güvenlik Uyarısı: '${action.appName}' hassas/sistem uygulaması. Açılması onaylanıyor mu?"
                }
            }

            is AgentAction.Tap -> {
                return evaluateTargetNode(action.targetId, screenContent)
            }

            is AgentAction.LongPress -> {
                return evaluateTargetNode(action.targetId, screenContent)
            }

            is AgentAction.TypeText -> {
                val matchedLine = findTargetLine(action.targetId, screenContent)
                if ((matchedLine != null && isSensitive(matchedLine)) || isSensitive(action.text)) {
                    return true to "Güvenlik Uyarısı: '${action.text}' metni hassas bir alana girilecek. Devam edilsin mi?"
                }
            }

            else -> Unit
        }

        return false to ""
    }

    private fun evaluateTargetNode(targetId: Int, screenContent: String): Pair<Boolean, String> {
        val matchedLine = findTargetLine(targetId, screenContent)
        val lineIsSensitive = matchedLine != null && isSensitive(matchedLine)
        val screenIsHighRisk = isHighRiskContext(screenContent)
        val isConfirmButton = matchedLine != null && matchesAnyToken(matchedLine, confirmationButtons)

        if (lineIsSensitive || (screenIsHighRisk && isConfirmButton)) {
            val desc = matchedLine?.trim() ?: "Hedef ID: $targetId"
            return true to "Güvenlik Uyarısı: '$desc' işlemine dokunulmak isteniyor. Devam edilsin mi?"
        }
        return false to ""
    }

    private fun findTargetLine(targetId: Int, screenContent: String): String? {
        val targetPrefix = "[$targetId]"
        return screenContent.lineSequence()
            .firstOrNull { it.trimStart().startsWith(targetPrefix) }
    }

    private fun normalizeVariants(text: String): List<String> {
        val trLower = text.lowercase(trLocale).replace("i\u0307", "i")
        val rootLower = text.lowercase(Locale.ROOT).replace("i\u0307", "i")
        return if (trLower == rootLower) listOf(trLower) else listOf(trLower, rootLower)
    }

    private fun isSensitive(text: String): Boolean {
        val variants = normalizeVariants(text)
        if (multiWordPhrases.any { phrase -> variants.any { it.contains(phrase) } }) return true
        return matchesAnyToken(text, sensitiveTokens)
    }

    private fun isHighRiskContext(text: String): Boolean {
        val variants = normalizeVariants(text)
        if (multiWordPhrases.any { phrase -> variants.any { it.contains(phrase) } }) return true
        return matchesAnyToken(text, highRiskTokens)
    }

    private fun matchesAnyToken(text: String, tokenSet: Set<String>): Boolean {
        for (variant in normalizeVariants(text)) {
            val words = variant.split(nonWordRegex).filter { it.isNotBlank() }
            if (words.any { tokenSet.contains(it) }) return true
        }
        return false
    }
}

