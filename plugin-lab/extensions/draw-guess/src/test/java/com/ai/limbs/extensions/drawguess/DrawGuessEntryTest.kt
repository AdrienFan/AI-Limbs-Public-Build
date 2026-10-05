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
            val events = setOf("open", "view", "ready", "roll", "confirm_dice", "choose_order", "seal_word",
                "seal_hints", "canvas", "preview", "paint", "finish", "picture", "guess", "exit")
            assertEquals(events.map { "plugin.draw_guess.$it" }.toSet(), capabilities.keys)
            val paintExample = JSONObject(capabilities.getValue("plugin.draw_guess.paint").suggestedParamsJson)
            assertTrue(paintExample.getJSONObject("params").getString("id").matches(Regex("[a-f0-9-]{36}")))
            val ingress = JSONObject(requireNotNull(discovery).payloadJson)
            assertEquals("plugin.draw_guess.open", ingress.getString("start"))
            assertEquals("plugin.draw_guess.ready", ingress.getString("ready"))
            assertEquals("plugin.draw_guess.view", ingress.getString("view"))
            assertTrue(capabilities.containsKey(ingress.getString("start")))
            assertEquals(GAME_RULES, ingress.getString("rules"))
            val binding = requireNotNull(published)
            val menu = binding["menu"] as InProcessUiStateProvider
            val panel = binding["panel"] as InProcessUiStateProvider
            val beforeOpen = JSONObject(capabilities.getValue("plugin.draw_guess.view").executor.invoke("{}"))
            assertFalse(beforeOpen.getBoolean("open"))
            assertEquals("open", beforeOpen.getJSONArray("allowed").getString(0))
            val opened = JSONObject(capabilities.getValue("plugin.draw_guess.open").executor.invoke("{}"))
            assertEquals("LANER", opened.getString("player"))
            assertTrue(opened.getBoolean("open"))
            assertEquals(0, opened.getJSONArray("ready").length())
            assertEquals("ready", opened.getJSONArray("allowed").getString(0))
            menu.perform("open", "{}")
            val ready = JSONObject(capabilities.getValue("plugin.draw_guess.ready").executor.invoke("{}"))
            assertEquals("LANER", ready.getString("player"))
            assertEquals(GAME_RULES, ready.getString("rules"))
            assertEquals("READY", ready.getString("phase"))
            val phone = JSONObject(panel.perform("ready", "{}"))
            assertEquals("AWEI", phone.getString("player"))
            assertEquals("DICE", phone.getString("phase"))
            assertTrue(JSONObject(requireNotNull(panel.stateJson.value)).getBoolean("open"))
            // Reopening an active game must not erase either player's readiness or advance revision.
            val repeated = JSONObject(capabilities.getValue("plugin.draw_guess.open").executor.invoke("{}"))
            assertEquals("DICE", repeated.getString("phase"))
            assertEquals(phone.getInt("revision"), repeated.getInt("revision"))
            assertEquals(2, repeated.getJSONArray("ready").length())
            val exited = JSONObject(capabilities.getValue("plugin.draw_guess.exit").executor.invoke(
                JSONObject().put("revision", repeated.getInt("revision")).toString()))
            assertEquals("CLOSED", exited.getString("phase"))
            assertEquals("open", exited.getJSONArray("allowed").getString(0))
            // The public LANER entrance must recover an exited game without touching AWEI's entrance.
            val reopened = JSONObject(capabilities.getValue("plugin.draw_guess.open").executor.invoke("{}"))
            assertEquals("READY", reopened.getString("phase"))
            assertTrue(reopened.getBoolean("open"))
            assertEquals(0, reopened.getJSONArray("ready").length())
            assertTrue(reopened.getInt("revision") > exited.getInt("revision"))
            assertTrue(JSONObject(requireNotNull(panel.stateJson.value)).getBoolean("open"))
            val readyAgain = JSONObject(capabilities.getValue("plugin.draw_guess.ready").executor.invoke("{}"))
            assertEquals("READY", readyAgain.getString("phase"))
            assertEquals("LANER", readyAgain.getJSONArray("ready").getString(0))
        } finally { handle.stop() }
        assertEquals(16, closed)
        assertNull((requireNotNull(published)["panel"] as InProcessUiStateProvider).stateJson.value)
    }
}
