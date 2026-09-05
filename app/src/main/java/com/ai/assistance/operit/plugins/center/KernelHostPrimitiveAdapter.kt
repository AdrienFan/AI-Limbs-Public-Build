package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsDispatcher
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionPolicyEngine
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionSession
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionTransport
import com.ai.assistance.operit.plugins.system.KernelDynamicNavigationJsonServiceV1
import com.ai.assistance.operit.widget.ToolPkgDesktopWidgetHost
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Kernel-only adapters behind Host Gateway V1. No business-specific ABI is added here. */
internal class KernelHostPrimitiveAdapter(context: Context) {
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
        require(PluginPlatformKernel.isInitialized) { "Plugin kernel is not initialized" }
        val id = primitiveId.trim().lowercase()
        val op = operation.trim().lowercase()
        if (!isAvailable(id, op)) {
            throw PluginInstallException(
                "HOST_PRIMITIVE_OPERATION_NOT_BOUND",
                "Kernel operation is not bound: $id/$op"
            )
        }
        return when (id) {
            "host.ui.surface@1" -> invokeUiSurface(op, parameters)
            "host.capability@1" -> invokeCapability(ownerPluginId, parameters)
            "host.plugin.service@1" -> invokePluginService(op, parameters)
            "host.extension.routing@1" -> invokeExtensionRouting(op, parameters)
            "host.plugin.runtime@1" -> invokePluginRuntime(op, parameters)
            "host.authorization@1" -> evaluateAuthorization(ownerPluginId, parameters)
            "kernel.plugin.trust@1" -> invokeTrust(op, parameters)
            else -> throw PluginInstallException(
                "HOST_PRIMITIVE_OPERATION_NOT_BOUND",
                "No Kernel adapter for $id/$op"
            )
        }
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
        appContext.startActivity(
            ToolPkgDesktopWidgetHost.buildLaunchIntent(appContext, routeId, routeArgs.toString())
        )
        return result.put("route_id", routeId)
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
        val SUPPORTED = setOf(
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
            "kernel.plugin.trust@1/status",
            "kernel.plugin.trust@1/verify_package",
            "kernel.plugin.trust@1/verify_detached",
            "kernel.plugin.trust@1/install_keyring"
        )
    }
}
