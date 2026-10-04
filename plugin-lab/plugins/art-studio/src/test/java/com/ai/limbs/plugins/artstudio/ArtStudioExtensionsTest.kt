package com.ai.limbs.plugins.artstudio

import com.ai.limbs.plugin.runtime.ChildExtensionBinding
import com.ai.limbs.plugin.runtime.ChildExtensionTarget
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ArtStudioExtensionsTest {
    private class Provider : InProcessUiStateProvider {
        override val stateJson = MutableStateFlow<String?>(menu(true))
        val events = mutableListOf<String>()
        override suspend fun perform(eventId: String, payloadJson: String): String {
            events += eventId
            return "{}"
        }
    }
    private fun binding(provider: Any, point: String = ART_EXTENSION_POINT) = ChildExtensionBinding(
        "example.child", "1.0.0", ChildExtensionTarget(ART_ID, point, ART_EXTENSION_API),
        "Example", emptyMap(), provider)
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejected extension request") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
    @Test fun admissionRejectsWrongPointPayloadAndDuplicateMenuIds() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val menus = ArtStudioExtensionMenus(scope, { _, _ -> }, { })
        try {
            rejects { menus.bind(binding(Provider(), "another.point")) }
            rejects { menus.bind(binding(Any())) }
            rejects { ArtExtensionMenuSchema.items("""{"schema":1,"items":[{"id":"a","title":"A","enabled":true},{"id":"a","title":"B","enabled":true}]}""") }
            assertTrue(ArtExtensionMenuSchema.rows(menus.stateJson.value!!).isEmpty())
        } finally { menus.close(); scope.cancel() }
    }
    @Test fun activationRoutesToChildAndRejectsDisabledOrUnboundItems() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val uses = mutableListOf<String>()
        val menus = ArtStudioExtensionMenus(scope, { _, _ -> }, { id -> uses += id })
        val child = Provider()
        try {
            val handle = menus.bind(binding(child))
            val row = ArtExtensionMenuSchema.rows(menus.stateJson.value!!).single()
            menus.perform("activate", row.request())
            assertEquals(listOf("example"), child.events)
            assertEquals(listOf("example.child"), uses)
            child.stateJson.value = menu(false)
            rejects { runBlocking { menus.perform("activate", row.request()) } }
            handle.close()
            assertTrue(ArtExtensionMenuSchema.rows(menus.stateJson.value!!).isEmpty())
            rejects { runBlocking { menus.perform("activate", row.request()) } }
            assertEquals(1, child.events.size)
        } finally { menus.close(); scope.cancel() }
    }
    @Test fun replacementInvalidatesOldClickAndOldCloseDoesNotRemoveNewBinding() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val menus = ArtStudioExtensionMenus(scope, { _, _ -> }, { })
        val old = Provider(); val replacement = Provider()
        try {
            val oldHandle = menus.bind(binding(old))
            val oldRow = ArtExtensionMenuSchema.rows(menus.stateJson.value!!).single()
            oldHandle.close()
            menus.bind(binding(replacement))
            oldHandle.close()
            val newRow = ArtExtensionMenuSchema.rows(menus.stateJson.value!!).single()
            assertNotEquals(oldRow.binding, newRow.binding)
            rejects { runBlocking { menus.perform("activate", oldRow.request()) } }
            menus.perform("activate", newRow.request())
            assertTrue(old.events.isEmpty())
            assertEquals(listOf("example"), replacement.events)
        } finally { menus.close(); scope.cancel() }
    }
    @Test fun invalidLiveStateRemovesExecutableRowsAndParentCloseRevokesAll() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val errors = mutableListOf<String>()
        val menus = ArtStudioExtensionMenus(scope, { id, _ -> errors += id }, { })
        val child = Provider()
        try {
            menus.bind(binding(child))
            child.stateJson.value = """{"schema":2,"items":[]}"""
            assertTrue(ArtExtensionMenuSchema.rows(menus.stateJson.value!!).isEmpty())
            assertEquals(listOf("example.child"), errors)
            child.stateJson.value = menu(true)
            assertEquals(1, ArtExtensionMenuSchema.rows(menus.stateJson.value!!).size)
            menus.close()
            child.stateJson.value = menu(false)
            assertTrue(ArtExtensionMenuSchema.rows(menus.stateJson.value!!).isEmpty())
            rejects { menus.bind(binding(Provider())) }
        } finally { menus.close(); scope.cancel() }
    }
    companion object {
        private fun menu(enabled: Boolean) = """{"schema":1,"items":[{"id":"example","title":"示例工具","enabled":$enabled}]}"""
    }
}
