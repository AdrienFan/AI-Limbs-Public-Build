package com.ai.assistance.operit.plugins.center

import org.json.JSONObject

/**
 * Runtime-facing contract for one capability owned by a mounted plugin.
 * Executable capability IDs and invoke aliases are reserved under the `plugin.*` namespace.
 */
fun interface PluginCapabilityExecutor {
    suspend fun execute(parameters: JSONObject): JSONObject
}

data class PluginCapabilityParameterSpec(
    val name: String,
    val type: String = "string",
    val description: String,
    val required: Boolean = true,
    val default: String? = null
)

data class PluginCapabilitySpec(
    val displayName: String,
    val description: String,
    val invokeAliases: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val parameters: List<PluginCapabilityParameterSpec> = emptyList(),
    val suggestedParamsJson: String? = null,
    val inputSchema: String? = null,
    val executor: PluginCapabilityExecutor
)

/**
 * Kernel-side binding boundary. Plugin Runtime can request registration but never receives a
 * Dispatcher or policy entry point.
 */
interface PluginCapabilityBinder {
    fun register(
        ownerPluginId: String,
        capabilityId: String,
        capability: PluginCapabilitySpec
    ): AutoCloseable
}
