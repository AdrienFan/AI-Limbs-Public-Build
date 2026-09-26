package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry

internal enum class AiLimbsCoreLocalOperation {
    ACCESS_CONTEXT_READ,
    CAPABILITY_SEARCH,
    CAPABILITY_DESCRIBE,
    DEVELOPER_CATALOG_READ,
    CORE_STATUS,
    DISPATCHER_STATUS,
    UI_STATUS,
    HOST_TOOLS_LIST,
    HOST_TOOL_EXECUTE,
    POLICY_DESCRIBE,
    WORK_MODE_SELECT,
    POLICY_SESSION_RESET,
    STORAGE_SEARCH,
    STORAGE_DESCRIBE,
    STORAGE_PROJECT_FILES
}

internal sealed interface AiLimbsCoreRoute {
    data class Local(val operation: AiLimbsCoreLocalOperation) : AiLimbsCoreRoute
    data class ManagedDocumentRead(val documentId: AiLimbsDocumentId) : AiLimbsCoreRoute
    data class ManagedDocumentWrite(val documentId: AiLimbsDocumentId) : AiLimbsCoreRoute
    data class PluginChatModeCompatibility(val operation: String) : AiLimbsCoreRoute
    object ForwardHostTool : AiLimbsCoreRoute
}

internal enum class AiLimbsCoreProvider {
    CORE,
    BRIDGE
}

internal enum class AiLimbsCoreAvailabilityPolicy {
    DEFAULT,
    BRIDGE_RECONNECT
}

internal data class AiLimbsCoreCapabilityRegistration(
    val catalogEntry: ToolCatalogEntry,
    val route: AiLimbsCoreRoute,
    val invokeAliases: List<String> = emptyList(),
    val capabilityId: String? = null,
    val capabilityAliases: List<String> = emptyList(),
    val provider: AiLimbsCoreProvider = AiLimbsCoreProvider.CORE,
    val availabilityPolicy: AiLimbsCoreAvailabilityPolicy = AiLimbsCoreAvailabilityPolicy.DEFAULT
)
