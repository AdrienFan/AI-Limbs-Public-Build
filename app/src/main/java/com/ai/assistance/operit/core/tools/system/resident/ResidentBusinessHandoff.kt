package com.ai.assistance.operit.core.tools.system.resident

internal enum class ResidentBackendRequirement {
    REQUIRED,
    OPTIONAL
}

internal data class ResidentBusinessHandoff(
    val coreSessionId: String,
    val coreProcessId: Int,
    val permissionHandoff: ResidentPermissionHandoff?,
    val backendRequirement: ResidentBackendRequirement,
    val backendState: String
) {
    val permissionPrepared: Boolean get() = permissionHandoff != null
}
