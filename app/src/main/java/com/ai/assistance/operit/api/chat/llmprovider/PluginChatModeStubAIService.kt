package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.util.stream.Stream

/**
 * Non-network AIService placeholder for a plugin-owned chat mode.
 *
 * Composer traffic must be consumed by the generic chat-mode extension before it reaches the model
 * provider layer. Reaching [sendMessage] is therefore an invariant violation.
 */
class PluginChatModeStubAIService(
    private val config: ModelConfigData
) : AIService {
    override val inputTokenCount: Long = 0L
    override val cachedInputTokenCount: Long = 0L
    override val outputTokenCount: Long = 0L
    override val providerModel: String =
        config.apiProviderTypeId + ":" + config.modelName

    override fun resetTokenCounts() = Unit
    override fun cancelStreaming() = Unit

    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> =
        Result.success(
            listOf(
                ModelOption(
                    id = config.modelName.ifBlank { config.id },
                    name = config.name
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
            "Plugin chat mode message bypassed the chat-mode submit extension"
        )
    }

    override suspend fun testConnection(
        context: Context,
        onUsageReported: (suspend (ProviderUsageSnapshot, attempt: Int) -> Unit)?
    ): Result<String> =
        if (PluginPlatformKernel.matchesBusinessChatModeConfig(config)) {
            Result.success("Plugin chat mode runtime is active")
        } else {
            Result.failure(IllegalStateException("Plugin chat mode runtime is unavailable"))
        }

    override suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?
    ): Long =
        chatHistory.sumOf { turn -> (turn.content.length.toLong() + 3L) / 4L }
}

