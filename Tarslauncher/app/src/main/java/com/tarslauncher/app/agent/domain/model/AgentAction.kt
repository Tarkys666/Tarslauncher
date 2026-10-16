package com.tarslauncher.app.agent.domain.model

/**
 * Represents an action determined by the AI Agent to interact with the device.
 */
sealed interface AgentAction {
    val thought: String
    val stepIndex: Int
        get() = -1

    fun toOperationalSignature(): String = when (this) {
        is OpenApp -> "OpenApp($appName)"
        is OpenUrl -> "OpenUrl($url)"
        is Tap -> "Tap($targetId)"
        is LongPress -> "LongPress($targetId)"
        is TypeText -> "TypeText($targetId, $text, pressEnter=$pressEnter)"
        is Scroll -> "Scroll($direction)"
        is Back -> "Back"
        is Home -> "Home"
        is Recents -> "Recents"
        is Notifications -> "Notifications"
        is QuickSettings -> "QuickSettings"
        is ClipboardSet -> "ClipboardSet(${text.take(30)})"
        is Wait -> "Wait(${durationMs}ms)"
        is AskUser -> "AskUser($question)"
        is Done -> "Done"
        is Unknown -> "Unknown($rawAction)"
    }

    data class OpenApp(
        override val thought: String,
        val appName: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class OpenUrl(
        override val thought: String,
        val url: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class Tap(
        override val thought: String,
        val targetId: Int,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class LongPress(
        override val thought: String,
        val targetId: Int,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class TypeText(
        override val thought: String,
        val targetId: Int,
        val text: String,
        val pressEnter: Boolean = true,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class Scroll(
        override val thought: String,
        val direction: Direction,
        override val stepIndex: Int = -1
    ) : AgentAction {
        enum class Direction {
            UP,
            DOWN;

            companion object {
                fun fromString(value: String): Direction =
                    if (value.equals("up", ignoreCase = true)) UP else DOWN
            }
        }
    }

    data class Back(
        override val thought: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class Home(
        override val thought: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class Recents(
        override val thought: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class Notifications(
        override val thought: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class QuickSettings(
        override val thought: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class ClipboardSet(
        override val thought: String,
        val text: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class Wait(
        override val thought: String,
        val durationMs: Long,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class AskUser(
        override val thought: String,
        val question: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class Done(
        override val thought: String,
        val summary: String,
        override val stepIndex: Int = -1
    ) : AgentAction

    data class Unknown(
        override val thought: String,
        val rawAction: String,
        override val stepIndex: Int = -1
    ) : AgentAction
}
