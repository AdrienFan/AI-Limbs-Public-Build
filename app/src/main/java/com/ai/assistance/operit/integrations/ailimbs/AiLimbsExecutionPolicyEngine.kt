package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry
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
import kotlin.coroutines.coroutineContext

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
    val session: AiLimbsExecutionSession,
    sharedAccessGate: AiLimbsAccessGate? = null
) {
    private val appContext = context.applicationContext
    private val handler = AIToolHandler.getInstance(appContext)
    private val permissionSystem = ToolPermissionSystem.getInstance(appContext)
    private val uiCapabilities = AiLimbsUiCapabilityService(appContext)
    private val receipts = sharedAccessGate ?: AiLimbsAccessGate(appContext)

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
                        spec = AiLimbsExecutionPolicyDescriptor.specForHostTool(targetName, parameters, session.transport)
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
                            spec = AiLimbsExecutionPolicyDescriptor.specForHostTool(targetName, parameters, session.transport)
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
                spec = AiLimbsExecutionPolicyDescriptor.specForPluginCapability(
                    effect = plugin.effect,
                    domain = plugin.domain,
                    workContextRequiredReceipts = plugin.workContextRequiredReceipts,
                    parameters = args,
                    transport = session.transport
                )
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
                    spec = AiLimbsExecutionPolicyDescriptor.specForHostTool(targetName, JSONObject(), session.transport)
                } else {
                    route = AiLimbsCapabilityRoute.Core(core)
                    spec = AiLimbsExecutionPolicyDescriptor.specForCoreRoute(core.route)
                }
            }
            is AiLimbsCapabilityRegistration.Plugin -> {
                route = AiLimbsCapabilityRoute.Plugin(registration.registration)
                val plugin = registration.registration
                spec = AiLimbsExecutionPolicyDescriptor.specForPluginCapability(
                    effect = plugin.effect,
                    domain = plugin.domain,
                    workContextRequiredReceipts = plugin.workContextRequiredReceipts,
                    parameters = JSONObject(),
                    transport = session.transport
                )
            }
            null -> {
                route = AiLimbsCapabilityRoute.HostTool(targetName)
                spec = AiLimbsExecutionPolicyDescriptor.specForHostTool(targetName, JSONObject(), session.transport)
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

        val workGateApplies =
            session.transport != AiLimbsExecutionTransport.PLUGIN_RUNTIME &&
                !bypassesWorkModeGate(invocation)
        if (workGateApplies) {
            when (receipts.workGateState()) {
                AiLimbsWorkGateState.SELECTION_REQUIRED ->
                    return AiLimbsPolicyDecision(
                        proceed = false,
                        inspection = workModeSelectionInspection(inspection)
                    )
                AiLimbsWorkGateState.WORK_MANUAL_REQUIRED -> {
                    val missingManual = receipts.missingWorkManual()
                    if (missingManual != null) {
                        return AiLimbsPolicyDecision(
                            proceed = false,
                            inspection = missingReceiptInspection(inspection, missingManual)
                        )
                    }
                    return AiLimbsPolicyDecision(
                        proceed = false,
                        inspection = workModeSelectionInspection(inspection)
                    )
                }
                AiLimbsWorkGateState.NON_WORK_ONCE,
                AiLimbsWorkGateState.WORK_UNLOCKED -> Unit
            }
        }

        var finalInspection = inspection
        var confirmedDuringEvaluation = false
        if (
            inspection.permission == PermissionLevel.ASK.name &&
                !invocation.spec.hostPermissionEnforced
        ) {
            if (AiLimbsExecutionAuthorization.allows(coroutineContext, session, invocation.targetName)) {
                finalInspection = inspection.copy(outcome = AiLimbsPolicyOutcome.ALLOW)
                confirmedDuringEvaluation = true
            } else {
                val granted = corePermissionMutex.withLock {
                    permissionSystem.checkToolPermission(toAiTool(invocation))
                }
                if (!granted) {
                    return AiLimbsPolicyDecision(
                        proceed = false,
                        inspection = inspection.copy(
                            outcome = AiLimbsPolicyOutcome.FORBID,
                            reasonCode = "PERMISSION_DENIED",
                            reason = "The user denied the AI Limbs capability.",
                            nextAction = null
                        )
                    )
                }
                finalInspection = inspection.copy(outcome = AiLimbsPolicyOutcome.ALLOW)
                confirmedDuringEvaluation = true
            }
        }
        if (workGateApplies && !receipts.claimNormalExecution()) {
            val blockedInspection = when (receipts.workGateState()) {
                AiLimbsWorkGateState.WORK_MANUAL_REQUIRED ->
                    receipts.missingWorkManual()?.let {
                        missingReceiptInspection(finalInspection, it)
                    } ?: workModeSelectionInspection(finalInspection)
                else -> workModeSelectionInspection(finalInspection)
            }
            return AiLimbsPolicyDecision(proceed = false, inspection = blockedInspection)
        }
        return AiLimbsPolicyDecision(
            proceed = true,
            inspection = finalInspection,
            confirmedDuringEvaluation = confirmedDuringEvaluation
        )
    }

    internal suspend fun selectWorkMode(args: JSONObject): JSONObject {
        val rawMode = args.optString("mode").trim().uppercase()
        val mode = AiLimbsWorkMode.entries.firstOrNull { it.name == rawMode }
            ?: return JSONObject()
                .put("success", false)
                .put("error_code", "INVALID_WORK_MODE")
                .put("error", "mode must be WORK or NON_WORK")

        val state = receipts.selectWorkMode(mode)
        val result = JSONObject()
            .put("success", true)
            .put("selected_mode", mode.name)
            .put("work_gate_state", state.name)

        return when (state) {
            AiLimbsWorkGateState.NON_WORK_ONCE -> {
                result
                    .put("one_shot", true)
                    .put("work_gate_unlocked", false)
                    .put("instruction", "Exactly one normal capability may execute; the work-mode gate returns afterward.")
                if (
                    mode == AiLimbsWorkMode.NON_WORK &&
                        receipts.claimNonWorkUbuntuToolDiscovery()
                ) {
                    result.put("ubuntu_tool_discovery", ubuntuToolDiscoveryContract())
                }
                result
            }
            AiLimbsWorkGateState.WORK_MANUAL_REQUIRED -> {
                val missing = receipts.missingWorkManual()
                if (mode == AiLimbsWorkMode.NON_WORK) {
                    result
                        .put("success", false)
                        .put("error_code", "WORK_MODE_ALREADY_SELECTED")
                        .put("error", "WORK is already selected for this Interaction Cycle and cannot be downgraded to NON_WORK.")
                        .put("next_action", missing?.let(::managedDocumentNextAction) ?: JSONObject.NULL)
                } else {
                    result
                        .put("work_gate_unlocked", false)
                        .put("work_manual_required", true)
                        .put("next_action", missing?.let(::managedDocumentNextAction) ?: JSONObject.NULL)
                }
            }
            AiLimbsWorkGateState.WORK_UNLOCKED ->
                result
                    .put("work_gate_unlocked", true)
                    .put("work_manual_required", false)
                    .put("cycle_scope", "interaction_cycle")
            AiLimbsWorkGateState.SELECTION_REQUIRED ->
                result
                    .put("success", false)
                    .put("error_code", "WORK_MODE_SELECTION_REQUIRED")
        }
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

        return AiLimbsAvailabilityResult(available = true)
    }

    private fun bypassesWorkModeGate(invocation: AiLimbsNormalizedInvocation): Boolean =
        when (val route = invocation.route) {
            is AiLimbsCapabilityRoute.Core ->
                when (val coreRoute = route.registration.route) {
                    is AiLimbsCoreRoute.ManagedDocumentRead ->
                        when (coreRoute.documentId) {
                            AiLimbsDocumentId.SYSTEM_ACCESS_PROMPT,
                            AiLimbsDocumentId.CUSTOM_ACCESS_PROMPT -> true
                            AiLimbsDocumentId.WORK_MANUAL ->
                                receipts.workGateState() == AiLimbsWorkGateState.WORK_MANUAL_REQUIRED ||
                                    receipts.workGateState() == AiLimbsWorkGateState.WORK_UNLOCKED
                        }
                    is AiLimbsCoreRoute.Local ->
                        when (coreRoute.operation) {
                            AiLimbsCoreLocalOperation.ACCESS_CONTEXT_READ,
                            AiLimbsCoreLocalOperation.CAPABILITY_SEARCH,
                            AiLimbsCoreLocalOperation.CAPABILITY_DESCRIBE,
                            AiLimbsCoreLocalOperation.POLICY_DESCRIBE,
                            AiLimbsCoreLocalOperation.POLICY_SESSION_RESET,
                            AiLimbsCoreLocalOperation.WORK_MODE_SELECT -> true
                            else -> false
                        }
                    else -> false
                }
            else -> false
        }

    private fun workModeSelectionInspection(
        base: AiLimbsPolicyInspection
    ): AiLimbsPolicyInspection {
        val selectTool = AiLimbsCoreCapabilityRegistry.invokeNameForLocalOperation(
            AiLimbsCoreLocalOperation.WORK_MODE_SELECT
        )
        val workArgs = JSONObject().put("mode", AiLimbsWorkMode.WORK.name)
        val nonWorkArgs = JSONObject().put("mode", AiLimbsWorkMode.NON_WORK.name)
        return base.copy(
            outcome = AiLimbsPolicyOutcome.FORBID,
            available = false,
            reasonCode = "WORK_MODE_SELECTION_REQUIRED",
            reason = "Select WORK or NON_WORK before executing a normal capability.",
            nextAction = JSONObject()
                .put("type", "SELECT_WORK_MODE")
                .put("scope", "interaction_cycle")
                .put("capability", selectTool)
                .put("options", JSONArray()
                    .put(JSONObject()
                        .put("mode", AiLimbsWorkMode.NON_WORK.name)
                        .put("semantics", "Allow exactly one normal capability, then require mode selection again.")
                        .put("transport_invocation", transportInvocation(selectTool, nonWorkArgs)))
                    .put(JSONObject()
                        .put("mode", AiLimbsWorkMode.WORK.name)
                        .put("semantics", "Require the current Work Manual, then unlock normal capabilities for this Interaction Cycle.")
                        .put("transport_invocation", transportInvocation(selectTool, workArgs))))
        )
    }

    private fun ubuntuToolDiscoveryContract(): JSONObject =
        JSONObject()
            .put("type", "UBUNTU_TOOL_DISCOVERY")
            .put("scope", "interaction_cycle")
            .put("query_tool", "ail-tool")
            .put("query_existing_first", true)
            .put("reuse_existing_first", true)
            .put("install_only_if_no_match", true)
            .put("cleanup_install_artifacts_after_verified", true)

    private fun managedDocumentNextAction(missing: AiLimbsMissingReceipt): JSONObject =
        JSONObject()
            .put("type", "READ_MANAGED_DOCUMENT")
            .put("document_id", missing.reference.documentId)
            .put("required_version", missing.reference.version)
            .put("capability", JSONObject()
                .put("name", missing.readTool)
                .put("parameters", JSONObject()))
            .put("transport_invocation", transportInvocation(missing.readTool, JSONObject()))

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
