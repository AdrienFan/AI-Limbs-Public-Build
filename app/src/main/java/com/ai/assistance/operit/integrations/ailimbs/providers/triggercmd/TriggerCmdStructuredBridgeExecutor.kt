package com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionSession
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionTransport
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsRemoteInvocationExecutor
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class TriggerCmdStructuredBridgeExecutor(context: Context) {
    private data class CachedResponse(
        val signature: String,
        val response: String
    )

    private val remoteExecutor =
        AiLimbsRemoteInvocationExecutor(
            context.applicationContext,
            AiLimbsExecutionSession(
                transport = AiLimbsExecutionTransport.TRIGGERCMD,
                scopeId = "triggercmd-" + UUID.randomUUID()
            )
        )
    // Serialize retries and permission dialogs so one request_id has one deterministic outcome.
    private val executionMutex = Mutex()

    private val completed =
        object : LinkedHashMap<String, CachedResponse>(MAX_CACHED_RESPONSES + 1, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CachedResponse>?
            ): Boolean = size > MAX_CACHED_RESPONSES
        }

    suspend fun execute(rawParams: String): String {
        val decoded = TriggerCmdBridgeProtocol.decode(rawParams)
        if (decoded is TriggerCmdBridgeDecodeResult.Failure) {
            return TriggerCmdBridgeProtocol.decodeFailure(decoded)
        }
        val request = (decoded as TriggerCmdBridgeDecodeResult.Success).request
        return executionMutex.withLock {
            completed[request.requestId]?.let { cached ->
                return@withLock if (cached.signature == request.signature) {
                    cached.response
                } else {
                    TriggerCmdBridgeProtocol.requestIdConflict(request.requestId)
                }
            }

            val response = try {
                TriggerCmdBridgeProtocol.completed(
                    request,
                    remoteExecutor.execute(request.tool, request.args)
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                TriggerCmdBridgeProtocol.executionError(
                    request,
                    error.message ?: error::class.java.simpleName
                )
            }
            completed[request.requestId] = CachedResponse(request.signature, response)
            response
        }
    }

    companion object {
        private const val MAX_CACHED_RESPONSES = 128
    }
}
