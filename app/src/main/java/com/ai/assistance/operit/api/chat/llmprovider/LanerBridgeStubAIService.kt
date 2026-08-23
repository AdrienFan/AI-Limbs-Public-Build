package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeManager
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgePhase
import com.ai.assistance.operit.integrations.ailimbs.chat.LanerChatContract
import com.ai.assistance.operit.util.stream.Stream

/**
 * Configuration/readiness adapter for Laner Bridge chat.
 *
 * Message bodies must be intercepted by LanerChatMessageProcessingPlugin. Reaching sendMessage is
 * an invariant violation and fails explicitly; it must never fall through to a network provider.
 */
class LanerBridgeStubAIService : AIService {
    override val inputTokenCount: Long = 0L
    override val cachedInputTokenCount: Long = 0L
    override val outputTokenCount: Long = 0L
    override val providerModel: String = LanerChatContract.PROVIDER_MODEL

    override fun resetTokenCounts() = Unit

    override fun cancelStreaming() = Unit

    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> =
        Result.success(
            listOf(
                ModelOption(
                    id = LanerChatContract.MODEL_ID,
                    name = "Laner Chat Bridge"
                )
            )
        )

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Long, cachedInput: Long, output: Long) -> Unit,
        onUsageReported: (suspend (ProviderUsageSnapshot, attempt: Int) -> Unit)?,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean,
        statsCategory: TokenStatCategory?
    ): Stream<String> {
        throw IllegalStateException(
            "Laner Bridge message bypassed LanerChatMessageProcessingPlugin"
        )
    }

    override suspend fun testConnection(
        context: Context,
        onUsageReported: (suspend (ProviderUsageSnapshot, attempt: Int) -> Unit)?
    ): Result<String> {
        val bridgeState = AiLimbsBridgeManager.runtimeState.value
        return if (bridgeState.phase == AiLimbsBridgePhase.ONLINE) {
            Result.success("AI Limbs Bridge is online")
        } else {
            Result.failure(
                IllegalStateException("AI Limbs Bridge is ${bridgeState.phase.name.lowercase()}")
            )
        }
    }

    override suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?
    ): Long =
        chatHistory.sumOf { turn -> (turn.content.length.toLong() + 3L) / 4L }
}
