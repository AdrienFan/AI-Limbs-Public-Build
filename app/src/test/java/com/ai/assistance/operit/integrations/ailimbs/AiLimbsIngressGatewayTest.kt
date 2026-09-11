package com.ai.assistance.operit.integrations.ailimbs

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLimbsIngressGatewayTest {
    @Test
    fun firstInvocationReturnsBootstrapWithoutExecutingCommand() = runBlocking {
        val bootstrapReads = AtomicInteger(0)
        val executions = AtomicInteger(0)
        val gateway = testGateway(bootstrapReads, executions)

        val first = gateway.invoke("capability.search", JSONObject().put("query", "Ubuntu"))
        val second = gateway.invoke("capability.search", JSONObject().put("query", "Bridge"))

        assertEquals("bootstrap-1", first.accessBootstrap)
        assertEquals(0, first.payload.optInt("execution", 0))
        assertNull(second.accessBootstrap)
        assertEquals(1, second.payload.optInt("execution"))
        assertEquals(1, executions.get())
        assertEquals("rdc", gateway.ingressSession.sourceId)
        assertEquals(AiLimbsExecutionTransport.RDC, gateway.ingressSession.executionSession.transport)
        assertEquals(1, bootstrapReads.get())
    }

    @Test
    fun invokePayloadAlsoBlocksTheFirstCommand() = runBlocking {
        val bootstrapReads = AtomicInteger(0)
        val executions = AtomicInteger(0)
        val gateway = testGateway(bootstrapReads, executions)

        val first = gateway.invokePayload("anything", JSONObject())
        val second = gateway.invokePayload("anything", JSONObject())

        assertEquals("bootstrap-1", first.optString("access_bootstrap"))
        assertEquals(0, first.optInt("execution", 0))
        assertEquals(1, second.optInt("execution"))
        assertEquals(1, executions.get())
    }

    @Test
    fun resetRearmsBootstrapWithoutChangingExecutionSession() = runBlocking {
        val bootstrapReads = AtomicInteger(0)
        val gateway = testGateway(bootstrapReads)

        gateway.invoke("capability.search", JSONObject())
        gateway.resetAccessBootstrap()
        val afterReset = gateway.invoke("capability.describe", JSONObject())

        assertEquals("bootstrap-2", afterReset.accessBootstrap)
        assertEquals("scope-rdc-test", gateway.ingressSession.executionSession.scopeId)
        assertEquals(2, bootstrapReads.get())
    }

    @Test
    fun bootstrapReadFailureLeavesBootstrapPendingForRetry() = runBlocking {
        val reads = AtomicInteger(0)
        val gateway = AiLimbsIngressGateway(
            ingressSession = ingressSession(),
            executeRemote = { tool, _ -> JSONObject().put("tool", tool) },
            readAccessBootstrap = {
                if (reads.incrementAndGet() == 1) error("temporary")
                "bootstrap-ok"
            }
        )

        val firstFailure = runCatching {
            gateway.invoke("capability.search", JSONObject())
        }.exceptionOrNull()
        val retry = gateway.invoke("capability.search", JSONObject())

        assertTrue(firstFailure?.message?.contains("temporary") == true)
        assertEquals("bootstrap-ok", retry.accessBootstrap)
        assertEquals(2, reads.get())
    }

    private fun testGateway(
        bootstrapReads: AtomicInteger,
        executions: AtomicInteger = AtomicInteger(0)
    ): AiLimbsIngressGateway =
        AiLimbsIngressGateway(
            ingressSession = ingressSession(),
            executeRemote = { tool, args ->
                JSONObject()
                    .put("tool", tool)
                    .put("args", args)
                    .put("execution", executions.incrementAndGet())
            },
            readAccessBootstrap = {
                "bootstrap-${bootstrapReads.incrementAndGet()}"
            }
        )

    private fun ingressSession(): AiLimbsIngressSession =
        AiLimbsIngressSession(
            sourceId = "rdc",
            executionSession = AiLimbsExecutionSession(
                transport = AiLimbsExecutionTransport.RDC,
                scopeId = "scope-rdc-test"
            )
        )
}
