package com.tarslauncher.app.agent.domain.model

data class ChatMessage(
    val role: Role,
    val content: String
) {
    enum class Role(val serializedName: String) {
        USER("user"),
        MODEL("model")
    }
}
