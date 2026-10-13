package com.tarslauncher.app.agent.domain.model

data class ScreenDump(
    val packageName: String,
    val formattedContent: String,
    val elementCount: Int
)
