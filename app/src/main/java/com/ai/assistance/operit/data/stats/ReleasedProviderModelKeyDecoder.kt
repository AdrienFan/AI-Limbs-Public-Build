package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.model.ApiProviderType

internal data class ReleasedProviderModelKey(
    val storedProviderModel: String,
    val provider: String,
    val model: String,
)

/**
 * Decodes the released DataStore key format `provider:model -> provider_model`.
 *
 * Registered aliases are matched longest-first so ToolPkg IDs containing `_` keep their
 * full identity. A provider that was removed before migration is decoded with the same
 * first-separator rule used by the released implementation; its historical name is the
 * only identity available in the key itself.
 */
internal object ReleasedProviderModelKeyDecoder {
    private val builtInProviderAliases = ApiProviderType.entries.associate { it.name to it.name }

    fun decode(
        encoded: String,
        additionalProviderAliases: Map<String, String> = emptyMap(),
    ): ReleasedProviderModelKey {
        val aliases = buildMap {
            putAll(builtInProviderAliases)
            additionalProviderAliases.forEach { (rawAlias, rawIdentity) ->
                val alias = rawAlias.trim()
                val identity = rawIdentity.trim()
                require(alias.isNotEmpty() && identity.isNotEmpty()) {
                    "released token provider aliases must not be blank"
                }
                val previous = put(alias, identity)
                require(previous == null || previous == identity) {
                    "conflicting released token provider alias: $alias"
                }
            }
        }
        val knownProviderAlias = aliases.keys
            .sortedByDescending(String::length)
            .firstOrNull { encoded == it || encoded.startsWith("${it}_") }
        val separator: Int
        val providerAlias: String
        if (knownProviderAlias != null) {
            providerAlias = knownProviderAlias
            separator = providerAlias.length
        } else {
            // Released keys for providers no longer present in the registry only retain
            // the original provider:model separator encoded as the first underscore.
            separator = encoded.indexOf('_')
            require(separator > 0 && separator < encoded.lastIndex) {
                "released token key does not contain a provider and model: $encoded"
            }
            providerAlias = encoded.substring(0, separator)
        }
        require(separator > 0 && separator < encoded.lastIndex) {
            "released token key does not contain a provider and model: $encoded"
        }
        val model = encoded.substring(separator + 1)
        val provider =
            if (knownProviderAlias != null) aliases.getValue(providerAlias) else providerAlias
        return ReleasedProviderModelKey(
            storedProviderModel = "$providerAlias:$model",
            provider = provider,
            model = model,
        )
    }
}
