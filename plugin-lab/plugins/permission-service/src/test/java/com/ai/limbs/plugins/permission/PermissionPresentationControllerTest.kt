package com.ai.limbs.plugins.permission

import com.ai.limbs.plugin.runtime.InProcessProviderBinding
import com.ai.limbs.plugin.runtime.InProcessProviderDirectory
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PermissionPresentationControllerTest {
    @Test fun presentationCanStartBeforeStateProviderAndReceiveItsLaterPublication() = runBlocking {
        val directory = Directory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val errors = mutableListOf<Throwable>()
        try {
            val controller = PermissionPresentationController(directory, scope) { errors += it }
            assertTrue(controller.state.value.busy)
            assertTrue(controller.state.value.statusError.isNotEmpty())
            try { controller.refresh(); fail("Missing provider must reject actions") }
            catch (expected: IllegalStateException) { /* Explicit readiness error, no action dispatched. */ }
            directory.publish(Provider(2000))
            val ready = withTimeout(2000) { controller.state.first { it.running } }
            assertEquals(2000, ready.uid)
            assertFalse(ready.busy)
            assertTrue(errors.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun replacementAndRemovalUpdateStateAndActionRouting() = runBlocking {
        val directory = Directory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val errors = mutableListOf<Throwable>()
        val old = Provider(2000)
        val replacement = Provider(0)
        try {
            directory.publish(old)
            val controller = PermissionPresentationController(directory, scope) { errors += it }
            withTimeout(2000) { controller.state.first { it.running } }
            controller.refresh()
            directory.publish(replacement)
            withTimeout(2000) { controller.state.first { it.running && it.uid == 0 } }
            controller.select("ai_limbs")
            assertEquals(listOf("refresh"), old.events)
            assertEquals(listOf("select"), replacement.events)
            directory.binding.value = null
            withTimeout(2000) { controller.state.first { it.busy && it.statusError.isNotEmpty() } }
            try { controller.stop(); fail("Removed provider must reject actions") }
            catch (expected: IllegalStateException) { }
            assertEquals(listOf("select"), replacement.events)
            assertTrue(errors.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun wrongOwnerDoesNotBecomeThePermissionServiceStateSource() = runBlocking {
        val directory = Directory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val errors = mutableListOf<Throwable>()
        val impostor = Provider(0)
        try {
            val controller = PermissionPresentationController(directory, scope) { errors += it }
            directory.binding.value = InProcessProviderBinding("plugin.other", ID, emptyMap(), impostor)
            withTimeout(2000) { controller.state.first { it.statusError == "状态同步失败" } }
            try { controller.refresh(); fail("Mismatched owner must be rejected") }
            catch (expected: IllegalStateException) { }
            assertEquals(1, errors.size)
            assertTrue(impostor.events.isEmpty())
        } finally { scope.cancel() }
    }

    private class Directory : InProcessProviderDirectory {
        val binding = MutableStateFlow<InProcessProviderBinding?>(null)
        fun publish(provider: InProcessUiStateProvider) {
            binding.value = InProcessProviderBinding(OWNER, ID, emptyMap(), provider)
        }
        override fun resolve(id: String): InProcessProviderBinding? {
            require(id == ID); return binding.value
        }
        override fun snapshot(): List<InProcessProviderBinding> = listOfNotNull(binding.value)
        override fun observe(id: String): StateFlow<InProcessProviderBinding?> {
            require(id == ID); return binding
        }
    }

    private class Provider(uid: Int) : InProcessUiStateProvider {
        override val stateJson = MutableStateFlow<String?>(JSONObject()
            .put("running", true).put("backend", "ai_limbs").put("uid", uid)
            .put("busy", false).put("message", "已连接").toString())
        val events = mutableListOf<String>()
        override suspend fun perform(eventId: String, payloadJson: String): String {
            events += eventId
            return checkNotNull(stateJson.value)
        }
    }

    private companion object {
        const val OWNER = "plugin.system.permission_service"
        const val ID = "$OWNER.ui"
    }
}
