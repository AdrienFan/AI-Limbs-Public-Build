package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
import com.ai.assistance.operit.core.tools.system.Terminal
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private data class AiLimbsAvailabilityResult(
    val available: Boolean,
    val reasonCode: String? = null,
    val reason: String? = null,
    val nextAction: JSONObject? = null,
    val prerequisites: List<String> = emptyList()
)

/**
 * Transport-neutral policy kernel shared by discovery and execution.
 *
 * Resolver calls inspectForResolver; Dispatcher calls normalize and evaluate. Domain services still
 * own their atomic final checks because state can change after this preflight.
 */
class AiLimbsExecutionPolicyEngine(
    context: Context,
    val session: AiLimbsExecutionSession
) {
    private val appContext = context.applicationContext
    private val handler = AIToolHandler.getInstance(appContext)
    private val permissionSystem = ToolPermissionSystem.getInstance(appContext)
    private val uiCapabilities = AiLimbsUiCapabilityService(appContext)
    private val terminal = Terminal.getInstance(appContext)
    private val receipts = AiLimbsAccessGate(appContext)

    internal fun normalize(tool: String, args: JSONObject): AiLimbsNormalizedInvocation {
        val registration =
            AiLimbsCapabilityRegistry.registrationForInvokeName(tool)
                ?: throw IllegalArgumentException("Unknown AI Limbs tool: " + tool)

        val canonicalName: String
        val targetName: String
        val parameters: JSONObject
        val route: AiLimbsCapabilityRoute
        val sourceEnabled: Boolean
        val spec: AiLimbsPolicySpec

        when (registration) {
            is AiLimbsCapabilityRegistration.Core -> {
                val core = registration.registration
                canonicalName = core.catalogEntry.targetToolName
                sourceEnabled = core.catalogEntry.sourceEnabled
                when (val coreRoute = core.route) {
                    AiLimbsCoreRoute.ForwardHostTool -> {
                        targetName = canonicalName
                        parameters = args
                        route = AiLimbsCapabilityRoute.HostTool(targetName)
                        spec = AiLimbsExecutionPolicyDescriptor.specForHostTool(targetName, parameters)
                    }
                    is AiLimbsCoreRoute.Local -> {
                        if (coreRoute.operation == AiLimbsCoreLocalOperation.HOST_TOOL_EXECUTE) {
                            targetName = args.optString("name").trim()
                            require(targetName.isNotBlank()) { "Missing host tool name" }
                            require(!isReservedPluginCapabilityName(targetName)) {
                                "Host tool target uses reserved plugin capability namespace: $targetName"
                            }
                            parameters = args.optJSONObject("parameters") ?: JSONObject()
                            route = AiLimbsCapabilityRoute.HostTool(targetName)
                            spec = AiLimbsExecutionPolicyDescriptor.specForHostTool(targetName, parameters)
                        } else {
                            targetName = canonicalName
                            parameters = args
                            route = AiLimbsCapabilityRoute.Core(core)
                            spec = AiLimbsExecutionPolicyDescriptor.specForCoreRoute(coreRoute)
                        }
                    }
                    else -> {
                        targetName = canonicalName
                        parameters = args
                        route = AiLimbsCapabilityRoute.Core(core)
                        spec = AiLimbsExecutionPolicyDescriptor.specForCoreRoute(coreRoute)
                    }
                }
            }
            is AiLimbsCapabilityRegistration.Plugin -> {
                val plugin = registration.registration
                canonicalName = plugin.catalogEntry.targetToolName
                targetName = canonicalName
                parameters = args
                route = AiLimbsCapabilityRoute.Plugin(plugin)
                sourceEnabled = plugin.catalogEntry.sourceEnabled
                spec = AiLimbsExecutionPolicyDescriptor.specForPluginCapability()
            }
        }

        return AiLimbsNormalizedInvocation(
            requestedName = tool,
            canonicalName = canonicalName,
            targetName = targetName,
            parameters = parameters,
            route = route,
            sourceEnabled = sourceEnabled,
            spec = spec
        )
    }

    internal suspend fun inspect(
        invocation: AiLimbsNormalizedInvocation
    ): AiLimbsPolicyInspection =
        inspectSpec(
            targetName = invocation.targetName,
            parameters = invocation.parameters,
            route = invocation.route,
            sourceEnabled = invocation.sourceEnabled,
            spec = invocation.spec
        )

    internal suspend fun inspectForResolver(
        targetName: String,
        entry: ToolCatalogEntry
    ): AiLimbsPolicyInspection {
        val registration = AiLimbsCapabilityRegistry.registrationForInvokeName(targetName)
        val route: AiLimbsCapabilityRoute
        val spec: AiLimbsPolicySpec
        when (registration) {
            is AiLimbsCapabilityRegistration.Core -> {
                val core = registration.registration
                if (core.route == AiLimbsCoreRoute.ForwardHostTool) {
                    route = AiLimbsCapabilityRoute.HostTool(targetName)
                    spec = AiLimbsExecutionPolicyDescriptor.specForHostTool(targetName, JSONObject())
                } else {
                    route = AiLimbsCapabilityRoute.Core(core)
                    spec = AiLimbsExecutionPolicyDescriptor.specForCoreRoute(core.route)
                }
            }
            is AiLimbsCapabilityRegistration.Plugin -> {
                route = AiLimbsCapabilityRoute.Plugin(registration.registration)
                spec = AiLimbsExecutionPolicyDescriptor.specForPluginCapability()
            }
            null -> {
                route = AiLimbsCapabilityRoute.HostTool(targetName)
                spec = AiLimbsExecutionPolicyDescriptor.specForHostTool(targetName, JSONObject())
            }
        }
        val inspection =
            inspectSpec(
                targetName = targetName,
                parameters = JSONObject(),
                route = route,
                sourceEnabled = entry.sourceEnabled,
                spec = spec
            )
        val missingReceipt = receipts.firstMissing(spec.requiredReceipts)
        return if (missingReceipt == null) {
            inspection
        } else {
            missingReceiptInspection(inspection, missingReceipt)
        }
    }

    internal suspend fun evaluate(
        invocation: AiLimbsNormalizedInvocation
    ): AiLimbsPolicyDecision {
        val inspection = inspect(invocation)
        val missingReceipt = receipts.firstMissing(invocation.spec.requiredReceipts)
        if (missingReceipt != null) {
            return AiLimbsPolicyDecision(
                proceed = false,
                inspection = missingReceiptInspection(inspection, missingReceipt)
            )
        }
        if (!inspection.available || inspection.permission == PermissionLevel.FORBID.name) {
            return AiLimbsPolicyDecision(proceed = false, inspection = inspection)
        }
        if (
            inspection.permission == PermissionLevel.ASK.name &&
                !invocation.spec.hostPermissionEnforced
        ) {
            val granted =
                corePermissionMutex.withLock {
                    permissionSystem.checkToolPermission(toAiTool(invocation))
                }
            if (!granted) {
                return AiLimbsPolicyDecision(
                    proceed = false,
                    inspection =
                        inspection.copy(
                            outcome = AiLimbsPolicyOutcome.FORBID,
                            reasonCode = "PERMISSION_DENIED",
                            reason = "The user denied the AI Limbs capability.",
                            nextAction = null
                        )
                )
            }
            return AiLimbsPolicyDecision(
                proceed = true,
                inspection = inspection.copy(outcome = AiLimbsPolicyOutcome.ALLOW),
                confirmedDuringEvaluation = true
            )
        }
        return AiLimbsPolicyDecision(proceed = true, inspection = inspection)
    }

    internal fun recordSuccessfulExecution(
        invocation: AiLimbsNormalizedInvocation,
        result: JSONObject
    ) {
        receipts.recordSuccessfulRead(invocation, result)
    }

    fun resetSessionReceipts(): JSONObject {
        receipts.resetForContextBoundary()
        return JSONObject()
            .put("success", true)
            .put("scope_id", session.scopeId)
            .put("transport", session.transport.wireValue)
            .put("receipts_cleared", true)
    }

    fun describePolicy(): JSONObject =
        JSONObject()
            .put("success", true)
            .put("module", "AI Limbs Execution Policy Engine")
            .put("transport_neutral", true)
            .put("session_scope", session.scopeId)
            .put("transport", session.transport.wireValue)
            .put("bootstrap_version", AiLimbsSystemAccessPrompt.version)
            .put("policy", AiLimbsExecutionPolicyDescriptor.summaryJson())

    fun transportInvocation(name: String, parameters: JSONObject): JSONObject =
        when (session.transport) {
            AiLimbsExecutionTransport.RDC ->
                JSONObject()
                    .put("tool", "start_process")
                    .put(
                        "arguments",
                        JSONObject()
                            .put("shell", "operit")
                            .put(
                                "command",
                                JSONObject()
                                    .put("name", name)
                                    .put("parameters", parameters)
                                    .toString()
                            )
                    )
            AiLimbsExecutionTransport.TRIGGERCMD ->
                AiLimbsTriggerCmdContract.transportInvocation(name, parameters)
            AiLimbsExecutionTransport.EXTERNAL_HTTP ->
                JSONObject()
                    .put("method", "POST")
                    .put("path", "/api/ai-limbs/tool")
                    .put(
                        "body",
                        JSONObject()
                            .put("tool", name)
                            .put("args", parameters)
                    )
            AiLimbsExecutionTransport.PLUGIN_RUNTIME ->
                JSONObject()
                    .put("type", "PLUGIN_CAPABILITY_INVOKE")
                    .put("capability", name)
                    .put("parameters", JSONObject(parameters.toString()))
        }

    internal fun rejectionJson(
        invocation: AiLimbsNormalizedInvocation,
        decision: AiLimbsPolicyDecision
    ): JSONObject {
        val inspection = decision.inspection
        return JSONObject()
            .put("success", false)
            .put("error_code", inspection.reasonCode ?: "POLICY_REJECTED")
            .put("policy_outcome", inspection.outcome.name)
            .put("requested_name", invocation.requestedName)
            .put("canonical_name", invocation.canonicalName)
            .put("target_name", invocation.targetName)
            .put("effect", inspection.effect.name)
            .put("domain", inspection.domain.name)
            .put("required_receipts", JSONArray(inspection.requiredReceipts.map { it.name }))
            .put("next_action", inspection.nextAction ?: JSONObject.NULL)
            .put("policy_version", AiLimbsExecutionPolicyDescriptor.policyVersion)
            .put("error", inspection.reason ?: "AI Limbs execution policy rejected the capability.")
    }

    private suspend fun inspectSpec(
        targetName: String,
        parameters: JSONObject,
        route: AiLimbsCapabilityRoute,
        sourceEnabled: Boolean,
        spec: AiLimbsPolicySpec
    ): AiLimbsPolicyInspection {
        val permission =
            if (spec.permissionMode == AiLimbsPermissionMode.PROTOCOL_ALLOW) {
                PermissionLevel.ALLOW
            } else {
                permissionSystem.getToolPermissionOverride(targetName)
                    ?: permissionSystem.masterSwitchFlow.first()
            }
        val availability =
            readAvailability(
                targetName = targetName,
                parameters = parameters,
                route = route,
                sourceEnabled = sourceEnabled,
                spec = spec
            )
        val outcome =
            when {
                !availability.available -> AiLimbsPolicyOutcome.FORBID
                permission == PermissionLevel.FORBID -> AiLimbsPolicyOutcome.FORBID
                permission == PermissionLevel.ASK -> AiLimbsPolicyOutcome.ASK
                else -> AiLimbsPolicyOutcome.ALLOW
            }
        val permissionReason =
            if (permission == PermissionLevel.FORBID && availability.available) {
                "ToolPermissionSystem forbids " + targetName + "."
            } else {
                availability.reason
            }
        val permissionReasonCode =
            if (permission == PermissionLevel.FORBID && availability.available) {
                "PERMISSION_FORBID"
            } else {
                availability.reasonCode
            }
        return AiLimbsPolicyInspection(
            outcome = outcome,
            permission = permission.name,
            available = availability.available && permission != PermissionLevel.FORBID,
            effect = spec.effect,
            domain = spec.domain,
            requiredReceipts = spec.requiredReceipts,
            reasonCode = permissionReasonCode,
            reason = permissionReason,
            nextAction = availability.nextAction,
            prerequisites = availability.prerequisites,
            permissionEnforcedBy =
                when {
                    spec.permissionMode == AiLimbsPermissionMode.PROTOCOL_ALLOW ->
                        "AiLimbsExecutionPolicyEngine"
                    spec.hostPermissionEnforced ->
                        "AiLimbsExecutionPolicyEngine + ToolExecutionManager"
                    else ->
                        "AiLimbsExecutionPolicyEngine + ToolPermissionSystem"
                },
            payloadKind = spec.payloadKind
        )
    }

    private suspend fun readAvailability(
        targetName: String,
        parameters: JSONObject,
        route: AiLimbsCapabilityRoute,
        sourceEnabled: Boolean,
        spec: AiLimbsPolicySpec
    ): AiLimbsAvailabilityResult {
        if (!sourceEnabled) {
            return AiLimbsAvailabilityResult(
                available = false,
                reasonCode = "SOURCE_DISABLED",
                reason = "The capability source is disabled."
            )
        }

        val coreRegistration = (route as? AiLimbsCapabilityRoute.Core)?.registration
        if (spec.hostPermissionEnforced) {
            handler.registerDefaultTools()
            if (targetName !in handler.getAllToolNames()) {
                return AiLimbsAvailabilityResult(
                    available = false,
                    reasonCode = "POLICY_TARGET_NOT_REGISTERED",
                    reason = "The normalized host target is not registered: " + targetName
                )
            }
        }

        if (AiLimbsExecutionPolicyDescriptor.isUiTool(targetName)) {
            val status = uiCapabilities.readStatus()
            val subagent = targetName.startsWith("Automatic_ui_subagent:")
            val ready = if (subagent) status.uiSubagentReady else status.directUiReady
            if (!ready) {
                return AiLimbsAvailabilityResult(
                    available = false,
                    reasonCode = "UI_NOT_READY",
                    reason = "The required AI Limbs UI capability is not ready.",
                    nextAction =
                        JSONObject()
                            .put("type", "AUTHORIZE_UI")
                            .put(
                                "instruction",
                                status.nextAction ?: "Authorize the selected AI Limbs UI backend."
                            ),
                    prerequisites =
                        if (subagent) {
                            listOf("ui_subagent_ready=true")
                        } else {
                            listOf("direct_ui_ready=true")
                        }
                )
            }
        }

        if (
            coreRegistration?.provider == AiLimbsCoreProvider.BRIDGE &&
                coreRegistration.availabilityPolicy == AiLimbsCoreAvailabilityPolicy.BRIDGE_RECONNECT
        ) {
            val bridgeActionCapabilityActive =
                PluginPlatformKernel.isInitialized &&
                    PluginPlatformKernel.isStarted &&
                    BRIDGE_ACTION_CAPABILITY_ID in PluginPlatformKernel.capabilities.activeIds()
            if (!bridgeActionCapabilityActive) {
                return AiLimbsAvailabilityResult(
                    available = false,
                    reasonCode = "BRIDGE_RECONNECT_UNAVAILABLE",
                    reason = "Bridge plugin action capability is not active."
                )
            }
        }

        val usesUbuntu =
            coreRegistration?.provider == AiLimbsCoreProvider.UBUNTU ||
                AiLimbsExecutionPolicyDescriptor.isUbuntuTool(targetName, parameters)
        if (usesUbuntu) {
            return ubuntuAvailability(coreRegistration)
        }
        return AiLimbsAvailabilityResult(available = true)
    }

    private fun ubuntuAvailability(
        registration: AiLimbsCoreCapabilityRegistration?
    ): AiLimbsAvailabilityResult {
        val phase = terminal.currentUbuntuRuntimeState().phase.name
        val prerequisites = listOf("Ubuntu runtime state: " + phase)
        return when (registration?.availabilityPolicy) {
            AiLimbsCoreAvailabilityPolicy.UBUNTU_START ->
                when (phase) {
                    "STARTING", "STOPPING" ->
                        AiLimbsAvailabilityResult(
                            false,
                            "UBUNTU_TRANSITION",
                            "Ubuntu is currently " + phase + ".",
                            prerequisites = prerequisites
                        )
                    "RUNNING" ->
                        AiLimbsAvailabilityResult(
                            false,
                            "UBUNTU_ALREADY_RUNNING",
                            "Ubuntu is already RUNNING.",
                            prerequisites = prerequisites
                        )
                    else -> AiLimbsAvailabilityResult(true, prerequisites = prerequisites)
                }
            AiLimbsCoreAvailabilityPolicy.UBUNTU_STOP -> {
                if (phase != "RUNNING") {
                    AiLimbsAvailabilityResult(
                        false,
                        "UBUNTU_NOT_RUNNING",
                        "Ubuntu is " + phase + ".",
                        prerequisites = prerequisites
                    )
                } else {
                    val usage = terminal.currentUbuntuUsageState()
                    if (usage.userInterfaceClients > 0 || usage.hiddenAiOperations > 0) {
                        AiLimbsAvailabilityResult(
                            false,
                            "UBUNTU_IN_USE",
                            "Ubuntu is still used by another interface or hidden operation.",
                            prerequisites =
                                prerequisites +
                                    ("Ubuntu participants: " + usage.participantCount)
                        )
                    } else {
                        AiLimbsAvailabilityResult(true, prerequisites = prerequisites)
                    }
                }
            }
            AiLimbsCoreAvailabilityPolicy.UBUNTU_STATUS,
            AiLimbsCoreAvailabilityPolicy.UBUNTU_IDLE_POLICY ->
                AiLimbsAvailabilityResult(true, prerequisites = prerequisites)
            AiLimbsCoreAvailabilityPolicy.DEFAULT,
            AiLimbsCoreAvailabilityPolicy.BRIDGE_RECONNECT,
            null ->
                if (phase == "RUNNING") {
                    AiLimbsAvailabilityResult(true, prerequisites = prerequisites)
                } else {
                    AiLimbsAvailabilityResult(
                        false,
                        "UBUNTU_NOT_RUNNING",
                        "Ubuntu is " + phase + ".",
                        nextAction =
                            transportInvocation(
                                "ubuntu.start",
                                JSONObject()
                            ),
                        prerequisites = prerequisites
                    )
                }
        }
    }

    private fun missingReceiptInspection(
        base: AiLimbsPolicyInspection,
        missing: AiLimbsMissingReceipt
    ): AiLimbsPolicyInspection {
        val nextAction = transportInvocation(missing.readTool, JSONObject())
        val label =
            when (missing.receipt) {
                AiLimbsRequiredReceipt.CUSTOM_ACCESS_PROMPT ->
                    "custom access prompt"
                AiLimbsRequiredReceipt.WORK_MANUAL ->
                    "Work Manual"
            }
        return base.copy(
            outcome = AiLimbsPolicyOutcome.FORBID,
            available = false,
            reasonCode =
                when (missing.receipt) {
                    AiLimbsRequiredReceipt.CUSTOM_ACCESS_PROMPT ->
                        "CUSTOM_ACCESS_PROMPT_REQUIRED"
                    AiLimbsRequiredReceipt.WORK_MANUAL ->
                        "WORK_MANUAL_REQUIRED"
                },
            reason =
                "Read the current " +
                    label +
                    " version before executing this capability.",
            nextAction =
                JSONObject()
                    .put("type", "READ_MANAGED_DOCUMENT")
                    .put("document_id", missing.reference.documentId)
                    .put("required_version", missing.reference.version)
                    .put(
                        "capability",
                        JSONObject()
                            .put("name", missing.readTool)
                            .put("parameters", JSONObject())
                    )
                    .put("transport_invocation", nextAction)
        )
    }

    private fun toAiTool(invocation: AiLimbsNormalizedInvocation): AITool {
        val parameters = mutableListOf<ToolParameter>()
        val keys = invocation.parameters.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            parameters += ToolParameter(key, invocation.parameters.opt(key)?.toString() ?: "")
        }
        return AITool(name = invocation.targetName, parameters = parameters)
    }

    private companion object {
        val corePermissionMutex = Mutex()
    }
}
