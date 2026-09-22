package com.ai.assistance.operit.plugins.center

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginHostCapabilityRegistryUnifiedHostEntryTest {
    @Test
    fun requestableCatalogPrimitiveReachesGenericHostGatewayWithoutAdapterWhitelist() = runBlocking {
        val id = "host.android.component@1"
        val primitive = requireNotNull(AiLimbsHostPrimitiveCatalog.find(id))
        assertTrue(primitive.requestableScope)
        assertEquals(HostPrimitiveExposure.BOUND, primitive.exposure)
        assertTrue(HostPrimitiveGatewayBindings.isCallable(id))

        val invoker = PluginHostCapabilityRegistry().create("plugin.test.generic_host", setOf(id))
        val error = runCatching {
            invoker.invoke(id, JSONObject().put("operation", "invoke"))
        }.exceptionOrNull() as PluginInstallException

        assertEquals("HOST_GATEWAY_NOT_READY", error.code)
    }

    @Test
    fun genericHostGatewayStillRequiresDeclaredGrantedScope() = runBlocking {
        val id = "host.android.component@1"
        val invoker = PluginHostCapabilityRegistry().create("plugin.test.generic_host", emptySet())
        val error = runCatching {
            invoker.invoke(id, JSONObject().put("operation", "invoke"))
        }.exceptionOrNull() as PluginInstallException

        assertEquals("PLUGIN_SCOPE_DENIED", error.code)
    }
}
