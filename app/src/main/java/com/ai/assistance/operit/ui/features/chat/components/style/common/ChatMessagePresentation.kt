package com.ai.assistance.operit.ui.features.chat.components.style.common

import androidx.compose.ui.graphics.Color
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessagePresentation
import com.ai.assistance.operit.data.model.ChatMessageSemanticTone

internal data class ChatMessagePresentationColors(
    val background: Color,
    val text: Color,
)

internal fun resolveUserMessagePresentationColors(
    message: ChatMessage,
    fallbackBackground: Color,
    fallbackText: Color,
): ChatMessagePresentationColors =
    when (ChatMessagePresentation.semanticTone(message.presentationJson)) {
        ChatMessageSemanticTone.DANGER ->
            ChatMessagePresentationColors(
                background = Color(0xFF6F3038),
                text = Color(0xFFFFF4F4),
            )
        ChatMessageSemanticTone.INFO ->
            ChatMessagePresentationColors(
                background = fallbackBackground,
                text = fallbackText,
            )
        ChatMessageSemanticTone.SUCCESS ->
            ChatMessagePresentationColors(
                background = Color(0xFF2F6848),
                text = Color(0xFFF2FFF6),
            )
        ChatMessageSemanticTone.WARNING ->
            ChatMessagePresentationColors(
                background = Color(0xFF7A5A24),
                text = Color(0xFFFFF8E8),
            )
        ChatMessageSemanticTone.NEUTRAL, null ->
            ChatMessagePresentationColors(
                background = fallbackBackground,
                text = fallbackText,
            )
    }
