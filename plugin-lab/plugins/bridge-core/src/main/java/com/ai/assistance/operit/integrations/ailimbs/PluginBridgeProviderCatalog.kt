package com.ai.assistance.operit.integrations.ailimbs

/** Dynamic replacement for the V0.6.4.7.8 compile-time provider catalog. */
object PluginBridgeProviderCatalog {
    @Volatile private var factories: List<BridgeProviderFactory> = emptyList()
    fun replaceFactories(value: Collection<BridgeProviderFactory>) { factories = value.toList() }
    val DEFAULT_PROFILE_ID: String
        get() {
            val profiles = factories.flatMap { it.profiles }
            return profiles.singleOrNull { it.isDefault }?.id
                ?: profiles.firstOrNull()?.id
                ?: error("No Bridge provider extension is active")
        }
    fun createRegistry(): BridgeProviderRegistry = BridgeProviderRegistry().also { registry ->
        factories.forEach(registry::register)
    }
}
