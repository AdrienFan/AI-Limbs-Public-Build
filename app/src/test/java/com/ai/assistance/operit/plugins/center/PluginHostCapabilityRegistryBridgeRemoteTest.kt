package com.ai.assistance.operit.plugins.center

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginHostCapabilityRegistryBridgeRemoteTest {
    @Test
    fun `bridge owner reaches private remote ingress`() = runBlocking {
        val invoker = PluginHostCapabilityRegistry().create("plugin.system.bridge", emptySet())
        val error = runCatching {
            invoker.invoke("core.bridge.remote.invoke", JSONObject())
        }.exceptionOrNull() as PluginInstallException
        assertEquals("HOST_RUNTIME_UNAVAILABLE", error.code)
    }

    @Test
    fun `ordinary plugin cannot call private bridge remote ingress`() = runBlocking {
        val invoker = PluginHostCapabilityRegistry().create("plugin.other", emptySet())
        val error = runCatching {
            invoker.invoke("core.bridge.remote.invoke", JSONObject())
        }.exceptionOrNull() as PluginInstallException
        assertEquals("HOST_PRIMITIVE_UNKNOWN", error.code)
    }
}
