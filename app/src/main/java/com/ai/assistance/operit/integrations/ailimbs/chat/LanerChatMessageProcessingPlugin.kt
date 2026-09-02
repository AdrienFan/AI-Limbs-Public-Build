package com.ai.assistance.operit.integrations.ailimbs.chat

import com.ai.assistance.operit.core.chat.plugins.MessageProcessingController
import com.ai.assistance.operit.core.chat.plugins.MessageProcessingExecution
import com.ai.assistance.operit.core.chat.plugins.MessageProcessingHookParams
import com.ai.assistance.operit.core.chat.plugins.MessageProcessingPlugin
import com.ai.assistance.operit.core.chat.plugins.MessageProcessingPluginRegistry
import com.ai.assistance.operit.util.stream.stream
import kotlinx.coroutines.CancellationException

object LanerChatPlugin {

    fun register() {
        MessageProcessingPluginRegistry.register(LanerChatMessageProcessingPlugin)
    }
}

/**
 * Legacy compatibility adapter for old model-runtime send paths.
 *
 * V0.6.4 Laner UI does not use this adapter: it writes directly to the durable mailbox and the
 * Assistant Turn Scheduler owns batching/replies. Keep this only so older internal call sites fail
 * safely into the existing request stream without reinjecting queue/scheduling policy into context.
 */
private object LanerChatMessageProcessingPlugin : MessageProcessingPlugin {
    override val id: String = "builtin.ai-limbs.laner-chat.message-processing"

    override suspend fun createExecutionIfMatched(
        params: MessageProcessingHookParams
    ): MessageProcessingExecution? {
        if (!LanerChatContract.isBridgeProvider(params.chatProviderTypeId)) return null
        val service = LanerChatBridgeService.getInstance(params.context)
        val chatId = params.chatId ?: "__DEFAULT_CHAT__"
        val priority = LanerChatDraftPriorityStore.consume(chatId)
        val bridgedMessage = params.rawUserText ?: params.messageContent
        val pending =
            service.enqueue(
                chatId = chatId,
                text = bridgedMessage,
                attachments = params.attachments,
                priority = priority
            )
        return MessageProcessingExecution(
            controller =
                object : MessageProcessingController {
                    override fun cancel() {
                        service.cancelLegacyExchange(
                            requestId = pending.request.requestId,
                            reason = "User canceled Laner Bridge chat request"
                        )
                    }
                },
            stream = stream {
                try {
                    emit(pending.reply.await())
                } catch (error: CancellationException) {
                    service.cancelLegacyExchange(
                        requestId = pending.request.requestId,
                        reason = "Laner Bridge chat stream was canceled"
                    )
                    throw error
                }
            }
        )
    }
}
