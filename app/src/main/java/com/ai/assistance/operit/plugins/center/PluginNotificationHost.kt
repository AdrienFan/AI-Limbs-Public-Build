package com.ai.assistance.operit.plugins.center

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.util.AppLogger
import com.ai.limbs.plugin.runtime.InProcessNotificationActionHandler
import com.ai.limbs.plugin.runtime.InProcessNotificationHost
import com.ai.limbs.plugin.runtime.InProcessNotificationState
import com.ai.limbs.plugin.runtime.InProcessProviderBinding
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class PluginForegroundNotificationSnapshot(
    val bindingId: String,
    val ownerPluginId: String,
    val state: InProcessNotificationState
)

internal class PluginNotificationHost(
    context: Context,
    private val surfacePolicy: HostSurfacePolicy
) {
    private data class Binding(
        val ownerPluginId: String,
        val handler: InProcessNotificationActionHandler,
        @Volatile var job: Job? = null,
        @Volatile var latest: InProcessNotificationState? = null
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bindings = ConcurrentHashMap<String, Binding>()
    private val ownerBindings = ConcurrentHashMap<String, String>()
    private val foregroundStateFlow = MutableStateFlow<PluginForegroundNotificationSnapshot?>(null)

    val foregroundState: StateFlow<PluginForegroundNotificationSnapshot?> = foregroundStateFlow.asStateFlow()
    val hasForegroundResponsibility: Boolean
        get() = foregroundStateFlow.value != null

    fun bindingFor(ownerPluginId: String, requestedScopes: Set<String>): InProcessProviderBinding? {
        if (NOTIFICATION_SCOPE !in requestedScopes || !surfacePolicy.isAllowed(PluginSurfaceIds.HOST_NOTIFICATION)) {
            return null
        }
        val scoped = object : InProcessNotificationHost {
            override fun publish(
                state: StateFlow<InProcessNotificationState?>,
                actionHandler: InProcessNotificationActionHandler
            ): AutoCloseable = publishScoped(ownerPluginId, state, actionHandler)
        }
        return InProcessProviderBinding(
            ownerPluginId = HOST_OWNER_ID,
            id = InProcessSystemIds.NOTIFICATION_HOST_PROVIDER,
            metadata = mapOf("api" to "1", "surface" to PluginSurfaceIds.HOST_NOTIFICATION),
            payload = scoped
        )
    }

    private fun publishScoped(
        ownerPluginId: String,
        state: StateFlow<InProcessNotificationState?>,
        handler: InProcessNotificationActionHandler
    ): AutoCloseable {
        surfacePolicy.requireAllowed(PluginSurfaceIds.HOST_NOTIFICATION)
        val bindingId = UUID.randomUUID().toString()
        check(ownerBindings.putIfAbsent(ownerPluginId, bindingId) == null) {
            "Plugin already owns a notification binding: $ownerPluginId"
        }
        val binding = Binding(ownerPluginId, handler)
        bindings[bindingId] = binding
        binding.job = scope.launch {
            state.collect { raw ->
                binding.latest = raw?.sanitize()
                recomputeForegroundState()
            }
        }
        return AutoCloseable {
            bindings.remove(bindingId)?.job?.cancel()
            ownerBindings.remove(ownerPluginId, bindingId)
            recomputeForegroundState()
        }
    }

    fun dispatch(bindingId: String, actionId: String): Boolean {
        val binding = bindings[bindingId] ?: return false
        if (!surfacePolicy.isAllowed(PluginSurfaceIds.HOST_NOTIFICATION)) return false
        scope.launch {
            runCatching { binding.handler.perform(actionId) }
                .onFailure { AppLogger.e(TAG, "Notification action failed: ${binding.ownerPluginId}/$actionId", it) }
        }
        return true
    }

    fun refreshAll() {
        recomputeForegroundState()
    }

    fun clear() {
        bindings.values.forEach { it.job?.cancel() }
        bindings.clear()
        ownerBindings.clear()
        recomputeForegroundState()
    }

    @Synchronized
    private fun recomputeForegroundState() {
        // The foreground service owns one Android notification. Stable owner ordering keeps
        // competing plugin updates from stealing the card based on timing.
        foregroundStateFlow.value =
            bindings.entries
                .asSequence()
                .mapNotNull { (bindingId, binding) ->
                    binding.latest?.let { state ->
                        PluginForegroundNotificationSnapshot(
                            bindingId,
                            binding.ownerPluginId,
                            state
                        )
                    }
                }
                .sortedWith(compareBy({ it.ownerPluginId }, { it.bindingId }))
                .firstOrNull()
        AIForegroundService.refreshPluginNotification(
            appContext,
            requireStart = foregroundStateFlow.value != null
        )
    }

    private fun InProcessNotificationState.sanitize(): InProcessNotificationState {
        val cleanTitle = title.trim().take(MAX_TITLE).ifBlank { "AI Limbs" }
        val cleanSummary = summary.trim().take(MAX_SUMMARY)
        val cleanLines = statusLines.map { it.trim().take(MAX_LINE) }.filter { it.isNotBlank() }.take(MAX_LINES)
        val cleanActions = actions
            .asSequence()
            .filter { it.enabled && it.id.isNotBlank() && it.label.isNotBlank() }
            .map { it.copy(id = it.id.trim().take(MAX_ACTION_ID), label = it.label.trim().take(MAX_ACTION_LABEL)) }
            .filter { it.id.isNotBlank() && it.label.isNotBlank() }
            .sortedByDescending { it.priority }
            .distinctBy { it.id }
            .take(MAX_ACTIONS)
            .toList()
        return copy(title = cleanTitle, summary = cleanSummary, statusLines = cleanLines, actions = cleanActions)
    }

    companion object {
        private const val TAG = "PluginNotificationHost"
        private const val HOST_OWNER_ID = "core.notification.host"
        const val NOTIFICATION_SCOPE = "host.notification@1"
        private const val MAX_TITLE = 80
        private const val MAX_SUMMARY = 160
        private const val MAX_LINE = 160
        private const val MAX_LINES = 4
        private const val MAX_ACTIONS = 2
        private const val MAX_ACTION_ID = 128
        private const val MAX_ACTION_LABEL = 40
        const val ACTION_NOTIFICATION = "com.ai.assistance.operit.plugin.NOTIFICATION_ACTION"
        const val EXTRA_BINDING_ID = "binding_id"
        const val EXTRA_ACTION_ID = "action_id"
    }
}

class PluginNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PluginNotificationHost.ACTION_NOTIFICATION) return
        val bindingId = intent.getStringExtra(PluginNotificationHost.EXTRA_BINDING_ID).orEmpty()
        val actionId = intent.getStringExtra(PluginNotificationHost.EXTRA_ACTION_ID).orEmpty()
        if (bindingId.isBlank() || actionId.isBlank()) return
        if (PluginPlatformKernel.isInitialized) {
            PluginPlatformKernel.dispatchNotificationAction(bindingId, actionId)
        }
    }
}
