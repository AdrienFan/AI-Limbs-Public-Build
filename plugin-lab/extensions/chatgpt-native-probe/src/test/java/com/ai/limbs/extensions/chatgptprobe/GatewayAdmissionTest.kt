package com.ai.limbs.extensions.chatgptprobe

import org.junit.Assert.*
import org.junit.Test

class GatewayAdmissionTest {
    private val config = ChatGptProbeConfig(true, true, "tunnel-test", "https://example.com")
    private val online = McpGatewayState(running = true, phase = "ONLINE", lastSuccessfulPollAtMs = 1000L,
        access = GatewayAccessObservation(startedAtMs = 900L))

    @Test fun pollingAloneDoesNotProveToolOrBusinessAccess() {
        val status = GatewayAdmission.evaluate(online, config, true, 1100L)
        assertTrue(status.transportHealthy)
        assertEquals("AWAITING_TOOL_DISCOVERY", status.stage)
        assertFalse(status.advertisedToolObserved)
        assertFalse(status.successfulInvokeObserved)
        assertFalse(status.toJson().getBoolean("client_catalog_refresh_verified"))
    }

    @Test fun catalogRequestDoesNotProveACallOrClientRefresh() {
        val status = GatewayAdmission.evaluate(online.copy(lastToolsListAtMs = 1010L), config, true, 1100L)
        assertEquals("CATALOG_REQUEST_OBSERVED", status.stage)
        assertTrue(status.catalogRequested)
        assertFalse(status.successfulInvokeObserved)
        assertFalse(status.toJson().getBoolean("automatic_client_refresh_supported"))
    }

    @Test fun readToolAndSuccessfulInvocationAreDistinctEvenWithACachedCatalog() {
        val read = online.copy(access = online.access.copy(lastAdvertisedToolCallAtMs = 1010L))
        assertEquals("ADVERTISED_TOOL_CALL_OBSERVED", GatewayAdmission.evaluate(read, config, true, 1100L).stage)
        val invoked = read.copy(access = read.access.copy(lastSuccessfulInvokeAtMs = 1020L))
        val status = GatewayAdmission.evaluate(invoked, config, true, 1100L)
        assertEquals("CAPABILITY_RESULT_OBSERVED", status.stage)
        assertTrue(status.successfulInvokeObserved)
        assertFalse(status.catalogRequested)
    }

    @Test fun mismatchRemainsVisibleDespiteAnEarlierSuccessfulInvocation() {
        val mismatch = online.copy(access = online.access.copy(lastSuccessfulInvokeAtMs = 1010L, catalogMismatchSuspected = true))
        val status = GatewayAdmission.evaluate(mismatch, config, true, 1100L)
        assertEquals("CATALOG_MISMATCH_SUSPECTED", status.stage)
        assertTrue(status.successfulInvokeObserved)
        assertTrue(status.catalogMismatchSuspected)
    }

    @Test fun oldPollsAndMissingPrerequisitesCannotAppearReady() {
        assertEquals("TRANSPORT_PENDING", GatewayAdmission.evaluate(online, config, true, 50_000L).stage)
        val restarted = online.copy(access = GatewayAccessObservation(startedAtMs = 1100L))
        assertFalse(GatewayAdmission.evaluate(restarted, config, true, 1200L).transportHealthy)
        assertEquals("CONFIGURATION_REQUIRED", GatewayAdmission.evaluate(online, config.copy(configured = false), true, 1100L).stage)
        assertEquals("INGRESS_UNBOUND", GatewayAdmission.evaluate(online, config, false, 1100L).stage)
        assertEquals("AUTH_REQUIRED", GatewayAdmission.evaluate(online.copy(running = false, phase = "AUTH_REQUIRED"), config, true, 1100L).stage)
    }
}
