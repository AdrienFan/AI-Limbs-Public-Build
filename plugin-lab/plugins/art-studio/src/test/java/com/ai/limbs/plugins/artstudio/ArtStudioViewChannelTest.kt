package com.ai.limbs.plugins.artstudio

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtStudioViewChannelTest {
    private fun physical(document: String = "doc-a", percent: Double = 120.0) =
        StudioViewSettings().describe().put("pageVisible", true).put("canvasAttached", true)
            .put("toolOptionsWindow", StudioToolWindowState().describe().put("visible", false))
            .put("canvasZoom", JSONObject().put("documentId", document).put("percent", percent)
                .put("minPercent", 12.0).put("maxPercent", 1920.0))

    private suspend fun event(channel: ArtStudioViewChannel, name: String,
        session: String = "page-a", payload: JSONObject = JSONObject()): JSONObject =
        JSONObject(channel.perform(name, payload.put("session", session).toString()))

    private suspend fun attach(channel: ArtStudioViewChannel, session: String = "page-a",
        state: JSONObject = physical()) = event(channel, "attach", session, JSONObject().put("state", state))

    private fun request(channel: ArtStudioViewChannel) =
        JSONObject(requireNotNull(channel.stateJson.value)).getJSONObject("request")

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected an explicit failure") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.isNotBlank()) }
    }

    @Test fun aDifferentProcessCanPublishTheActualCanvas() = runBlocking {
        val channel = ArtStudioViewChannel { 1000L }
        ArtStudioViewControl.canvasAttached = false
        attach(channel)
        assertTrue(channel.describe().getBoolean("canvasAttached"))
        assertEquals("doc-a", channel.describe().getJSONObject("canvasZoom").getString("documentId"))
        channel.close()
    }

    @Test fun noPageRejectsCommandsAndKeepsPreferencesUntilAcknowledged() = runBlocking {
        val channel = ArtStudioViewChannel { 1000L }
        expectFailure { channel.execute("command", JSONObject().put("command", "fit")) }
        val changed = channel.execute("zoom_tool", JSONObject().put("mode", "out"))
        assertTrue(changed.getBoolean("deferredUntilPageOpens"))
        val attached = attach(channel)
        assertEquals("out", attached.getJSONObject("applyPreferences").getString("zoomToolMode"))
        val observed = physical().put("zoomToolMode", "out").put("zoomToolBadge", "小")
        event(channel, "update", payload = JSONObject().put("state", observed))
        assertEquals(0, JSONObject(channel.stateJson.value!!).getJSONObject("applyPreferences").length())
        assertEquals("out", channel.describe().getString("zoomToolMode"))
        channel.close()
    }

    @Test fun zoomWaitsForActualExecutionAndIgnoresDuplicateClaimsAndReceipts() = runBlocking {
        supervisorScope {
            val channel = ArtStudioViewChannel { 1000L }
            attach(channel)
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                channel.execute("zoom", JSONObject().put("documentId", "doc-a").put("percent", 150.0))
            }
            val id = request(channel).getString("id")
            assertFalse(call.isCompleted)
            assertTrue(event(channel, "claim", payload = JSONObject().put("id", id)).getBoolean("accepted"))
            assertFalse(event(channel, "claim", payload = JSONObject().put("id", id)).getBoolean("accepted"))
            assertFalse(call.isCompleted)
            val completed = JSONObject().put("id", id).put("success", true).put("state", physical(percent = 150.0))
                .put("result", JSONObject().put("accepted", true).put("requestedPercent", 150.0))
            assertTrue(event(channel, "complete", payload = completed).getBoolean("accepted"))
            assertTrue(call.await().getBoolean("accepted"))
            assertEquals(150.0, channel.describe().getJSONObject("canvasZoom").getDouble("percent"), 0.001)
            assertFalse(event(channel, "complete", payload = completed).getBoolean("accepted"))
            channel.close()
        }
    }

    @Test fun changingDocumentsRejectsTheOldRequestBeforeExecution() = runBlocking {
        supervisorScope {
            val channel = ArtStudioViewChannel { 1000L }
            attach(channel)
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                channel.execute("command", JSONObject().put("command", "fit"))
            }
            val id = request(channel).getString("id")
            event(channel, "update", payload = JSONObject().put("state", physical("doc-b")))
            assertFalse(event(channel, "claim", payload = JSONObject().put("id", id)).getBoolean("accepted"))
            expectFailure { call.await() }
            channel.close()
        }
    }

    @Test fun aNewPageSessionInvalidatesCommandsAndOldUpdates() = runBlocking {
        supervisorScope {
            val channel = ArtStudioViewChannel { 1000L }
            attach(channel)
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                channel.execute("command", JSONObject().put("command", "fit"))
            }
            attach(channel, "page-b", physical("doc-b"))
            expectFailure { call.await() }
            expectFailure { event(channel, "update", "page-a", JSONObject().put("state", physical())) }
            assertEquals("doc-b", channel.describe().getJSONObject("canvasZoom").getString("documentId"))
            channel.close()
        }
    }

    @Test fun expiredHeartbeatsDoNotPretendACanvasIsOpen() = runBlocking {
        var now = 1000L
        val channel = ArtStudioViewChannel { now }
        attach(channel)
        now += ArtStudioViewChannel.LEASE_MS + 1
        assertFalse(channel.describe().getBoolean("canvasAttached"))
        assertFalse(channel.describe().has("canvasZoom"))
        expectFailure { channel.execute("command", JSONObject().put("command", "zoom_in")) }
        channel.close()
    }

    @Test fun anExpiredCommandCannotBeClaimedEvenWithAFreshHeartbeat() = runBlocking {
        supervisorScope {
            var now = 1000L
            val channel = ArtStudioViewChannel { now }
            attach(channel)
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                channel.execute("command", JSONObject().put("command", "fit"))
            }
            val id = request(channel).getString("id")
            now += ArtStudioViewChannel.TIMEOUT_MS + 1
            event(channel, "update", payload = JSONObject().put("state", physical()))
            assertFalse(event(channel, "claim", payload = JSONObject().put("id", id)).getBoolean("accepted"))
            expectFailure { call.await() }
            channel.close()
        }
    }

    @Test fun pageClosureCancelsAnOutstandingWindowRequest() = runBlocking {
        supervisorScope {
            val channel = ArtStudioViewChannel { 1000L }
            attach(channel)
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                channel.execute("tool_options", JSONObject().put("action", "show").put("toolId", "ink"))
            }
            event(channel, "update", payload = JSONObject().put("state",
                physical().put("pageVisible", false).put("canvasAttached", false)))
            expectFailure { call.await() }
            assertFalse(channel.describe().getBoolean("canvasAttached"))
            channel.close()
        }
    }

    @Test fun executionErrorsAreReturnedToTheCaller() = runBlocking {
        supervisorScope {
            val channel = ArtStudioViewChannel { 1000L }
            attach(channel)
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                channel.execute("command", JSONObject().put("command", "fit"))
            }
            val id = request(channel).getString("id")
            event(channel, "claim", payload = JSONObject().put("id", id))
            event(channel, "complete", payload = JSONObject().put("id", id).put("success", false)
                .put("error", "画布已切换").put("state", physical()))
            expectFailure { call.await() }
            channel.close()
        }
    }

    @Test fun stoppingThePluginUnblocksWaitingCallers() = runBlocking {
        supervisorScope {
            val channel = ArtStudioViewChannel { 1000L }
            attach(channel)
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                channel.execute("command", JSONObject().put("command", "fit"))
            }
            channel.close()
            expectFailure { call.await() }
            assertFalse(channel.describe().getBoolean("canvasAttached"))
        }
    }
}
