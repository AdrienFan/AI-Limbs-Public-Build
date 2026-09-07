package com.ai.limbs.plugins.ubuntu.runtime.terminal.data

const val UBUNTU_STOPPED_ERROR = "Ubuntu is stopped. Call ubuntu.start first."

enum class UbuntuRuntimePhase {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

data class UbuntuRuntimeState(
    val phase: UbuntuRuntimePhase = UbuntuRuntimePhase.STOPPED,
    val detail: String = "Ubuntu is stopped.",
    val error: String? = null
)

enum class UbuntuStopRequester {
    USER_INTERFACE,
    AI_TOOL,
    SYSTEM
}

data class UbuntuUsageState(
    val userInterfaceClients: Int = 0,
    val hiddenAiOperations: Int = 0
) {
    val participantCount: Int
        get() = userInterfaceClients + if (hiddenAiOperations > 0) 1 else 0
}

enum class UbuntuIdleMode {
    KEEP_RUNNING,
    MINUTES_10,
    MINUTES_15,
    MINUTES_30,
    MINUTES_60,
    CUSTOM
}

data class UbuntuIdlePolicy(
    val mode: UbuntuIdleMode = UbuntuIdleMode.KEEP_RUNNING,
    val customMinutes: Int = DEFAULT_CUSTOM_MINUTES
) {
    val timeoutMinutes: Int?
        get() =
            when (mode) {
                UbuntuIdleMode.KEEP_RUNNING -> null
                UbuntuIdleMode.MINUTES_10 -> 10
                UbuntuIdleMode.MINUTES_15 -> 15
                UbuntuIdleMode.MINUTES_30 -> 30
                UbuntuIdleMode.MINUTES_60 -> 60
                UbuntuIdleMode.CUSTOM -> customMinutes
            }

    init {
        require(customMinutes in MIN_CUSTOM_MINUTES..MAX_CUSTOM_MINUTES) {
            "Custom Ubuntu idle timeout must be between $MIN_CUSTOM_MINUTES and " +
                "$MAX_CUSTOM_MINUTES minutes."
        }
    }

    companion object {
        const val DEFAULT_CUSTOM_MINUTES = 15
        const val MIN_CUSTOM_MINUTES = 1
        const val MAX_CUSTOM_MINUTES = 1440
    }
}
