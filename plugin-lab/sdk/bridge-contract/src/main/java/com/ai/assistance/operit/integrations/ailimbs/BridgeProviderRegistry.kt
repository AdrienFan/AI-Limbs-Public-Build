// Source: AI Limbs V0.6.4.7.8 @ 70438d99bb40c147cadc0a4a085deb90d15b347c; visibility-only ABI adaptation.
package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import kotlinx.coroutines.CoroutineScope

class BridgeProviderRegistry {
    private val factoriesByType = linkedMapOf<String, BridgeProviderFactory>()
    private val profilesById = linkedMapOf<String, BridgeProfile>()

    fun register(factory: BridgeProviderFactory): BridgeProviderRegistry {
        check(factory.type !in factoriesByType) {
            "Bridge factory type is already registered: ${factory.type}"
        }
        factory.profiles.forEach { profile ->
            check(profile.type == factory.type) {
                "Bridge profile ${profile.id} has type ${profile.type}, expected ${factory.type}"
            }
            check(profile.id !in profilesById) {
                "Bridge profile id is already registered: ${profile.id}"
            }
        }

        factoriesByType[factory.type] = factory
        factory.profiles.forEach { profile ->
            profilesById[profile.id] = profile
        }
        return this
    }

    fun registerProfile(profile: BridgeProfile): BridgeProviderRegistry {
        check(profile.type in factoriesByType) {
            "No Bridge factory is registered for profile type: ${profile.type}"
        }
        check(profile.id !in profilesById) {
            "Bridge profile id is already registered: ${profile.id}"
        }
        profilesById[profile.id] = profile
        return this
    }

    val profiles: List<BridgeProfile>
        get() = profilesById.values.toList()

    fun requireProfile(profileId: String): BridgeProfile =
        checkNotNull(profilesById[profileId]) {
            "Unknown Bridge profile: $profileId"
        }

    fun supportedActions(profile: BridgeProfile): Set<BridgeAction> =
        requireFactory(profile).supportedActions

    fun create(
        profile: BridgeProfile,
        context: Context,
        scope: CoroutineScope
    ): AiLimbsBridgeProvider {
        val factory = requireFactory(profile)
        val provider = factory.create(context, scope, profile)
        check(provider.id == profile.id) {
            "Bridge factory returned provider ${provider.id} for profile ${profile.id}"
        }
        check(provider.supportedActions == factory.supportedActions) {
            "Bridge provider action contract differs from its factory: ${profile.id}"
        }
        return provider
    }

    private fun requireFactory(profile: BridgeProfile): BridgeProviderFactory {
        check(profilesById[profile.id] == profile) {
            "Bridge profile is not registered: ${profile.id}"
        }
        return checkNotNull(factoriesByType[profile.type]) {
            "No Bridge factory is registered for profile type: ${profile.type}"
        }
    }
}
