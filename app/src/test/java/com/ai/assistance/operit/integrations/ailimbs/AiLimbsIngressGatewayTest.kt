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
    fun bootstrapIsDeliveredOncePerIngressSession() = runBlocking {
        val bootstrapReads = AtomicInteger(0)
        val gateway = testGateway(bootstrapReads)

        val first = gateway.invoke("capability.search", JSONObject().put("query", "Ubuntu"))
        val second = gateway.invoke("capability.search", JSONObject().put("query", "Bridge"))

        assertEquals("bootstrap-1", first.accessBootstrap)
        assertNull(second.accessBootstrap)
        assertEquals("rdc", gateway.ingressSession.sourceId)
        assertEquals(AiLimbsExecutionTransport.RDC, gateway.ingressSession.executionSession.transport)
        assertEquals(1, bootstrapReads.get())
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
            gateway.complete(JSONObject().put("success", true))
        }.exceptionOrNull()
        val retry = gateway.complete(JSONObject().put("success", true))

        assertTrue(firstFailure?.message?.contains("temporary") == true)
        assertEquals("bootstrap-ok", retry.accessBootstrap)
        assertEquals(2, reads.get())
    }

    private fun testGateway(bootstrapReads: AtomicInteger): AiLimbsIngressGateway =
        AiLimbsIngressGateway(
            ingressSession = ingressSession(),
            executeRemote = { tool, args ->
                JSONObject().put("tool", tool).put("args", args)
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
