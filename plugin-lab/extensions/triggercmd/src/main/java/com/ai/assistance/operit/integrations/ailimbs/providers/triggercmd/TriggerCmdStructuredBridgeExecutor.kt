// Source: AI Limbs V0.6.4.7.8 @ 70438d99bb40c147cadc0a4a085deb90d15b347c; host execution is adapted separately.
package com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd

import com.ai.limbs.extensions.triggercmd.runtime.AiLimbsRemoteInvocationExecutor
import com.ai.assistance.operit.integrations.ailimbs.BridgeRemoteIngress
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class TriggerCmdStructuredBridgeExecutor(
    private val scope: CoroutineScope,
    private val executeRemote: suspend (String, JSONObject) -> JSONObject
) {
    private data class RequestRecord(val signature: String)
    private data class CachedResponse(val signature: String, val response: String)

    constructor(remoteIngress: BridgeRemoteIngress, scope: CoroutineScope) : this(
        scope = scope,
        executeRemote = createRemoteExecutor(remoteIngress)
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
            TriggerCmdBridgeProtocol.completed(
                request,
                executeRemote(request.tool, request.args)
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

        private fun createRemoteExecutor(
            remoteIngress: BridgeRemoteIngress
        ): suspend (String, JSONObject) -> JSONObject {
            val remoteExecutor = AiLimbsRemoteInvocationExecutor(remoteIngress)
            return { tool, args -> remoteExecutor.execute(tool, args) }
        }
    }
}
