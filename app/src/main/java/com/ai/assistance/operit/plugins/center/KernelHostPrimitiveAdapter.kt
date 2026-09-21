package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutor
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDispatcher
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionPolicyEngine
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionSession
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionTransport
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsInteractionCyclePolicy
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsInteractionCycleRuntime
import com.ai.assistance.operit.plugins.system.KernelDynamicNavigationJsonServiceV1
import com.ai.assistance.operit.widget.ToolPkgDesktopWidgetHost
import com.ai.assistance.operit.ui.main.screens.ScreenRouteRegistry
import com.ai.assistance.operit.ui.main.screens.Screen
import com.ai.assistance.operit.ui.features.toolbox.layout.ToolboxLayoutController
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Kernel-only adapters behind Host Gateway V1. No business-specific ABI is added here. */
internal class KernelHostPrimitiveAdapter(context: Context, private val runtimeRole: PluginRuntimeRole = PluginRuntimeRole.LEGACY_HOST) {
    private val appContext = context.applicationContext
    private val gatewayBindings = ConcurrentHashMap<String, ExtensionBindingHandle>()

    fun isAvailable(primitiveId: String, operation: String): Boolean =
        "${primitiveId.trim().lowercase()}/${operation.trim().lowercase()}" in SUPPORTED

    suspend fun invoke(
        ownerPluginId: String,
        primitiveId: String,
        operation: String,
        parameters: JSONObject
    ): JSONObject {
        if (runtimeRole != PluginRuntimeRole.UI_PROXY) {
            require(PluginPlatformKernel.isInitialized) { "Plugin kernel is not initialized" }
        }
        val id = primitiveId.trim().lowercase()
        val op = operation.trim().lowercase()
        if (!isAvailable(id, op)) {
            throw PluginInstallException(
                "HOST_PRIMITIVE_OPERATION_NOT_BOUND",
                "Kernel operation is not bound: $id/$op"
            )
        }
        if (runtimeRole == PluginRuntimeRole.BUSINESS && CapabilityRegistry.isOwnedBy(id, CapabilityExecutionOwner.HOST)) {
            return invokeHostOwnedPrimitive(ownerPluginId, id, op, parameters)
        }
        return when (id) {
            "host.network@1" -> invokeNetwork(op)
            "host.ui.surface@1" -> invokeUiSurface(op, parameters)
            "host.capability@1" -> invokeCapability(ownerPluginId, parameters)
            "host.plugin.service@1" -> invokePluginService(op, parameters)
            "host.extension.routing@1" -> invokeExtensionRouting(op, parameters)
            "host.plugin.runtime@1" -> invokePluginRuntime(op, parameters)
            "host.authorization@1" -> evaluateAuthorization(ownerPluginId, parameters)
            "host.privileged.runtime@1" -> com.ai.assistance.operit.core.tools.system.privilege.PrivilegeRuntime.invoke(appContext, ownerPluginId, op, parameters)
            "host.resident.runtime@1" -> com.ai.assistance.operit.core.tools.system.resident.AiLimbsResidentRuntime.invoke(appContext, ownerPluginId, op, parameters)
            "host.ui.layout@1" -> invokeUiLayout(op, parameters)
            "host.interaction.cycle@1" -> invokeInteractionCycle(op, parameters)
            "kernel.plugin.trust@1" -> invokeTrust(op, parameters)
            else -> throw PluginInstallException(
                "HOST_PRIMITIVE_OPERATION_NOT_BOUND",
                "No Kernel adapter for $id/$op"
            )
        }
    }

    private fun invokeHostOwnedPrimitive(
        ownerPluginId: String,
        primitiveId: String,
        operation: String,
        parameters: JSONObject
    ): JSONObject {
        val response = try {
            com.ai.assistance.operit.core.tools.system.resident.ResidentHostComponentProxy.request(
                com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_HOST_PRIMITIVE,
                JSONObject()
                    .put("owner_plugin_id", ownerPluginId)
                    .put("primitive_id", primitiveId)
                    .put("operation", operation)
                    .put("parameters", JSONObject(parameters.toString()))
            )
        } catch (error: Throwable) {
            throw PluginInstallException(
                "HOST_UI_PROXY_UNAVAILABLE",
                "Host-owned primitive could not reach the Android Host: $primitiveId/$operation",
                error
            )
        }
        if (!response.optBoolean("ok", false)) {
            throw PluginInstallException(
                "HOST_UI_PROXY_FAILED",
                response.optString("error", "Host-owned primitive failed: $primitiveId/$operation")
            )
        }
        return response.optJSONObject("result") ?: JSONObject()
    }

    private suspend fun invokeNetwork(operation: String): JSONObject = when (operation) {
        "listeners" -> snapshotTcpListeners()
        else -> unsupported("host.network@1", operation)
    }

    private suspend fun snapshotTcpListeners(): JSONObject {
        // The plugin never receives Shell access. Host executes one fixed read-only command and
        // reduces /proc/net to a de-duplicated TCP LISTEN port list before crossing the ABI.
        val command = "for f in /proc/net/tcp /proc/net/tcp6; do [ -r \"\$f\" ] && cat \"\$f\"; done"
        val shell = executeListenerSnapshotCommand(command)
        if (!shell.success) {
            return JSONObject()
                .put("available", false)
                .put("ports", JSONArray())
                .put("source", "android_proc_net")
                .put("updated_at_epoch_ms", System.currentTimeMillis())
                .put("reason", shell.stderr.trim().ifBlank { "Android listener snapshot is unavailable" })
        }

        val ports = shell.stdout.lineSequence()
            .mapNotNull(::tcpListenPort)
            .distinct()
            .sorted()
            .toList()
        return JSONObject()
            .put("available", true)
            .put("ports", JSONArray(ports))
            .put("source", "android_proc_net")
            .put("updated_at_epoch_ms", System.currentTimeMillis())
    }

    private suspend fun executeListenerSnapshotCommand(command: String): ShellExecutor.CommandResult {
        val reasons = mutableListOf<String>()
        for (level in LISTENER_SNAPSHOT_LEVELS) {
            val executor = ShellExecutorFactory.getExecutor(appContext, level)
            val permission = executor.hasPermission()
            if (!executor.isAvailable() || !permission.granted) {
                reasons += "$level: ${permission.reason}"
                continue
            }
            val result = executor.executeCommand(command)
            if (result.success) return result
            reasons += "$level: ${result.stderr.ifBlank { "exit=${result.exitCode}" }}"
        }
        return ShellExecutor.CommandResult(
            success = false,
            stdout = "",
            stderr = reasons.joinToString("; ").ifBlank { "No supported Host shell backend can read Android TCP listeners" },
            exitCode = -1
        )
    }

    private fun tcpListenPort(line: String): Int? {
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size < 4 || columns[3] != "0A") return null
        val local = columns[1]
        val separator = local.lastIndexOf(':')
        if (separator < 0 || separator == local.lastIndex) return null
        return local.substring(separator + 1).toIntOrNull(16)?.takeIf { it in 1..65535 }
    }

    private suspend fun invokeUiSurface(operation: String, parameters: JSONObject): JSONObject {
        val service = KernelDynamicNavigationJsonServiceV1(
            PluginPlatformKernel.dynamicNavigationRegistry,
            PluginPlatformKernel.uiRegistry,
            PluginPlatformKernel.adminSecurity
        )
        return when (operation) {
            "list" -> service.call("list_surfaces", parameters)
            "register" -> service.call("create_surface", parameters)
            "open" -> openUiSurface(parameters)
            "remove" -> service.call("delete_surface", parameters)
            else -> unsupported("host.ui.surface@1", operation)
        }
    }

    private fun openUiSurface(parameters: JSONObject): JSONObject {
        val surfaceId = parameters.optString("surface_id").trim().takeIf { it.isNotEmpty() }
        val screenId = parameters.optString("screen_id").trim().takeIf { it.isNotEmpty() }
        if ((surfaceId == null) == (screenId == null)) {
            throw PluginInstallException(
                "HOST_UI_TARGET_INVALID",
                "Exactly one of surface_id or screen_id is required"
            )
        }

        val focusKind = parameters.optString("focus_kind").trim().lowercase().takeIf { it.isNotEmpty() }
        val focusId = parameters.optString("focus_id").trim().takeIf { it.isNotEmpty() }
        if ((focusKind == null) != (focusId == null) || (focusKind != null && focusKind !in setOf("plugin", "child"))) {
            throw PluginInstallException(
                "HOST_UI_FOCUS_INVALID",
                "focus_kind and focus_id must be supplied together; focus_kind must be plugin or child"
            )
        }

        val routeArgs = JSONObject()
        val routeId: String
        val result = JSONObject().put("opened", true)
        if (surfaceId != null) {
            val surface = PluginPlatformKernel.dynamicNavigationRegistry.find(surfaceId)
                ?: throw PluginInstallException(
                    "DYNAMIC_SURFACE_NOT_FOUND",
                    "Dynamic surface does not exist: $surfaceId"
                )
            routeId = dynamicNavigationRouteId(surface.id)
            routeArgs.put("surfaceId", surface.id)
            result.put("surface_id", surface.id)
        } else {
            val screen = PluginPlatformKernel.uiRegistry.screen(screenId!!)
                ?: throw PluginInstallException(
                    "PLUGIN_SCREEN_NOT_FOUND",
                    "Plugin screen does not exist: $screenId"
                )
            routeId = "plugin.declarative.page.${screen.id}"
            routeArgs.put("screenId", screen.id)
            result.put("screen_id", screen.id)
        }
        if (focusKind != null && focusId != null) {
            routeArgs.put("focusKind", focusKind)
            routeArgs.put("focusId", focusId)
        }
        launchHostRoute(routeId, routeArgs.toString())
        return result.put("route_id", routeId)
    }

    private fun launchHostRoute(routeId: String, routeArgsJson: String) {
        if (runtimeRole == PluginRuntimeRole.BUSINESS) {
            val flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            com.ai.assistance.operit.core.tools.system.resident.ResidentHostComponentProxy.request(
                com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker.KIND_START_ACTIVITY,
                JSONObject()
                    .put("component_package", appContext.packageName)
                    .put("component_class", "com.ai.assistance.operit.ui.main.MainActivity")
                    .put("flags", flags)
                    .put("extras", JSONObject()
                        .put(ToolPkgDesktopWidgetHost.EXTRA_OPEN_ROUTE_ID, routeId)
                        .put(ToolPkgDesktopWidgetHost.EXTRA_OPEN_ROUTE_ARGS_JSON, routeArgsJson))
            )
            return
        }
        appContext.startActivity(ToolPkgDesktopWidgetHost.buildLaunchIntent(appContext, routeId, routeArgsJson))
    }

    private suspend fun invokeCapability(ownerPluginId: String, parameters: JSONObject): JSONObject {
        val capabilityId = required(parameters, "capability_id").lowercase()
        val args = parameters.optJSONObject("parameters") ?: JSONObject()
        return if (capabilityId.startsWith("plugin.")) {
            PluginPlatformKernel.capabilities.invokePlugin(capabilityId, JSONObject(args.toString()))
        } else {
            dispatcher(ownerPluginId).execute(capabilityId, JSONObject(args.toString()))
        }
    }

    private suspend fun invokePluginService(operation: String, parameters: JSONObject): JSONObject {
        val contributions = PluginPlatformKernel.contributions
        return when (operation) {
            "list" -> JSONObject().put(
                "services",
                JSONArray().apply {
                    contributions.listAll()
                        .filter { it.kind == PluginContributionKind.SERVICE }
                        .forEach { put(serviceJson(it)) }
                }
            )
            "describe" -> {
                val id = required(parameters, "service_id")
                val record = contributions.find(PluginContributionKind.SERVICE, id)
                    ?: throw PluginInstallException("SERVICE_NOT_ACTIVE", "Service is not active: $id")
                JSONObject().put("service", serviceJson(record))
            }
            "call" -> {
                val id = required(parameters, "service_id")
                val method = required(parameters, "operation")
                val args = parameters.optJSONObject("parameters") ?: JSONObject()
                val record = contributions.find(PluginContributionKind.SERVICE, id)
                    ?: throw PluginInstallException("SERVICE_NOT_ACTIVE", "Service is not active: $id")
                val endpoint = record.payload as? PluginServiceEndpoint
                    ?: throw PluginInstallException("SERVICE_NOT_CALLABLE", "Service has no PluginServiceEndpoint: $id")
                endpoint.invoke(method, JSONObject(args.toString()))
            }
            else -> unsupported("host.plugin.service@1", operation)
        }
    }

    private fun serviceJson(record: PluginContributionRecord): JSONObject = JSONObject()
        .put("service_id", record.id)
        .put("owner_plugin_id", record.ownerPluginId)
        .put("api_version", record.apiVersion ?: 0)
        .put("metadata", JSONObject(record.metadata))
        .put("callable", record.payload is PluginServiceEndpoint)

    private fun invokeExtensionRouting(operation: String, parameters: JSONObject): JSONObject {
        val points = PluginPlatformKernel.extensionPoints
        val router = PluginPlatformKernel.extensionRouter
        return when (operation) {
            "list_points" -> JSONObject().put(
                "points",
                JSONArray().apply {
                    points.list().forEach { point ->
                        put(JSONObject().put("point", point.point).put("api_version", point.apiVersion))
                    }
                }
            )
            "list_bindings" -> JSONObject().put(
                "bindings",
                JSONArray().apply { router.listBindings().forEach { put(bindingJson(it)) } }
            )
            "bind" -> {
                val point = required(parameters, "point").lowercase()
                val extensionId = required(parameters, "extension_id")
                val owner = parameters.optString("owner_plugin_id").trim()
                val existing = router.listBindings().firstOrNull {
                    it.point == point && it.extensionId == extensionId &&
                        (owner.isBlank() || it.ownerPluginId == owner)
                }
                if (existing != null) {
                    return JSONObject().put("bound", true).put("already_bound", true)
                        .put("binding", bindingJson(existing))
                }
                val record = PluginPlatformKernel.contributions
                    .findExtension(point, extensionId)
                    ?: throw PluginInstallException("EXTENSION_NOT_ACTIVE", "Extension is not active: $point/$extensionId")
                if (owner.isNotBlank() && record.ownerPluginId != owner) {
                    throw PluginInstallException("EXTENSION_OWNER_MISMATCH", "Extension owner mismatch")
                }
                val handle = router.bind(record)
                val key = bindingKey(record.ownerPluginId, point, extensionId)
                gatewayBindings[key]?.close()
                gatewayBindings[key] = handle
                JSONObject().put("bound", true).put("owner_plugin_id", record.ownerPluginId)
                    .put("point", point).put("extension_id", extensionId)
            }
            "unbind" -> unbindGatewayBinding(parameters)
            else -> unsupported("host.extension.routing@1", operation)
        }
    }
    private fun unbindGatewayBinding(parameters: JSONObject): JSONObject {
        val owner = required(parameters, "owner_plugin_id")
        val point = required(parameters, "point").lowercase()
        val extensionId = required(parameters, "extension_id")
        val key = bindingKey(owner, point, extensionId)
        val handle = gatewayBindings.remove(key)
        if (handle != null) {
            handle.close()
            return JSONObject().put("unbound", true).put("lifecycle_owned", false)
        }
        val stillBound = PluginPlatformKernel.extensionRouter.listBindings().any {
            it.ownerPluginId == owner && it.point == point && it.extensionId == extensionId
        }
        return JSONObject()
            .put("unbound", false)
            .put("still_bound", stillBound)
            .put("lifecycle_owned", stillBound)
            .put("reason", if (stillBound) "Binding is owned by plugin mount lifecycle" else "Binding not found")
    }

    private fun bindingJson(binding: ExtensionBindingSnapshot): JSONObject = JSONObject()
        .put("owner_plugin_id", binding.ownerPluginId)
        .put("point", binding.point)
        .put("extension_id", binding.extensionId)
        .put("api_version", binding.apiVersion)

    private suspend fun invokePluginRuntime(operation: String, parameters: JSONObject): JSONObject {
        val manager = PluginPlatformKernel.manager
        return when (operation) {
            "list" -> JSONObject().put(
                "plugins",
                JSONArray().apply { manager.snapshots().forEach { put(snapshotJson(it)) } }
            )
            "status" -> JSONObject().put("plugin", snapshotJson(manager.snapshot(required(parameters, "plugin_id"))))
            "mount" -> {
                val id = required(parameters, "plugin_id")
                stateJson(manager.enable(id)).put("mounted", true)
            }
            "stop" -> {
                val id = required(parameters, "plugin_id")
                val adminAuthorized = parameters.optBoolean("admin_authorized", false)
                stateJson(manager.disable(id, adminAuthorized)).put("mounted", false)
            }
            else -> unsupported("host.plugin.runtime@1", operation)
        }
    }

    private fun snapshotJson(snapshot: PluginSnapshot): JSONObject = JSONObject()
        .put("plugin_id", snapshot.pluginId)
        .put("versions", JSONArray(snapshot.versions))
        .put("mounted_version", snapshot.mountedVersion ?: JSONObject.NULL)
        .put("active_version", snapshot.persistentState?.activeVersion ?: JSONObject.NULL)
        .put("enabled", snapshot.persistentState?.enabled ?: false)
        .put("state", snapshot.persistentState?.lastState?.name ?: JSONObject.NULL)
        .put("last_error", snapshot.persistentState?.lastError ?: JSONObject.NULL)
        .put("display_name", snapshot.activeManifest?.display?.name ?: JSONObject.NULL)
        .put("contribution_count", snapshot.contributions.size)

    private fun stateJson(state: PluginPersistentState): JSONObject = JSONObject()
        .put("plugin_id", state.pluginId)
        .put("active_version", state.activeVersion ?: JSONObject.NULL)
        .put("previous_version", state.previousVersion ?: JSONObject.NULL)
        .put("rollback_version", state.rollbackVersion ?: JSONObject.NULL)
        .put("retention_limit", state.retentionLimit)
        .put("enabled", state.enabled)
        .put("state", state.lastState.name)
        .put("last_error", state.lastError ?: JSONObject.NULL)

    private suspend fun evaluateAuthorization(ownerPluginId: String, parameters: JSONObject): JSONObject {
        val capabilityId = required(parameters, "capability_id")
        val args = parameters.optJSONObject("parameters") ?: JSONObject()
        val session = AiLimbsExecutionSession(
            AiLimbsExecutionTransport.PLUGIN_RUNTIME,
            "system:$ownerPluginId:policy"
        )
        val engine = AiLimbsExecutionPolicyEngine(appContext, session)
        val invocation = engine.normalize(capabilityId, JSONObject(args.toString()))
        return engine.inspect(invocation).toJson()
            .put("capability_id", capabilityId)
            .put("inspection_only", true)
    }


    private fun invokeUiLayout(operation: String, parameters: JSONObject): JSONObject {
        val controller = ToolboxLayoutController.get(appContext)
        return when (operation) {
            "status" -> uiLayoutState(controller)
            "start" -> {
                val surface = parameters.optString("surface", ToolboxLayoutController.TOOLBOX_SURFACE)
                    .trim().lowercase()
                val mode = parameters.optString("mode", ToolboxLayoutController.LAYOUT_MODE)
                    .trim().lowercase()
                try {
                    controller.start(surface, mode)
                } catch (error: IllegalArgumentException) {
                    throw PluginInstallException("UI_LAYOUT_TARGET_UNSUPPORTED", error.message ?: "Unsupported UI layout target", error)
                }
                val navigate = parameters.optBoolean("navigate", true)
                if (navigate && surface == ToolboxLayoutController.TOOLBOX_SURFACE) {
                    val routeId = ScreenRouteRegistry.routeIdOf(Screen.Toolbox)
                    launchHostRoute(routeId, "{}")
                }
                uiLayoutState(controller)
                    .put("started", true)
                    .put("navigated", navigate)
            }
            "finish" -> {
                controller.finish()
                uiLayoutState(controller).put("finished", true)
            }
            "reset" -> {
                val surface = parameters.optString("surface", ToolboxLayoutController.TOOLBOX_SURFACE)
                    .trim().lowercase()
                try {
                    controller.reset(surface)
                } catch (error: IllegalArgumentException) {
                    throw PluginInstallException("UI_LAYOUT_TARGET_UNSUPPORTED", error.message ?: "Unsupported UI layout target", error)
                }
                uiLayoutState(controller).put("reset", true)
            }
            else -> unsupported("host.ui.layout@1", operation)
        }
    }

    private fun uiLayoutState(controller: ToolboxLayoutController): JSONObject {
        val session = controller.editSession.value
        return JSONObject()
            .put(
                "available_surfaces",
                JSONArray().put(
                    JSONObject()
                        .put("surface", ToolboxLayoutController.TOOLBOX_SURFACE)
                        .put("modes", JSONArray().put(ToolboxLayoutController.LAYOUT_MODE))
                )
            )
            .put("active", session != null)
            .put("surface", session?.surface ?: JSONObject.NULL)
            .put("mode", session?.mode ?: JSONObject.NULL)
            .put("toolbox_order", JSONArray(controller.toolboxOrder.value))
    }

    private fun invokeInteractionCycle(operation: String, parameters: JSONObject): JSONObject {
        val policy = AiLimbsInteractionCyclePolicy(appContext)
        fun statusJson(): JSONObject {
            val runtime = AiLimbsInteractionCycleRuntime.state(appContext).snapshot()
            val accessGate = runtime.optJSONObject("access_gate")
            return policy.snapshot().toJson()
                .put("generation", runtime.optLong("current_generation", runtime.optLong("generation", 0L)))
                .put("cycle_started_at_ms", runtime.optLong("cycle_started_at_ms", 0L))
                .put("expired_pending", runtime.optBoolean("expired_pending", false))
                .put("gate_released", accessGate?.optBoolean("released_for_current_cycle", false) == true)
        }
        return when (operation) {
            "status" -> statusJson()
            "set_timeout" -> {
                val password = required(parameters, "admin_password")
                if (!PluginPlatformKernel.adminSecurity.verifyPassword(password)) {
                    return JSONObject().put("changed", false).put("authorized", false)
                }
                val timeoutMs = parameters.optLong("timeout_ms", -1L)
                if (!AiLimbsInteractionCyclePolicy.isValidTimeout(timeoutMs)) {
                    throw PluginInstallException("INTERACTION_CYCLE_TIMEOUT_INVALID", "Invalid AI Limbs interaction cycle timeout")
                }
                val before = policy.timeoutMs()
                policy.setTimeoutMs(timeoutMs)
                statusJson()
                    .put("changed", before != timeoutMs)
                    .put("authorized", true)
            }
            "reset" -> {
                val password = required(parameters, "admin_password")
                if (!PluginPlatformKernel.adminSecurity.verifyPassword(password)) {
                    return JSONObject().put("reset", false).put("authorized", false)
                }
                val reset = AiLimbsInteractionCycleRuntime.reset(appContext)
                statusJson()
                    .put("reset", true)
                    .put("authorized", true)
                    .put("generation", reset.generation)
                    .put("reset_applied_immediately", reset.appliedImmediately)
                    .put("cycle_started_at_ms", reset.cycleStartedAtMs)
            }
            "release_gate" -> {
                val password = required(parameters, "admin_password")
                if (!PluginPlatformKernel.adminSecurity.verifyPassword(password)) {
                    return JSONObject().put("gate_released", false).put("authorized", false)
                }
                val generation = AiLimbsInteractionCycleRuntime.releaseGate(appContext)
                statusJson()
                    .put("gate_released", true)
                    .put("authorized", true)
                    .put("generation", generation)
            }
            "close" -> {
                val password = required(parameters, "admin_password")
                if (!PluginPlatformKernel.adminSecurity.verifyPassword(password)) {
                    return JSONObject().put("closed", false).put("authorized", false)
                }
                val close = AiLimbsInteractionCycleRuntime.close(appContext)
                policy.snapshot().toJson()
                    .put("closed", true)
                    .put("authorized", true)
                    .put("generation", close.generation)
                    .put("next_generation", close.nextGeneration)
                    .put("close_applied_immediately", close.appliedImmediately)
            }
            else -> unsupported("host.interaction.cycle@1", operation)
        }
    }

    private fun invokeTrust(operation: String, parameters: JSONObject): JSONObject = when (operation) {
        "status" -> PluginTrustKeyringV1.statusJson()
            .put("plugin_format", PluginAbi.FORMAT)
            .put("plugin_schema_version", PluginAbi.SCHEMA_VERSION)
            .put("integrity_algorithm", "SHA-256")
            .put("signature_algorithm", "Ed25519")
        "verify_package" -> verifyPackage(parameters)
        "verify_detached" -> verifyDetachedTrust(parameters)
        "install_keyring" -> installTrustKeyring(parameters)
        else -> unsupported("kernel.plugin.trust@1", operation)
    }

    private fun verifyDetachedTrust(parameters: JSONObject): JSONObject {
        val signerId = required(parameters, "signer_id")
        val purpose = required(parameters, "purpose").lowercase()
        val role = parameters.optString("role").trim().lowercase().takeIf { it.isNotEmpty() }
        val payload = decodeBase64(parameters, "payload_base64")
        val signature = decodeBase64(parameters, "signature_base64")
        val trusted = PluginTrustKeyringV1.verifyDetached(signerId, purpose, role, payload, signature)
        return JSONObject()
            .put("trusted", trusted)
            .put("signer_id", signerId)
            .put("purpose", purpose)
            .put("role", role ?: JSONObject.NULL)
            .put("keyring_version", PluginTrustKeyringV1.current().version)
    }

    private fun installTrustKeyring(parameters: JSONObject): JSONObject {
        val keyring = decodeBase64(parameters, "keyring_base64")
        val signature = decodeBase64(parameters, "signature_base64")
        val installed = PluginTrustKeyringV1.installSignedKeyring(keyring, signature)
        return PluginTrustKeyringV1.statusJson()
            .put("installed", true)
            .put("installed_version", installed.version)
    }

    private fun decodeBase64(parameters: JSONObject, key: String): ByteArray = try {
        Base64.getDecoder().decode(required(parameters, key))
    } catch (error: IllegalArgumentException) {
        throw PluginInstallException("HOST_GATEWAY_BASE64_INVALID", "$key is not valid Base64", error)
    }

    private fun verifyPackage(parameters: JSONObject): JSONObject {
        val source = File(required(parameters, "path")).canonicalFile
        if (!source.isFile || !source.name.lowercase().endsWith(PluginAbi.PACKAGE_EXTENSION)) {
            throw PluginInstallException("PACKAGE_MISSING", "A readable .ailp path is required")
        }
        val probeRoot = File(appContext.cacheDir, "plugin-trust-probe/${System.nanoTime()}")
        val contentDir = File(probeRoot, "content")
        return try {
            probeRoot.mkdirs()
            val verified = PluginPackageVerifier(PluginPlatformKernel.officialIdentities).verifyAndExtract(source, contentDir)
            val trust = StrictPluginTrustVerifier.verify(
                source,
                contentDir,
                verified.manifest,
                verified.packageSha256
            )
            JSONObject()
                .put("plugin_id", verified.manifest.pluginId)
                .put("version", verified.manifest.version)
                .put("package_sha256", verified.packageSha256)
                .put("entry_count", verified.entryCount)
                .put("extracted_bytes", verified.extractedBytes)
                .put("trust_verdict", trust.verdict.name)
                .put("trusted", trust.isTrusted)
                .put("signer_id", trust.signerId ?: JSONObject.NULL)
                .put("reason", trust.reason ?: JSONObject.NULL)
        } finally {
            probeRoot.deleteRecursively()
        }
    }

    private fun dispatcher(ownerPluginId: String): AiLimbsDispatcher {
        val session = AiLimbsExecutionSession(
            AiLimbsExecutionTransport.PLUGIN_RUNTIME,
            "system:$ownerPluginId"
        )
        return AiLimbsDispatcher(appContext, AiLimbsExecutionPolicyEngine(appContext, session))
    }

    private fun bindingKey(owner: String, point: String, extensionId: String): String =
        "$owner|$point|$extensionId"

    private fun required(parameters: JSONObject, key: String): String =
        parameters.optString(key).trim().takeIf { it.isNotEmpty() }
            ?: throw PluginInstallException("HOST_GATEWAY_FIELD_REQUIRED", "$key is required")

    private fun unsupported(primitiveId: String, operation: String): Nothing =
        throw PluginInstallException(
            "HOST_OPERATION_UNSUPPORTED",
            "$primitiveId does not support Kernel operation=$operation"
        )

    private companion object {
        private val HOST_OWNED_PRIMITIVES = setOf(
            "host.ui.layout@1",
            "host.privileged.runtime@1"
        )

        init {
            check(
                HOST_OWNED_PRIMITIVES ==
                    CapabilityRegistry.idsOwnedBy(CapabilityExecutionOwner.HOST)
            ) {
                "Legacy Kernel Host-owned primitive mirror drifted from CapabilityRegistry"
            }
        }

        val LISTENER_SNAPSHOT_LEVELS = listOf(
            AndroidPermissionLevel.DEBUGGER,
            AndroidPermissionLevel.ROOT,
            AndroidPermissionLevel.STANDARD
        )
        val SUPPORTED = setOf(
            "host.network@1/listeners",
            "host.ui.surface@1/list",
            "host.ui.surface@1/register",
            "host.ui.surface@1/open",
            "host.ui.surface@1/remove",
            "host.capability@1/invoke",
            "host.plugin.service@1/list",
            "host.plugin.service@1/describe",
            "host.plugin.service@1/call",
            "host.extension.routing@1/list_points",
            "host.extension.routing@1/list_bindings",
            "host.extension.routing@1/bind",
            "host.extension.routing@1/unbind",
            "host.plugin.runtime@1/list",
            "host.plugin.runtime@1/status",
            "host.plugin.runtime@1/mount",
            "host.plugin.runtime@1/stop",
            "host.authorization@1/evaluate",
            "host.privileged.runtime@1/status",
            "host.privileged.runtime@1/pair",
            "host.privileged.runtime@1/prepare",
            "host.privileged.runtime@1/stop",
            "host.privileged.runtime@1/select",
            "host.resident.runtime@1/status",
            "host.resident.runtime@1/set_enabled",
            "host.resident.runtime@1/start",
            "host.resident.runtime@1/stop",
            "host.resident.runtime@1/core_status",
            "host.resident.runtime@1/core_probe",
            "host.resident.runtime@1/core_stop",
            "host.ui.layout@1/status",
            "host.ui.layout@1/start",
            "host.ui.layout@1/finish",
            "host.ui.layout@1/reset",
            "host.interaction.cycle@1/status",
            "host.interaction.cycle@1/set_timeout",
            "host.interaction.cycle@1/reset",
            "host.interaction.cycle@1/release_gate",
            "host.interaction.cycle@1/close",
            "kernel.plugin.trust@1/status",
            "kernel.plugin.trust@1/verify_package",
            "kernel.plugin.trust@1/verify_detached",
            "kernel.plugin.trust@1/install_keyring"
        )
    }
}
