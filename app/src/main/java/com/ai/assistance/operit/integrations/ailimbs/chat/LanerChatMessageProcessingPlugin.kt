package com.ai.assistance.operit.integrations.ailimbs.chat

import com.ai.assistance.operit.core.chat.plugins.MessageProcessingController
import com.ai.assistance.operit.core.chat.plugins.MessageProcessingExecution
import com.ai.assistance.operit.core.chat.plugins.MessageProcessingHookParams
import com.ai.assistance.operit.core.chat.plugins.MessageProcessingPlugin
import com.ai.assistance.operit.core.chat.plugins.MessageProcessingPluginRegistry
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsAccessContextService
import com.ai.assistance.operit.plugins.OperitPlugin
import com.ai.assistance.operit.util.stream.stream
import kotlinx.coroutines.CancellationException

object LanerChatPlugin : OperitPlugin {
    override val id: String = "builtin.ai-limbs.laner-chat"

    override fun register() {
        MessageProcessingPluginRegistry.register(LanerChatMessageProcessingPlugin)
    }
}

private object LanerChatMessageProcessingPlugin : MessageProcessingPlugin {
    override val id: String = "builtin.ai-limbs.laner-chat.message-processing"

    override suspend fun createExecutionIfMatched(
        params: MessageProcessingHookParams
    ): MessageProcessingExecution? {
        if (!LanerChatContract.isBridgeProvider(params.chatProviderTypeId)) return null
        val service = LanerChatBridgeService.getInstance(params.context)
        val promptAnchor = AiLimbsAccessContextService(params.context).buildLanerChatPromptAnchor()
        val bridgedMessage = buildString {
            append(promptAnchor)
            append("\n\n[User message]\n")
            append(params.messageContent)
        }
        val pending =
            service.enqueue(
                chatId = params.chatId ?: "__DEFAULT_CHAT__",
                text = bridgedMessage
            )
        return MessageProcessingExecution(
            controller =
                object : MessageProcessingController {
                    override fun cancel() {
                        service.cancelRequest(
                            requestId = pending.request.requestId,
                            reason = "User canceled Laner Bridge chat request"
                        )
                    }
                },
            stream = stream {
                try {
                    emit(pending.reply.await())
                } catch (error: CancellationException) {
                    service.cancelRequest(
                        requestId = pending.request.requestId,
                        reason = "Laner Bridge chat stream was canceled"
                    )
                    throw error
                }
            }
        )
    }
}
