package com.ai.assistance.operit.integrations.ailimbs

import com.ai.assistance.operit.core.tools.catalog.ToolCatalogEntry

internal enum class AiLimbsCoreLocalOperation {
    ACCESS_CONTEXT_READ,
    CAPABILITY_SEARCH,
    CAPABILITY_DESCRIBE,
    DEVELOPER_CATALOG_READ,
    CORE_STATUS,
    DISPATCHER_STATUS,
    SHARED_UBUNTU_STATUS,
    UI_STATUS,
    HOST_TOOLS_LIST,
    HOST_TOOL_EXECUTE,
    POLICY_DESCRIBE,
    POLICY_SESSION_RESET,
    STORAGE_SEARCH,
    STORAGE_DESCRIBE,
    STORAGE_PROJECT_FILES
}

internal enum class AiLimbsLanerChatOperation {
    STATUS,
    SESSION_OPEN,
    SESSION_CLOSE,
    NOTIFICATION_CHECK,
    NOTIFICATION_WAIT,
    INBOX_FETCH,
    ATTACHMENT_FETCH,
    TURN_STATUS,
    TURN_CLAIM,
    TURN_REPLY,
    TURN_RESOLVE,
    TURN_CANCEL,
    TURN_RESUME,
    LEGACY_REPLY,
    SEND
}

internal sealed interface AiLimbsCoreRoute {
    data class Local(val operation: AiLimbsCoreLocalOperation) : AiLimbsCoreRoute
    data class ManagedDocumentRead(val documentId: AiLimbsDocumentId) : AiLimbsCoreRoute
    data class ManagedDocumentWrite(val documentId: AiLimbsDocumentId) : AiLimbsCoreRoute
    data class LanerChat(val operation: AiLimbsLanerChatOperation) : AiLimbsCoreRoute
    object ForwardHostTool : AiLimbsCoreRoute
}

internal enum class AiLimbsCoreProvider {
    CORE,
    BRIDGE,
    UBUNTU
}

internal enum class AiLimbsCoreAvailabilityPolicy {
    DEFAULT,
    BRIDGE_RECONNECT,
    UBUNTU_STATUS,
    UBUNTU_START,
    UBUNTU_STOP,
    UBUNTU_IDLE_POLICY
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
