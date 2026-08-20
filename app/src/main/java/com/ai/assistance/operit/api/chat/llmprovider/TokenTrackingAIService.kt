package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.TokenUsageRecordEntity
import com.ai.assistance.operit.data.model.TokenUsageRecordSource
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.data.stats.TokenStatStatus
import com.ai.assistance.operit.data.stats.TokenUsageRepository
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.RevisableTextStream
import com.ai.assistance.operit.util.stream.SharedStream
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.StreamCollector
import com.ai.assistance.operit.util.stream.TextStreamEvent
import com.ai.assistance.operit.util.stream.TextStreamEventCarrier
import com.ai.assistance.operit.util.stream.TimeoutException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext

/** Adds one compact Room statistics row around every logical [AIService] request. */
class TokenTrackingAIService(
    private val delegate: AIService,
    context: Context,
    private val configId: String,
) : AIService {
    private val repository = TokenUsageRepository.getInstance(context.applicationContext)

    override val inputTokenCount: Long get() = delegate.inputTokenCount
    override val cachedInputTokenCount: Long get() = delegate.cachedInputTokenCount
    override val outputTokenCount: Long get() = delegate.outputTokenCount
    override val providerModel: String get() = delegate.providerModel

    override fun resetTokenCounts() = delegate.resetTokenCounts()
    override fun cancelStreaming() = delegate.cancelStreaming()
    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> =
        delegate.getModelsList(context)

    override suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?,
    ): Long = delegate.calculateInputTokens(chatHistory, availableTools)

    override fun release() = delegate.release()

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
        statsCategory: TokenStatCategory?,
    ): Stream<String> {
        val request = RequestTracker(
            configId = configId,
            providerModel = providerModel,
            category = statsCategory ?: TokenStatCategory.OTHER,
        )
        val inner = try {
            delegate.sendMessage(
                context = context,
                chatHistory = chatHistory,
                modelParameters = modelParameters,
                enableThinking = enableThinking,
                stream = stream,
                availableTools = availableTools,
                preserveThinkInHistory = preserveThinkInHistory,
                onTokensUpdated = onTokensUpdated,
                onUsageReported = { usage, attempt ->
                    request.onUsage(usage, attempt)
                    forwardUsageObserver(onUsageReported, usage, attempt)
                },
                onNonFatalError = onNonFatalError,
                enableRetry = enableRetry,
                statsCategory = statsCategory,
            )
        } catch (t: Throwable) {
            persist(request.finish(classify(t)))
            throw t
        }
        return wrapStream(inner, request)
    }

    override suspend fun testConnection(
        context: Context,
        onUsageReported: (suspend (ProviderUsageSnapshot, attempt: Int) -> Unit)?,
    ): Result<String> {
        val request =
            RequestTracker(configId, providerModel, TokenStatCategory.CONNECTION_TEST)
        return try {
            val result = delegate.testConnection(context) { usage, attempt ->
                request.onUsage(usage, attempt)
                forwardUsageObserver(onUsageReported, usage, attempt)
            }
            persist(
                request.finish(
                    result.exceptionOrNull()?.let(::classify) ?: TokenStatStatus.COMPLETED
                )
            )
            result
        } catch (e: CancellationException) {
            persist(request.finish(classify(e)))
            throw e
        } catch (t: Throwable) {
            persist(request.finish(classify(t)))
            Result.failure(t)
        }
    }

    private suspend fun forwardUsageObserver(
        observer: (suspend (ProviderUsageSnapshot, Int) -> Unit)?,
        usage: ProviderUsageSnapshot,
        attempt: Int,
    ) {
        val callback = observer ?: return
        try {
            callback(usage, attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "usage observer failed", e)
        }
    }

    private fun wrapStream(inner: Stream<String>, request: RequestTracker): Stream<String> =
        if (inner is TextStreamEventCarrier) {
            TrackingRevisableStream(inner, inner.eventChannel, request, repository)
        } else {
            TrackingStream(inner, request, repository)
        }

    private class TrackingStream(
        private val inner: Stream<String>,
        private val request: RequestTracker,
        private val repository: TokenUsageRepository,
    ) : Stream<String> {
        override val isLocked: Boolean get() = inner.isLocked
        override val bufferedCount: Int get() = inner.bufferedCount
        override suspend fun lock() = inner.lock()
        override suspend fun unlock() = inner.unlock()
        override fun clearBuffer() = inner.clearBuffer()

        override suspend fun collect(collector: StreamCollector<String>) {
            try {
                inner.collect { value ->
                    if (value.isNotEmpty()) request.onFirstToken()
                    collector.emit(value)
                }
            } catch (t: Throwable) {
                persist(repository, request, request.finish(classify(t)))
                throw t
            }
            persist(repository, request, request.finish(TokenStatStatus.COMPLETED))
        }
    }

    private class TrackingRevisableStream(
        private val inner: Stream<String>,
        override val eventChannel: SharedStream<TextStreamEvent>,
        private val request: RequestTracker,
        private val repository: TokenUsageRepository,
    ) : RevisableTextStream {
        override val isLocked: Boolean get() = inner.isLocked
        override val bufferedCount: Int get() = inner.bufferedCount
        override suspend fun lock() = inner.lock()
        override suspend fun unlock() = inner.unlock()
        override fun clearBuffer() = inner.clearBuffer()

        override suspend fun collect(collector: StreamCollector<String>) {
            try {
                inner.collect { value ->
                    if (value.isNotEmpty()) request.onFirstToken()
                    collector.emit(value)
                }
            } catch (t: Throwable) {
                persist(repository, request, request.finish(classify(t)))
                throw t
            }
            persist(repository, request, request.finish(TokenStatStatus.COMPLETED))
        }
    }

    private suspend fun persist(record: TokenUsageRecordEntity) = persist(repository, record)

    private class RequestTracker(
        private val configId: String,
        private val providerModel: String,
        private val category: TokenStatCategory,
    ) {
        private val startedAtMs = System.currentTimeMillis()
        private val lock = Any()
        private val attempts = linkedMapOf<Int, ProviderUsageSnapshot>()
        private var firstTokenAtMs: Long? = null
        private val finished = AtomicBoolean(false)

        fun onUsage(usage: ProviderUsageSnapshot, attempt: Int) {
            synchronized(lock) {
                val key = attempt.coerceAtLeast(1)
                attempts[key] = merge(attempts[key], usage)
            }
        }

        fun onFirstToken() {
            synchronized(lock) {
                if (firstTokenAtMs == null) firstTokenAtMs = System.currentTimeMillis()
            }
        }

        fun finish(status: TokenStatStatus): TokenUsageRecordEntity {
            val endedAtMs = System.currentTimeMillis()
            val snapshots = synchronized(lock) { attempts.values.toList() }
            val firstToken = synchronized(lock) { firstTokenAtMs }
            val separator = providerModel.indexOf(':')
            require(separator > 0 && separator < providerModel.lastIndex) {
                "provider:model is required for token usage events"
            }
            return TokenUsageRecordEntity(
                occurredAtMs = startedAtMs,
                source = TokenUsageRecordSource.REQUEST,
                configId = configId,
                provider = providerModel.substring(0, separator),
                model = providerModel.substring(separator + 1),
                category = category.name,
                status = status.name,
                requestCount = 1L,
                uncachedInputTokens = snapshots.sumKnown { it.uncachedInputTokens },
                cachedInputTokens = snapshots.sumKnown { it.cachedInputTokens },
                cacheWriteTokens = snapshots.sumKnown { snapshot ->
                    if (snapshot.cacheWriteSeparateBilling) snapshot.cacheWriteTokens else 0L
                },
                totalInputTokens = snapshots.sumKnown { it.totalInputTokens },
                outputTokens = snapshots.sumKnown { snapshot ->
                    snapshot.outputTokens?.let { output ->
                        if (snapshot.reasoningIncludedInOutput == false) {
                            saturatedAdd(output, snapshot.reasoningTokens ?: 0L)
                        } else {
                            output
                        }
                    }
                },
                reasoningTokens = snapshots.sumKnown { it.reasoningTokens },
                ttftMs = firstToken?.let { (it - startedAtMs).coerceAtLeast(0L) },
                durationMs = firstToken?.let { (endedAtMs - it).coerceAtLeast(0L) },
            )
        }

        fun markPersisted(): Boolean = finished.compareAndSet(false, true)

        private fun merge(
            previous: ProviderUsageSnapshot?,
            update: ProviderUsageSnapshot,
        ): ProviderUsageSnapshot {
            if (previous == null || update.completeSnapshot) return update
            return update.copy(
                uncachedInputTokens = update.uncachedInputTokens ?: previous.uncachedInputTokens,
                cachedInputTokens = update.cachedInputTokens ?: previous.cachedInputTokens,
                cacheWriteTokens = update.cacheWriteTokens ?: previous.cacheWriteTokens,
                totalInputTokens = update.totalInputTokens ?: previous.totalInputTokens,
                outputTokens = update.outputTokens ?: previous.outputTokens,
                reasoningTokens = update.reasoningTokens ?: previous.reasoningTokens,
            )
        }
    }

    companion object {
        private const val TAG = "TokenTrackingAIService"
        private const val MAX_CAUSE_DEPTH = 8

        private suspend fun persist(
            repository: TokenUsageRepository,
            request: RequestTracker,
            record: TokenUsageRecordEntity,
        ) {
            if (request.markPersisted()) persist(repository, record)
        }

        private suspend fun persist(
            repository: TokenUsageRepository,
            record: TokenUsageRecordEntity,
        ) {
            withContext(Dispatchers.IO + NonCancellable) {
                try {
                    repository.record(record)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "token usage insert failed", e)
                }
            }
        }

        private fun classify(t: Throwable): TokenStatStatus = when {
            t is CancellationException && t !is TimeoutCancellationException ->
                TokenStatStatus.CANCELLED
            isTimeout(t) -> TokenStatStatus.TIMEOUT
            else -> TokenStatStatus.FAILED
        }

        private fun isTimeout(t: Throwable): Boolean {
            var current: Throwable? = t
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                if (
                    current is TimeoutCancellationException ||
                    current is TimeoutException ||
                    current is java.util.concurrent.TimeoutException ||
                    current is SocketTimeoutException ||
                    (current is InterruptedIOException &&
                        current.message?.contains("timeout", ignoreCase = true) == true)
                ) {
                    return true
                }
                current = current.cause
                depth++
            }
            return false
        }

        private fun List<ProviderUsageSnapshot>.sumKnown(
            selector: (ProviderUsageSnapshot) -> Long?,
        ): Long? {
            if (isEmpty()) return null
            var sum = 0L
            forEach { snapshot ->
                val value = selector(snapshot) ?: return null
                sum = if (Long.MAX_VALUE - sum < value) Long.MAX_VALUE else sum + value
            }
            return sum
        }

        private fun saturatedAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}
