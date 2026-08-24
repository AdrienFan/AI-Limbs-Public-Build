package com.ai.assistance.operit.ui.features.chat.components.style.common

import androidx.compose.ui.graphics.Color

data class LanerChatPriorityVisuals(
    val backgroundColor: Color,
    val textColor: Color,
)

fun resolveLanerChatPriorityVisuals(
    priority: String,
    defaultBackgroundColor: Color,
    defaultTextColor: Color,
): LanerChatPriorityVisuals =
    when (priority.trim().uppercase()) {
        "HIGH" -> LanerChatPriorityVisuals(Color(0xFFC74B50), Color.White)
        "LOW" -> LanerChatPriorityVisuals(Color(0xFF3F8A62), Color.White)
        else -> LanerChatPriorityVisuals(defaultBackgroundColor, defaultTextColor)
    }
