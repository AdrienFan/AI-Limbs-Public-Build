package com.ai.assistance.operit.core.tools.defaultTool

import android.content.Context
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolResultData
import com.ai.assistance.operit.core.tools.defaultTool.accessbility.AccessibilityUITools
import com.ai.assistance.operit.core.tools.defaultTool.admin.AdminUITools
import com.ai.assistance.operit.core.tools.defaultTool.debugger.DebuggerUITools
import com.ai.assistance.operit.core.tools.defaultTool.root.RootUITools
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardUITools
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
import com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker
import com.ai.assistance.operit.core.tools.system.resident.ResidentCoreProcessIdentity
import com.ai.assistance.operit.core.tools.system.resident.ResidentHostComponentProxy
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.data.repository.UIHierarchyManager
import com.ai.assistance.operit.services.FloatingChatService
import com.ai.assistance.operit.ui.common.displays.UIOperationOverlay
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

internal enum class UiAutomationOperation {
    SNAPSHOT,
    CLICK,
    TAP,
    LONG_PRESS,
    SET_TEXT,
    KEY,
    SWIPE
}

internal enum class UiAutomationBackend {
    ACCESSIBILITY,
    DEBUGGER,
    ROOT,
    UNSUPPORTED
}

internal data class UiAutomationBackendSelection(
    val backend: UiAutomationBackend,
    val shellPermissionLevel: AndroidPermissionLevel? = null,
    val preferredPermissionLevel: AndroidPermissionLevel? = null,
    val preferredAvailable: Boolean = true,
    val fallbackReason: String? = null
)

internal data class UiAutomationRuntimeState(
    val preferredPermissionLevel: AndroidPermissionLevel?,
    val accessibilityAvailable: Boolean,
    val source: String
)

internal fun selectUiAutomationBackend(
    preferredPermissionLevel: AndroidPermissionLevel?,
    preferredAvailable: Boolean,
    accessibilityAvailable: Boolean,
    allowAccessibilityFallback: Boolean
): UiAutomationBackendSelection {
    fun preferred(
        backend: UiAutomationBackend,
        shellLevel: AndroidPermissionLevel? = null
    ) = UiAutomationBackendSelection(
        backend = backend,
        shellPermissionLevel = shellLevel,
        preferredPermissionLevel = preferredPermissionLevel,
        preferredAvailable = preferredAvailable
    )

    val preferredSelection =
        when (preferredPermissionLevel) {
            AndroidPermissionLevel.ACCESSIBILITY ->
                preferred(UiAutomationBackend.ACCESSIBILITY)
            AndroidPermissionLevel.DEBUGGER ->
                preferred(UiAutomationBackend.DEBUGGER, AndroidPermissionLevel.DEBUGGER)
            AndroidPermissionLevel.ADMIN ->
                preferred(UiAutomationBackend.DEBUGGER, AndroidPermissionLevel.ADMIN)
            AndroidPermissionLevel.ROOT ->
                preferred(UiAutomationBackend.ROOT, AndroidPermissionLevel.ROOT)
            AndroidPermissionLevel.STANDARD, null ->
                preferred(UiAutomationBackend.UNSUPPORTED)
        }

    if (
        preferredPermissionLevel in setOf(
            AndroidPermissionLevel.DEBUGGER,
            AndroidPermissionLevel.ADMIN,
            AndroidPermissionLevel.ROOT
        ) &&
            !preferredAvailable &&
            allowAccessibilityFallback &&
            accessibilityAvailable
    ) {
        return UiAutomationBackendSelection(
            backend = UiAutomationBackend.ACCESSIBILITY,
            preferredPermissionLevel = preferredPermissionLevel,
            preferredAvailable = false,
            fallbackReason = "${preferredPermissionLevel?.name?.lowercase()}_unavailable"
        )
    }

    return preferredSelection
}

/**
 * Semantic owner for host.ui.automation@1.
 *
 * Authorization remains in ToolExecutionManager/AiLimbs policy before this layer. This object owns
 * operation semantics and chooses exactly one backend. Android presentation is delegated through
 * UiAutomationPresentation and therefore never becomes Core-owned state.
 */
internal object UiAutomationRuntime {
    private const val TAG = "UiAutomationRuntime"

    private fun hasExplicitDisplay(tool: AITool): Boolean =
        tool.parameters.any { parameter ->
            parameter.name.equals("display", ignoreCase = true) &&
                parameter.value.trim().isNotEmpty()
        }

    private fun isShellBackendAvailable(
        context: Context,
        level: AndroidPermissionLevel
    ): Boolean =
        runCatching {
            val executor = ShellExecutorFactory.getExecutor(context.applicationContext, level)
            executor.isAvailable() && executor.hasPermission().granted
        }.getOrElse { error ->
            AppLogger.w(
                TAG,
                "UI backend availability probe failed level=$level: ${error.message}"
            )
            false
        }

    private suspend fun isAccessibilityBackendAvailable(context: Context): Boolean =
        runCatching {
            UIHierarchyManager.isAccessibilityServiceEnabled(context.applicationContext)
        }.getOrElse { error ->
            AppLogger.w(
                TAG,
                "Accessibility backend availability probe failed: " + error.message
            )
            false
        }

    /**
     * Returns the authoritative UI permission/backend state.
     *
     * Resident Core must not trust its process-local Preferences DataStore cache after Host changes
     * the preferred permission level, and it cannot bind Android Accessibility services directly.
     * Therefore Resident obtains both facts from the AMS-owned Host process.
     */
    suspend fun runtimeState(context: Context): UiAutomationRuntimeState {
        val appContext = context.applicationContext
        if (ResidentCoreProcessIdentity.isCurrentProcessCore()) {
            val hostState =
                runCatching {
                    withContext(Dispatchers.IO) {
                        ResidentHostComponentProxy.request(
                            ResidentComponentProxyBroker.KIND_UI_AUTOMATION_HOST,
                            JSONObject().put("action", "state")
                        )
                    }
                }.getOrElse { error ->
                    AppLogger.w(
                        TAG,
                        "Host UI automation state unavailable; preserving local shell preference only: " +
                            error.message
                    )
                    null
                }

            if (hostState != null && hostState.optBoolean("ok", false)) {
                val preferred =
                    if (hostState.isNull("preferred_permission_level")) {
                        null
                    } else {
                        AndroidPermissionLevel.fromString(
                            hostState.optString("preferred_permission_level")
                        )
                    }
                return UiAutomationRuntimeState(
                    preferredPermissionLevel = preferred,
                    accessibilityAvailable = hostState.optBoolean("accessibility_available", false),
                    source = "resident_host"
                )
            }

            // Fail closed for Accessibility. A process-local preference may still preserve a
            // Debugger/Admin/Root session while Host reconnects, but Resident never binds A11y.
            return UiAutomationRuntimeState(
                preferredPermissionLevel = androidPermissionPreferences.getPreferredPermissionLevel(),
                accessibilityAvailable = false,
                source = "resident_local_fallback"
            )
        }

        return UiAutomationRuntimeState(
            preferredPermissionLevel = androidPermissionPreferences.getPreferredPermissionLevel(),
            accessibilityAvailable = isAccessibilityBackendAvailable(appContext),
            source = "android_host"
        )
    }

    suspend fun resolveBackend(
        context: Context,
        tool: AITool
    ): UiAutomationBackendSelection =
        resolveBackend(context, tool, runtimeState(context))

    suspend fun resolveBackend(
        context: Context,
        tool: AITool,
        state: UiAutomationRuntimeState
    ): UiAutomationBackendSelection {
        val preferred = state.preferredPermissionLevel
        val preferredAvailable =
            when (preferred) {
                AndroidPermissionLevel.DEBUGGER ->
                    isShellBackendAvailable(context, AndroidPermissionLevel.DEBUGGER)
                AndroidPermissionLevel.ADMIN ->
                    isShellBackendAvailable(context, AndroidPermissionLevel.ADMIN)
                AndroidPermissionLevel.ROOT ->
                    isShellBackendAvailable(context, AndroidPermissionLevel.ROOT)
                AndroidPermissionLevel.ACCESSIBILITY ->
                    state.accessibilityAvailable
                AndroidPermissionLevel.STANDARD, null -> false
            }

        val allowAccessibilityFallback = !hasExplicitDisplay(tool)
        val accessibilityAvailable =
            when {
                preferred == AndroidPermissionLevel.ACCESSIBILITY -> preferredAvailable
                preferredAvailable -> false
                !allowAccessibilityFallback -> false
                else -> state.accessibilityAvailable
            }

        AppLogger.d(
            TAG,
            "Resolved UI runtime state source=" + state.source +
                " preferred=" + preferred +
                " accessibilityAvailable=" + state.accessibilityAvailable
        )

        return selectUiAutomationBackend(
            preferredPermissionLevel = preferred,
            preferredAvailable = preferredAvailable,
            accessibilityAvailable = accessibilityAvailable,
            allowAccessibilityFallback = allowAccessibilityFallback
        )
    }

    suspend fun execute(
        context: Context,
        tool: AITool,
        operation: UiAutomationOperation
    ): ToolResult {
        val selection = resolveBackend(context, tool)
        if (selection.backend == UiAutomationBackend.UNSUPPORTED) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "UI automation requires Accessibility, Debugger/Admin, or Root backend"
            )
        }
        val residentCore = ResidentCoreProcessIdentity.isCurrentProcessCore()
        val tools =
            if (residentCore && selection.backend == UiAutomationBackend.ACCESSIBILITY) {
                null
            } else {
                createBackend(context.applicationContext, selection)
            }
        val presentation = UiAutomationPresentation(context.applicationContext)

        AppLogger.d(
            TAG,
            "Executing ${tool.name} operation=$operation " +
                "preferred=${selection.preferredPermissionLevel} " +
                "preferredAvailable=${selection.preferredAvailable} " +
                "effective=${selection.backend} " +
                "shellLevel=${selection.shellPermissionLevel} " +
                "fallback=${selection.fallbackReason ?: "none"}"
        )

        presentation.beginTool(showStatusIndicator = true)
        return try {
            delay(50)
            if (residentCore && selection.backend == UiAutomationBackend.ACCESSIBILITY) {
                executeAccessibilityInHost(tool, operation)
            } else {
                val localTools = checkNotNull(tools)
                when (operation) {
                    UiAutomationOperation.SNAPSHOT -> localTools.getPageInfo(tool)
                    UiAutomationOperation.CLICK -> localTools.clickElement(tool)
                    UiAutomationOperation.TAP -> localTools.tap(tool)
                    UiAutomationOperation.LONG_PRESS -> localTools.longPress(tool)
                    UiAutomationOperation.SET_TEXT -> localTools.setInputText(tool)
                    UiAutomationOperation.KEY -> localTools.pressKey(tool)
                    UiAutomationOperation.SWIPE -> localTools.swipe(tool)
                }
            }
        } finally {
            presentation.endTool()
        }
    }

    private val toolResultJson = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "__type"
    }

    private suspend fun executeAccessibilityInHost(
        tool: AITool,
        operation: UiAutomationOperation
    ): ToolResult {
        val parameters =
            JSONArray().apply {
                tool.parameters.forEach { parameter ->
                    put(
                        JSONObject()
                            .put("name", parameter.name)
                            .put("value", parameter.value)
                    )
                }
            }
        val response =
            withContext(Dispatchers.IO) {
                ResidentHostComponentProxy.request(
                    ResidentComponentProxyBroker.KIND_UI_AUTOMATION_HOST,
                    JSONObject()
                        .put("action", "execute_accessibility")
                        .put("operation", operation.name)
                        .put("tool_name", tool.name)
                        .put("parameters", parameters)
                )
            }
        check(response.optBoolean("ok", false)) {
            response.optString(
                "error",
                "Host Accessibility execution failed: " + operation.name.lowercase()
            )
        }
        val resultData =
            toolResultJson.decodeFromString<ToolResultData>(
                response.getString("result_data")
            )
        return ToolResult(
            toolName = response.optString("tool_name", tool.name),
            success = response.optBoolean("success", false),
            result = resultData,
            error = if (response.isNull("error")) null else response.optString("error")
        )
    }

    private fun createBackend(
        context: Context,
        selection: UiAutomationBackendSelection
    ): StandardUITools =
        when (selection.backend) {
            UiAutomationBackend.ACCESSIBILITY -> AccessibilityUITools(context)
            UiAutomationBackend.DEBUGGER -> {
                val level =
                    checkNotNull(selection.shellPermissionLevel) {
                        "DEBUGGER backend requires an explicit shell permission level"
                    }
                if (level == AndroidPermissionLevel.ADMIN) {
                    AdminUITools(
                        context = context,
                        allowAccessibilityFallback = false,
                        explicitShellPermissionLevel = level
                    )
                } else {
                    DebuggerUITools(
                        context = context,
                        allowAccessibilityFallback = false,
                        explicitShellPermissionLevel = level
                    )
                }
            }
            UiAutomationBackend.ROOT -> RootUITools(context)
            UiAutomationBackend.UNSUPPORTED ->
                error("UNSUPPORTED UI automation backend must be rejected before backend creation")
        }
}

/**
 * Presentation facade for UI automation.
 *
 * In Resident Core it carries neutral JSON only. In Android Host it owns the actual
 * FloatingChatService/UIOperationOverlay objects.
 */
class UiAutomationPresentation(context: Context) {
    private val appContext = context.applicationContext
    private val isResidentCore = ResidentCoreProcessIdentity.isCurrentProcessCore()

    private val localOverlay: UIOperationOverlay by lazy {
        check(!isResidentCore) { "Resident Core must not create UIOperationOverlay" }
        UIOperationOverlay.getInstance(appContext)
    }

    suspend fun beginTool(showStatusIndicator: Boolean) {
        if (isResidentCore) {
            dispatch(
                action = ACTION_TOOL_BEGIN,
                payload = JSONObject().put("show_status_indicator", showStatusIndicator)
            ) {}
            return
        }
        val floating = FloatingChatService.getInstance()
        floating?.setFloatingWindowVisible(false)
        floating?.setStatusIndicatorVisible(showStatusIndicator)
    }

    suspend fun endTool() {
        if (isResidentCore) {
            dispatch(action = ACTION_TOOL_END) {}
            return
        }
        val floating = FloatingChatService.getInstance()
        floating?.setFloatingWindowVisible(true)
        floating?.setStatusIndicatorVisible(false)
    }

    fun showTap(x: Int, y: Int, autoHideDelayMs: Long = 1500L) {
        dispatch(
            action = ACTION_OVERLAY_TAP,
            payload =
                JSONObject()
                    .put("x", x)
                    .put("y", y)
                    .put("auto_hide_delay_ms", autoHideDelayMs)
        ) {
            localOverlay.showTap(x, y, autoHideDelayMs)
        }
    }

    fun showSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        autoHideDelayMs: Long = 1500L
    ) {
        dispatch(
            action = ACTION_OVERLAY_SWIPE,
            payload =
                JSONObject()
                    .put("start_x", startX)
                    .put("start_y", startY)
                    .put("end_x", endX)
                    .put("end_y", endY)
                    .put("auto_hide_delay_ms", autoHideDelayMs)
        ) {
            localOverlay.showSwipe(startX, startY, endX, endY, autoHideDelayMs)
        }
    }

    fun showTextInput(x: Int, y: Int, text: String, autoHideDelayMs: Long = 2000L) {
        dispatch(
            action = ACTION_OVERLAY_TEXT,
            payload =
                JSONObject()
                    .put("x", x)
                    .put("y", y)
                    .put("text", text)
                    .put("auto_hide_delay_ms", autoHideDelayMs)
        ) {
            localOverlay.showTextInput(x, y, text, autoHideDelayMs)
        }
    }

    fun hide() {
        dispatch(action = ACTION_OVERLAY_HIDE) {
            localOverlay.hide()
        }
    }

    fun hideImmediately() {
        dispatch(action = ACTION_OVERLAY_HIDE_IMMEDIATE) {
            localOverlay.hideImmediately()
        }
    }

    private fun dispatch(
        action: String,
        payload: JSONObject = JSONObject(),
        local: () -> Unit
    ) {
        if (!isResidentCore) {
            local()
            return
        }

        runCatching {
            ResidentHostComponentProxy.request(
                ResidentComponentProxyBroker.KIND_UI_AUTOMATION_PRESENTATION,
                JSONObject(payload.toString()).put("action", action)
            )
        }.onFailure { error ->
            AppLogger.w(TAG, "Host UI automation presentation failed action=$action: ${error.message}")
        }
    }

    private companion object {
        private const val TAG = "UiAutomationPresentation"

        const val ACTION_TOOL_BEGIN = "tool_begin"
        const val ACTION_TOOL_END = "tool_end"
        const val ACTION_OVERLAY_TAP = "overlay_tap"
        const val ACTION_OVERLAY_SWIPE = "overlay_swipe"
        const val ACTION_OVERLAY_TEXT = "overlay_text"
        const val ACTION_OVERLAY_HIDE = "overlay_hide"
        const val ACTION_OVERLAY_HIDE_IMMEDIATE = "overlay_hide_immediate"
    }
}
