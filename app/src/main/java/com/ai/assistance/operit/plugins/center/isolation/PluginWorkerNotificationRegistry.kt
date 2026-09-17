package com.ai.assistance.operit.plugins.center.isolation

import com.ai.limbs.plugin.runtime.InProcessNotificationActionHandler
import com.ai.limbs.plugin.runtime.InProcessNotificationHost
import com.ai.limbs.plugin.runtime.InProcessNotificationState
import com.ai.limbs.plugin.runtime.InProcessProviderBinding
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Worker-local notification surface. Core polls neutral state and routes actions back by RPC. */
internal class PluginWorkerNotificationRegistry {
    private data class Binding(
        val ownerPluginId: String,
        val handler: InProcessNotificationActionHandler,
        @Volatile var latest: InProcessNotificationState? = null,
        @Volatile var job: Job? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bindings = ConcurrentHashMap<String, Binding>()

    fun bindingFor(ownerPluginId: String, grantedScopes: Set<String>): InProcessProviderBinding? {
        if (NOTIFICATION_SCOPE !in grantedScopes) return null
        val host = object : InProcessNotificationHost {
            override fun publish(
                state: StateFlow<InProcessNotificationState?>,
                actionHandler: InProcessNotificationActionHandler
            ): AutoCloseable {
                val binding = Binding(ownerPluginId, actionHandler)
                check(bindings.putIfAbsent(ownerPluginId, binding) == null) {
                    "Plugin already owns a worker notification binding: $ownerPluginId"
                }
                binding.job = scope.launch {
                    state.collect { binding.latest = it }
                }
                return AutoCloseable {
                    if (bindings.remove(ownerPluginId, binding)) {
                        binding.job?.cancel()
                    }
                }
            }
        }
        return InProcessProviderBinding(
            ownerPluginId = "core.notification.host",
            id = InProcessSystemIds.NOTIFICATION_HOST_PROVIDER,
            metadata = mapOf("api" to "1", "runtime_role" to "plugin_worker"),
            payload = host
        )
    }

    fun snapshot(ownerPluginId: String): JSONObject {
        val state = bindings[ownerPluginId]?.latest ?: return JSONObject().put("available", false)
        return JSONObject()
            .put("available", true)
            .put("title", state.title)
            .put("summary", state.summary)
            .put("status_lines", JSONArray(state.statusLines))
            .put("actions", JSONArray().apply {
                state.actions.forEach { action ->
                    put(JSONObject()
                        .put("id", action.id)
                        .put("label", action.label)
                        .put("priority", action.priority)
                        .put("enabled", action.enabled))
                }
            })
    }

    suspend fun perform(ownerPluginId: String, actionId: String): Boolean {
        val binding = bindings[ownerPluginId] ?: return false
        binding.handler.perform(actionId)
        return true
    }

    fun clearOwner(ownerPluginId: String) {
        bindings.remove(ownerPluginId)?.job?.cancel()
    }

    fun close() {
        bindings.values.forEach { it.job?.cancel() }
        bindings.clear()
        scope.cancel()
    }

    private companion object {
        const val NOTIFICATION_SCOPE = "host.notification@1"
    }
}
