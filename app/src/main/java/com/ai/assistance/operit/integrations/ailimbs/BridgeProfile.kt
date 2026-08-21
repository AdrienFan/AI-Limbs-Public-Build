package com.ai.assistance.operit.integrations.ailimbs

interface BridgeProfile {
    val id: String
    val type: String
    val label: String
    val enabled: Boolean
}

data class NativeBridgeProfile(
    override val id: String,
    override val type: String,
    override val label: String,
    override val enabled: Boolean = true
) : BridgeProfile

/**
 * Structured configuration for a future managed process provider.
 *
 * V0.5.2 defines the model only. It deliberately does not start a process or
 * translate this structure into a shell command.
 */
data class ExternalProcessProfile(
    override val id: String,
    override val label: String,
    override val enabled: Boolean = false,
    val executable: String,
    val args: List<String> = emptyList(),
    val cwd: String = "",
    val env: Map<String, String> = emptyMap()
) : BridgeProfile {
    override val type: String = TYPE

    companion object {
        const val TYPE = "external_process"
    }
}
