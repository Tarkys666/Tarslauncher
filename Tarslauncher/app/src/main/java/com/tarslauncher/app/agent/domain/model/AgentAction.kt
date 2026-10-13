package com.tarslauncher.app.agent.domain.model

/**
 * Represents an action determined by the AI Agent to interact with the device.
 */
sealed interface AgentAction {
    val thought: String

    fun toOperationalSignature(): String = when (this) {
        is OpenApp -> "OpenApp($appName)"
        is Tap -> "Tap($targetId)"
        is TypeText -> "TypeText($targetId, $text)"
        is Scroll -> "Scroll($direction)"
        is Back -> "Back"
        is Home -> "Home"
        is Wait -> "Wait($durationMs)"
        is AskUser -> "AskUser($question)"
        is Done -> "Done"
        is Unknown -> "Unknown($rawAction)"
    }

    data class OpenApp(
        override val thought: String,
        val appName: String
    ) : AgentAction

    data class Tap(
        override val thought: String,
        val targetId: Int
    ) : AgentAction

    data class TypeText(
        override val thought: String,
        val targetId: Int,
        val text: String
    ) : AgentAction

    data class Scroll(
        override val thought: String,
        val direction: Direction
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
        override val thought: String
    ) : AgentAction

    data class Home(
        override val thought: String
    ) : AgentAction

    data class Wait(
        override val thought: String,
        val durationMs: Long
    ) : AgentAction

    data class AskUser(
        override val thought: String,
        val question: String
    ) : AgentAction

    data class Done(
        override val thought: String,
        val summary: String
    ) : AgentAction

    data class Unknown(
        override val thought: String,
        val rawAction: String
    ) : AgentAction
}
