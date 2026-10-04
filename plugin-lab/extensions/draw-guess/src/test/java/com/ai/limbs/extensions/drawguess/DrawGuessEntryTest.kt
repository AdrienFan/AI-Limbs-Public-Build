package com.ai.limbs.extensions.drawguess

import com.ai.limbs.plugin.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.function.Consumer

class DrawGuessEntryTest {
    @Test fun mountsUnderRuntimeNamespaceAndBothPlayerEntrancesWork() = runBlocking {
        val capabilities = linkedMapOf<String, InProcessCapabilitySpec>()
        var discovery: ChildAiIngressDiscovery? = null
        var published: Map<*, *>? = null
        var closed = 0
        val host = Proxy.newProxyInstance(ChildExtensionHost::class.java.classLoader,
            arrayOf(ChildExtensionHost::class.java)) { _, method, args ->
            when (method.name) {
                "getExtensionId" -> GAME_ID
                "getTarget" -> ChildExtensionTarget("plugin.art.studio", "plugin.art.studio.extension_menu", 1)
                "registerCapability" -> {
                    val spec = args!![0] as InProcessCapabilitySpec
                    // This is the Child Runtime's admission rule that rejected version 0.1.0.
                    val namespace = "plugin.${GAME_ID.substringAfterLast('.')}"
                    check(spec.id.trim().lowercase().startsWith("$namespace.")) {
                        "Child capability must stay inside $namespace.*: ${spec.id}"
                    }
                    check(capabilities.put(spec.id, spec) == null)
                    AutoCloseable { closed++ }
                }
                "publishAiIngressDiscovery" -> {
                    check(discovery == null)
                    discovery = args!![0] as ChildAiIngressDiscovery
                    AutoCloseable { closed++ }
                }
                "publish" -> {
                    check(published == null)
                    published = args!![0] as Map<*, *>
                    val endpoint = object : InProcessUiStateProvider {
                        override val stateJson = MutableStateFlow<String?>("{}")
                        override suspend fun perform(eventId: String, payloadJson: String) = "{}"
                    }
                    @Suppress("UNCHECKED_CAST")
                    (published!!["connect"] as Consumer<InProcessUiStateProvider>).accept(endpoint)
                    null
                }
                else -> error("Unexpected host access: ${method.name}")
            }
        } as ChildExtensionHost
        val handle = DrawGuessEntry().mount(host)
        try {
            val events = setOf("view", "ready", "roll", "confirm_dice", "choose_order", "seal_word",
                "seal_hints", "canvas", "preview", "paint", "finish", "picture", "guess", "exit")
            assertEquals(events.map { "plugin.draw_guess.$it" }.toSet(), capabilities.keys)
            val ingress = JSONObject(requireNotNull(discovery).payloadJson)
            assertEquals("plugin.draw_guess.ready", ingress.getString("start"))
            assertEquals("plugin.draw_guess.view", ingress.getString("view"))
            assertTrue(capabilities.containsKey(ingress.getString("start")))
            assertEquals(GAME_RULES, ingress.getString("rules"))
            val binding = requireNotNull(published)
            val menu = binding["menu"] as InProcessUiStateProvider
            val panel = binding["panel"] as InProcessUiStateProvider
            menu.perform("open", "{}")
            val ready = JSONObject(capabilities.getValue("plugin.draw_guess.ready").executor.invoke("{}"))
            assertEquals("LANER", ready.getString("player"))
            assertEquals(GAME_RULES, ready.getString("rules"))
            assertEquals("READY", ready.getString("phase"))
            val phone = JSONObject(panel.perform("ready", "{}"))
            assertEquals("AWEI", phone.getString("player"))
            assertEquals("DICE", phone.getString("phase"))
            assertTrue(JSONObject(requireNotNull(panel.stateJson.value)).getBoolean("open"))
        } finally { handle.stop() }
        assertEquals(15, closed)
        assertNull((requireNotNull(published)["panel"] as InProcessUiStateProvider).stateJson.value)
    }
}
