package com.tarslauncher.app.agent.domain.safety

import com.tarslauncher.app.agent.domain.model.AgentAction

/**
 * Evaluates whether a proposed agent action poses risks (financial, deletion, external communication)
 * and requires explicit user consent before proceeding.
 */
class SafetyGate {

    private val sensitiveTokens = setOf(
        // Destructive
        "sil", "delete", "kaldır", "uninstall", "sıfırla", "reset", "wipe", "format",
        // Financial & transactions
        "öde", "pay", "buy", "sipariş", "order", "transfer", "iban",
        // Outgoing actions
        "gönder", "send", "paylaş", "share",
        // Permissions & admin
        "izin", "permission", "yönetici", "admin", "erişim", "güvenlik", "security"
    )

    private val multiWordPhrases = listOf(
        "satın al",
        "hesabı sil"
    )

    private val sensitiveAppKeywords = listOf(
        "bank", "cüzdan", "wallet", "binance", "paribu", "btcturk",
        "ayar", "setting", "installer", "packageinstaller", "play store", "google play"
    )

    private val confirmationButtons = setOf(
        "onayla", "devam", "tamam", "evet", "ileri", "öde", "sil", "kabul"
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
                val lower = action.appName.lowercase()
                if (sensitiveAppKeywords.any { lower.contains(it) }) {
                    return true to "Güvenlik Uyarısı: '${action.appName}' hassas/sistem uygulaması. Açılması onaylanıyor mu?"
                }
            }

            is AgentAction.Tap -> {
                val matchedLine = findTargetLine(action.targetId, screenContent)
                val lineIsSensitive = matchedLine != null && isSensitive(matchedLine)
                val screenIsSensitive = isSensitive(screenContent)
                val isConfirmButton = matchedLine != null && confirmationButtons.any { matchedLine.contains(it, ignoreCase = true) }

                if (lineIsSensitive || (screenIsSensitive && isConfirmButton)) {
                    val desc = matchedLine?.trim() ?: "Hedef ID: ${action.targetId}"
                    return true to "Güvenlik Uyarısı: '$desc' işlemine dokunulmak isteniyor. Devam edilsin mi?"
                }
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

    private fun findTargetLine(targetId: Int, screenContent: String): String? {
        val targetPrefix = "[$targetId]"
        return screenContent.lineSequence()
            .firstOrNull { it.trimStart().startsWith(targetPrefix) }
    }

    private fun isSensitive(text: String): Boolean {
        val lower = text.lowercase()
        if (multiWordPhrases.any { lower.contains(it) }) return true

        val words = lower.split(nonWordRegex).filter { it.isNotBlank() }
        return words.any { sensitiveTokens.contains(it) }
    }
}
