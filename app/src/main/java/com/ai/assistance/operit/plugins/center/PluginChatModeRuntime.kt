package com.ai.assistance.operit.plugins.center

import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.limbs.plugin.runtime.InProcessChatModeExtensionProvider
import org.json.JSONObject

internal data class PluginChatModePresentationBinding(
    val ownerPluginId: String,
    val id: String,
    val provider: InProcessChatModeExtensionProvider,
    val metadata: Map<String, String>
)

internal object PluginChatModeRuntime {
    const val PRESENTATION_KIND = "chat_mode_extension"
    const val BUSINESS_KIND = "chat_mode_runtime"

    fun presentationBindings(): List<PluginChatModePresentationBinding> =
        runCatching {
            PluginPlatformKernel.presentationProvidersSnapshot()
                .mapNotNull { binding ->
                    val payload = binding.payload as? InProcessChatModeExtensionProvider
                        ?: return@mapNotNull null
                    if (binding.metadata["kind"] != PRESENTATION_KIND) {
                        return@mapNotNull null
                    }
                    PluginChatModePresentationBinding(
                        ownerPluginId = binding.ownerPluginId,
                        id = binding.id,
                        provider = payload,
                        metadata = binding.metadata.toMap()
                    )
                }
                .sortedBy { it.id }
        }.getOrDefault(emptyList())

    fun resolvePresentation(config: ModelConfigData?): PluginChatModePresentationBinding? {
        val contextJson = contextJson(config)
        return presentationBindings().firstOrNull { binding ->
            runCatching { binding.provider.matches(contextJson) }.getOrDefault(false)
        }
    }

    fun isChatModeConfig(config: ModelConfigData?): Boolean {
        if (config == null) return false
        if (resolvePresentation(config) != null) return true
        return PluginPlatformKernel.matchesBusinessChatModeConfig(config)
    }

    internal data class BusinessBinding(
        val ownerPluginId: String,
        val providerId: String,
        val metadata: Map<String, String>
    )

    private fun businessBindings(): List<BusinessBinding> =
        PluginPlatformKernel.contributions.listAll()
            .asSequence()
            .filter { it.kind == PluginContributionKind.PROVIDER }
            .filter { it.metadata["kind"] == BUSINESS_KIND }
            .map { record ->
                BusinessBinding(
                    ownerPluginId = record.ownerPluginId,
                    providerId = record.id,
                    metadata = record.metadata.toMap()
                )
            }
            .sortedBy { it.providerId }
            .toList()

    internal fun businessBinding(config: ModelConfigData? = null): BusinessBinding {
        val bindings = businessBindings()
        require(bindings.isNotEmpty()) { "No plugin chat mode runtime is active" }
        if (config != null) {
            bindings.firstOrNull { binding ->
                val configId = binding.metadata["config_id"].orEmpty().trim()
                val providerTypeId = binding.metadata["provider_type_id"].orEmpty().trim()
                (configId.isNotEmpty() && configId == config.id.trim()) ||
                    (providerTypeId.isNotEmpty() &&
                        providerTypeId.equals(config.apiProviderTypeId.trim(), ignoreCase = true))
            }?.let { return it }
        }
        return bindings.singleOrNull()
            ?: throw IllegalStateException(
                "Multiple plugin chat modes are active; an explicit config match is required"
            )
    }

    internal fun businessConfigurationTemplate(config: ModelConfigData? = null): String {
        val binding = businessBinding(config)
        val configId = binding.metadata["config_id"].orEmpty().trim()
        val providerTypeId = binding.metadata["provider_type_id"].orEmpty().trim()
        require(configId.isNotEmpty()) { "Plugin chat mode config_id is missing" }
        require(providerTypeId.isNotEmpty()) { "Plugin chat mode provider_type_id is missing" }
        return JSONObject()
            .put("config_id", configId)
            .put("name", binding.metadata["display_name"]?.takeIf { it.isNotBlank() } ?: configId)
            .put("model_name", binding.metadata["model_name"]?.takeIf { it.isNotBlank() } ?: providerTypeId)
            .put("api_provider_type_id", providerTypeId)
            .put("enable_tool_call", false)
            .put("enable_summary", false)
            .put("enable_summary_by_message_count", false)
            .put("enable_direct_image_processing", false)
            .put("enable_direct_audio_processing", false)
            .put("enable_direct_video_processing", false)
            .toString()
    }

    internal suspend fun invokeBusinessCompatibility(
        operation: String,
        parameters: JSONObject,
        config: ModelConfigData? = null
    ): JSONObject {
        val binding = businessBinding(config)
        val normalizedOperation = operation.trim().trimStart('.')
        require(normalizedOperation.isNotEmpty()) {
            "Plugin chat mode compatibility operation is required"
        }
        return PluginPlatformKernel.capabilities.invokeOwnedUiDirect(
            ownerPluginId = binding.ownerPluginId,
            capabilityId = binding.ownerPluginId + "." + normalizedOperation,
            parameters = JSONObject(parameters.toString())
        )
    }

    fun contextJson(config: ModelConfigData?, chatId: String? = null): String =
        JSONObject()
            .put("config_id", config?.id.orEmpty())
            .put("config_name", config?.name.orEmpty())
            .put("model_name", config?.modelName.orEmpty())
            .put("api_provider_type_id", config?.apiProviderTypeId.orEmpty())
            .put("chat_id", chatId ?: JSONObject.NULL)
            .toString()
}

