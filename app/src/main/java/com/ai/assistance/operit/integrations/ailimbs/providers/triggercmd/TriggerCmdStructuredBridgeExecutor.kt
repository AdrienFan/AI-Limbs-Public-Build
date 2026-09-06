package com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionSession
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionTransport
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsIngressGateway
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsIngressResult
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsIngressSession
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class TriggerCmdStructuredBridgeExecutor(
    private val scope: CoroutineScope,
    private val executeIngress: suspend (String, JSONObject) -> AiLimbsIngressResult
) {
    private data class RequestRecord(val signature: String)
    private data class CachedResponse(val signature: String, val response: String)

    constructor(context: Context, scope: CoroutineScope) : this(
        scope = scope,
        executeIngress = createIngressExecutor(context)
    )

    private val stateMutex = Mutex()
    private val inFlight = LinkedHashMap<String, RequestRecord>()
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
        var launchExecution = false
        val immediateResponse = stateMutex.withLock {
            completed[request.requestId]?.let { cached ->
                return@withLock if (cached.signature == request.signature) {
                    cached.response
                } else {
                    TriggerCmdBridgeProtocol.requestIdConflict(request.requestId)
                }
            }
            inFlight[request.requestId]?.let { running ->
                return@withLock if (running.signature == request.signature) {
                    TriggerCmdBridgeProtocol.running(request)
                } else {
                    TriggerCmdBridgeProtocol.requestIdConflict(request.requestId)
                }
            }
            if (inFlight.size >= MAX_IN_FLIGHT_REQUESTS) {
                return@withLock TriggerCmdBridgeProtocol.bridgeBusy(request.requestId)
            }
            inFlight[request.requestId] = RequestRecord(request.signature)
            launchExecution = true
            TriggerCmdBridgeProtocol.accepted(request)
        }
        if (launchExecution) {
            scope.launch(Dispatchers.IO) {
                executeInBackground(request)
            }
        }
        return immediateResponse
    }

    private suspend fun executeInBackground(request: TriggerCmdBridgeRequest) {
        val response = try {
            val ingressResult = executeIngress(request.tool, request.args)
            TriggerCmdBridgeProtocol.completed(
                request,
                ingressResult.payload,
                ingressResult.accessBootstrap
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            TriggerCmdBridgeProtocol.executionError(
                request,
                error.message ?: error::class.java.simpleName
            )
        }
        stateMutex.withLock {
            val running = inFlight[request.requestId] ?: return@withLock
            if (running.signature != request.signature) return@withLock
            inFlight.remove(request.requestId)
            completed[request.requestId] = CachedResponse(request.signature, response)
        }
    }

    companion object {
        private const val MAX_IN_FLIGHT_REQUESTS = 32
        private const val MAX_CACHED_RESPONSES = 128

        private fun createIngressExecutor(
            context: Context
        ): suspend (String, JSONObject) -> AiLimbsIngressResult {
            val ingressGateway =
                AiLimbsIngressGateway(
                    context.applicationContext,
                    AiLimbsIngressSession(
                        sourceId = AiLimbsExecutionTransport.TRIGGERCMD.wireValue,
                        executionSession = AiLimbsExecutionSession(
                            transport = AiLimbsExecutionTransport.TRIGGERCMD,
                            scopeId = "triggercmd-" + UUID.randomUUID()
                        )
                    )
                )
            return { tool, args -> ingressGateway.invoke(tool, args) }
        }
    }
}
