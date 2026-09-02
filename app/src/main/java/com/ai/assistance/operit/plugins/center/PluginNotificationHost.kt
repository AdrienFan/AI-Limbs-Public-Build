package com.ai.assistance.operit.plugins.center

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ai.assistance.operit.R
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bindings = ConcurrentHashMap<String, Binding>()
    private val ownerBindings = ConcurrentHashMap<String, String>()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AI Limbs 插件通知",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "由 AI Limbs Notification Host Surface 统一渲染" }
            )
        }
    }

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
                val sanitized = raw?.sanitize()
                binding.latest = sanitized
                if (sanitized == null) cancelNotification(bindingId) else render(bindingId, sanitized)
            }
        }
        return AutoCloseable {
            bindings.remove(bindingId)?.job?.cancel()
            ownerBindings.remove(ownerPluginId, bindingId)
            cancelNotification(bindingId)
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
        bindings.forEach { (id, binding) -> binding.latest?.let { render(id, it) } }
    }

    fun clear() {
        bindings.keys.toList().forEach(::cancelNotification)
        bindings.values.forEach { it.job?.cancel() }
        bindings.clear()
        ownerBindings.clear()
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

    private fun render(bindingId: String, state: InProcessNotificationState) {
        val detail = state.statusLines.joinToString("\n")
        val contentText = state.summary.ifBlank { state.statusLines.firstOrNull().orEmpty() }
        val launchPendingIntent = launchPendingIntent(bindingId)
        val expanded = createExpandedView(bindingId, state, detail, launchPendingIntent)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(state.title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(expanded)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)

        launchPendingIntent?.let(builder::setContentIntent)
        runCatching { manager.notify(bindingId, NOTIFICATION_ID, builder.build()) }
            .onFailure { AppLogger.w(TAG, "Notification render skipped: ${it.message}", it) }
    }

    private fun createExpandedView(
        bindingId: String,
        state: InProcessNotificationState,
        detail: String,
        launchPendingIntent: PendingIntent?
    ): RemoteViews {
        val views = RemoteViews(appContext.packageName, R.layout.notification_plugin_surface)
        val nightMode =
            appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val primary = if (nightMode) Color.WHITE else Color.rgb(32, 33, 36)
        val secondary = if (nightMode) Color.rgb(210, 214, 220) else Color.rgb(82, 86, 94)
        val actionColor = if (nightMode) Color.rgb(138, 180, 248) else Color.rgb(25, 103, 210)
        views.setTextViewText(R.id.notification_surface_title, state.title)
        views.setTextColor(R.id.notification_surface_title, primary)
        launchPendingIntent?.let { views.setOnClickPendingIntent(R.id.notification_surface_title, it) }
        val body = buildString {
            state.summary.takeIf { it.isNotBlank() }?.let { append(it) }
            if (detail.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(detail)
            }
        }
        views.setTextViewText(R.id.notification_surface_details, body)
        views.setTextColor(R.id.notification_surface_details, secondary)
        bindAction(views, R.id.notification_surface_action_primary, state.actions.getOrNull(0), bindingId, actionColor)
        bindAction(views, R.id.notification_surface_action_secondary, state.actions.getOrNull(1), bindingId, actionColor)
        return views
    }

    private fun bindAction(
        views: RemoteViews,
        viewId: Int,
        action: com.ai.limbs.plugin.runtime.InProcessNotificationAction?,
        bindingId: String,
        textColor: Int
    ) {
        if (action == null) {
            views.setViewVisibility(viewId, View.GONE)
            return
        }
        views.setViewVisibility(viewId, View.VISIBLE)
        views.setTextViewText(viewId, action.label)
        views.setTextColor(viewId, textColor)
        views.setOnClickPendingIntent(viewId, actionPendingIntent(bindingId, action.id))
        views.setContentDescription(viewId, action.label)
    }

    private fun launchPendingIntent(bindingId: String): PendingIntent? =
        appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.let { launch ->
            PendingIntent.getActivity(
                appContext,
                bindingId.hashCode(),
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

    private fun actionPendingIntent(bindingId: String, actionId: String): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            31 * bindingId.hashCode() + actionId.hashCode(),
            Intent(appContext, PluginNotificationActionReceiver::class.java)
                .setAction(ACTION_NOTIFICATION)
                .putExtra(EXTRA_BINDING_ID, bindingId)
                .putExtra(EXTRA_ACTION_ID, actionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun cancelNotification(bindingId: String) {
        manager.cancel(bindingId, NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "PluginNotificationHost"
        private const val HOST_OWNER_ID = "core.notification.host"
        const val NOTIFICATION_SCOPE = "host.notification@1"
        private const val CHANNEL_ID = "ai_limbs_plugin_notification"
        private const val NOTIFICATION_ID = 7053
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
