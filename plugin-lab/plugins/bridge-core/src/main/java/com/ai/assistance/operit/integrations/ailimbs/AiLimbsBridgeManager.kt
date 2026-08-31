// Source: AI Limbs V0.6.4.7.8 @ 70438d99bb40c147cadc0a4a085deb90d15b347c; dynamic catalog compatibility patch only.
package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Bridge runtime manager.
 *
 * Multiple providers may remain online at the same time. activeProfile is only
 * the provider currently selected for status/configuration/actions; selecting a
 * different provider never stops the other bridge runtimes.
 */
class AiLimbsBridgeManager(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val registry = AiLimbsBridgeProviderCatalog.createRegistry()
    private val preferences =
        appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    private var activeProfileValue =
        registry.requireProfile(initializeActiveProviderId(preferences, registry))
    private val providersById: MutableMap<String, AiLimbsBridgeProvider> =
        registry.profiles.associate { profile ->
            profile.id to registry.create(profile, appContext, scope)
        }.toMutableMap()
    private val providerStateJobs = mutableMapOf<String, Job>()
    private val stateFlow = MutableStateFlow(selectedProvider().state.value)

    @Volatile
    private var rePairAwaitingAuthorizationProviderId: String? = null

    init {
        bindProviderStates()
    }

    val state: StateFlow<AiLimbsBridgeState> = stateFlow.asStateFlow()

    val activeProfile: BridgeProfile
        get() = activeProfileValue

    val shouldKeepAlive: Boolean
        get() = providersById.values.any { provider ->
            provider.enabled && desiredConnected(provider.id)
        }

    val hasActivePairingTransaction: Boolean
        get() = rePairAwaitingAuthorizationProviderId != null

    val requiresScreenOffCpuKeepAlive: Boolean
        get() = providersById.values.any { provider ->
            provider.enabled &&
                desiredConnected(provider.id) &&
                provider.requiresScreenOffCpuKeepAlive
        }

    fun availableActions(
        state: AiLimbsBridgeState = this.state.value
    ): List<BridgeAction> = availableActionsFor(selectedProvider(), state)

    fun startIfDesired() {
        providersById.values.forEach { provider ->
            if (provider.enabled && desiredConnected(provider.id)) {
                provider.start()
            } else {
                provider.markStopped()
            }
        }
        publishSelectedState()
    }

    fun perform(action: BridgeAction, providerId: String? = null): Boolean {
        val provider = providerFor(providerId)
        if (action !in availableActionsFor(provider, provider.state.value)) return false
        return when (action) {
            BridgeAction.CONNECT -> {
                connectProvider(provider)
                true
            }
            BridgeAction.STOP -> {
                stopProviderByUser(provider)
                true
            }
            BridgeAction.RECONNECT -> {
                reconnectProvider(provider)
                true
            }
            BridgeAction.RECOVER -> {
                recoverProvider(provider)
                true
            }
            BridgeAction.REPAIR -> {
                rePairProvider(provider)
                true
            }
            BridgeAction.OPEN_AUTH -> provider.openAuthorizationPage()
            BridgeAction.REFRESH -> {
                verifyProviderLiveness(provider)
                true
            }
        }
    }

    fun selectProvider(profileId: String) {
        check(state.value.phase != AiLimbsBridgePhase.RECOVERING) {
            "Bridge provider cannot change while selected provider recovery is running"
        }
        val nextProfile = registry.requireProfile(profileId)
        if (nextProfile.id == activeProfileValue.id) return

        preferences.edit().putString(KEY_ACTIVE_PROVIDER, nextProfile.id).apply()
        activeProfileValue = nextProfile
        publishSelectedState()

        val nextProvider = selectedProvider()
        if (nextProvider.enabled && desiredConnected(nextProvider.id) && !nextProvider.isRunning) {
            nextProvider.start()
        }
    }

    fun connect() = connectProvider(selectedProvider())

    fun stopByUser() = stopProviderByUser(selectedProvider())

    fun stopRuntime() {
        rePairAwaitingAuthorizationProviderId = null
        providersById.values.forEach(AiLimbsBridgeProvider::stopRuntime)
    }

    fun reconnect() = reconnectProvider(selectedProvider())

    fun recover() = recoverProvider(selectedProvider())

    fun rePair() = rePairProvider(selectedProvider())

    fun openAuthorizationPage(): Boolean = selectedProvider().openAuthorizationPage()

    fun verifyLiveness() = verifyProviderLiveness(selectedProvider())

    private fun connectProvider(provider: AiLimbsBridgeProvider) {
        check(provider.enabled) { "Bridge provider is disabled: ${provider.id}" }
        setDesiredConnected(provider.id, true)
        provider.start()
    }

    private fun stopProviderByUser(provider: AiLimbsBridgeProvider) {
        if (rePairAwaitingAuthorizationProviderId == provider.id) {
            rePairAwaitingAuthorizationProviderId = null
        }
        setDesiredConnected(provider.id, false)
        provider.stopByUser()
    }

    private fun reconnectProvider(provider: AiLimbsBridgeProvider) {
        check(provider.enabled) { "Bridge provider is disabled: ${provider.id}" }
        if (rePairAwaitingAuthorizationProviderId == provider.id) {
            rePairAwaitingAuthorizationProviderId = null
        }
        setDesiredConnected(provider.id, true)
        provider.reconnect()
    }

    private fun recoverProvider(provider: AiLimbsBridgeProvider) {
        check(provider.enabled) { "Bridge provider is disabled: ${provider.id}" }
        if (rePairAwaitingAuthorizationProviderId == provider.id) {
            rePairAwaitingAuthorizationProviderId = null
        }
        setDesiredConnected(provider.id, true)
        provider.recover()
    }

    private fun rePairProvider(provider: AiLimbsBridgeProvider) {
        check(provider.enabled) { "Bridge provider is disabled: ${provider.id}" }
        rePairAwaitingAuthorizationProviderId = provider.id
        setDesiredConnected(provider.id, false)
        try {
            provider.rePair()
        } catch (e: Exception) {
            rePairAwaitingAuthorizationProviderId = null
            throw e
        }
    }

    private fun verifyProviderLiveness(provider: AiLimbsBridgeProvider) {
        if (provider.enabled && desiredConnected(provider.id) && !provider.isRunning) {
            provider.start()
        } else {
            provider.verifyLiveness()
        }
    }

    internal fun onHostSignal(signal: AiLimbsBridgeHostSignal) {
        providersById.values.forEach { provider ->
            if (provider.enabled && desiredConnected(provider.id) && !provider.isRunning) {
                provider.start()
            }
            provider.onHostSignal(signal)
        }
    }

    fun statusSummary(): String =
        providersById.values.joinToString(separator = " | ") { provider ->
            "${provider.id}=${provider.statusSummary}"
        }

    private fun providerFor(providerId: String?): AiLimbsBridgeProvider {
        if (providerId.isNullOrBlank()) return selectedProvider()
        return checkNotNull(providersById[providerId]) {
            "Bridge provider runtime is missing: $providerId"
        }
    }

    private fun availableActionsFor(
        provider: AiLimbsBridgeProvider,
        state: AiLimbsBridgeState
    ): List<BridgeAction> {
        if (!provider.enabled) return emptyList()
        return BridgeAction.availableFor(state, provider.supportedActions)
    }

    private fun selectedProvider(): AiLimbsBridgeProvider =
        checkNotNull(providersById[activeProfileValue.id]) {
            "Selected Bridge provider runtime is missing: ${activeProfileValue.id}"
        }

    private fun bindProviderStates() {
        providerStateJobs.values.forEach(Job::cancel)
        providerStateJobs.clear()
        providersById.forEach { (providerId, provider) ->
            providerStateJobs[providerId] = scope.launch {
                provider.state.collect { newState ->
                    handleProviderState(providerId, newState)
                }
            }
        }
        publishSelectedState()
    }

    private fun handleProviderState(providerId: String, newState: AiLimbsBridgeState) {
        if (rePairAwaitingAuthorizationProviderId == providerId) {
            when {
                (newState.phase == AiLimbsBridgePhase.CONNECTING &&
                    !newState.deviceId.isNullOrBlank()) ||
                    newState.phase == AiLimbsBridgePhase.ONLINE -> {
                    rePairAwaitingAuthorizationProviderId = null
                    setDesiredConnected(providerId, true)
                }
                newState.phase == AiLimbsBridgePhase.ERROR ||
                    newState.phase == AiLimbsBridgePhase.RECOVERY_FAILED -> {
                    rePairAwaitingAuthorizationProviderId = null
                }
            }
        }
        if (providerId == AiLimbsBridgeProviderCatalog.DEFAULT_PROFILE_ID) {
            publishRuntimeState(newState)
        }
        if (providerId == activeProfileValue.id) {
            publishControlState(newState)
        }
    }

    private fun publishSelectedState() {
        publishControlState(selectedProvider().state.value)
        val primary = checkNotNull(providersById[AiLimbsBridgeProviderCatalog.DEFAULT_PROFILE_ID])
        publishRuntimeState(primary.state.value)
    }

    private fun publishControlState(newState: AiLimbsBridgeState) {
        val profileState = newState.copy(
            providerId = activeProfileValue.id,
            providerLabel = activeProfileValue.label
        )
        stateFlow.value = profileState
        controlStateFlow.value = profileState
    }

    private fun publishRuntimeState(newState: AiLimbsBridgeState) {
        val primaryProfile = registry.requireProfile(AiLimbsBridgeProviderCatalog.DEFAULT_PROFILE_ID)
        runtimeStateFlow.value = newState.copy(
            providerId = primaryProfile.id,
            providerLabel = primaryProfile.label
        )
    }

    private fun desiredConnected(profileId: String): Boolean =
        desiredConnected(preferences, profileId)

    private fun setDesiredConnected(profileId: String, value: Boolean) {
        preferences.edit()
            .putBoolean(desiredConnectedKey(profileId), value)
            .apply()
    }

    companion object {
        const val ENABLED = true
        private const val PREF_FILE = "ai_limbs_bridge_manager"
        private const val KEY_DESIRED_CONNECTED = "desired_connected"
        private const val KEY_DESIRED_CONNECTED_PREFIX = "desired_connected_"
        private const val KEY_ACTIVE_PROVIDER = "active_provider"

        private val runtimeStateFlow = MutableStateFlow(AiLimbsBridgeState())
        val runtimeState: StateFlow<AiLimbsBridgeState> = runtimeStateFlow.asStateFlow()
        private val controlStateFlow = MutableStateFlow(AiLimbsBridgeState())
        val controlState: StateFlow<AiLimbsBridgeState> = controlStateFlow.asStateFlow()

        fun persistActiveProvider(context: Context, profileId: String) {
            val registry = AiLimbsBridgeProviderCatalog.createRegistry()
            val profile = registry.requireProfile(profileId)
            context.applicationContext
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIVE_PROVIDER, profile.id)
                .apply()
        }

        fun availableProfiles(): List<BridgeProfile> =
            AiLimbsBridgeProviderCatalog.createRegistry().profiles

        fun activeProviderId(context: Context): String {
            val registry = AiLimbsBridgeProviderCatalog.createRegistry()
            return initializeActiveProviderId(
                context.applicationContext
                    .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE),
                registry
            )
        }

        fun availableActions(
            context: Context,
            state: AiLimbsBridgeState
        ): List<BridgeAction> {
            val registry = AiLimbsBridgeProviderCatalog.createRegistry()
            val profileId = state.providerId.takeIf { it.isNotBlank() } ?: activeProviderId(context)
            val profile = registry.requireProfile(profileId)
            if (!profile.enabled) return emptyList()
            return BridgeAction.availableFor(state, registry.supportedActions(profile))
        }

        fun shouldKeepAlive(context: Context): Boolean {
            if (!ENABLED) return false
            val appContext = context.applicationContext
            val preferences = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val registry = AiLimbsBridgeProviderCatalog.createRegistry()
            return registry.profiles.any { profile ->
                profile.enabled && desiredConnected(preferences, profile.id)
            }
        }

        private fun desiredConnected(
            preferences: SharedPreferences,
            profileId: String
        ): Boolean {
            val key = desiredConnectedKey(profileId)
            if (preferences.contains(key)) {
                return preferences.getBoolean(key, false)
            }
            if (profileId == AiLimbsBridgeProviderCatalog.DEFAULT_PROFILE_ID) {
                return if (preferences.contains(KEY_DESIRED_CONNECTED)) {
                    preferences.getBoolean(KEY_DESIRED_CONNECTED, true)
                } else {
                    true
                }
            }
            return false
        }

        private fun desiredConnectedKey(profileId: String): String =
            "$KEY_DESIRED_CONNECTED_PREFIX$profileId"

        private fun initializeActiveProviderId(
            preferences: SharedPreferences,
            registry: BridgeProviderRegistry
        ): String {
            if (!preferences.contains(KEY_ACTIVE_PROVIDER)) {
                val defaultProfile = registry.requireProfile(
                    AiLimbsBridgeProviderCatalog.DEFAULT_PROFILE_ID
                )
                preferences.edit()
                    .putString(KEY_ACTIVE_PROVIDER, defaultProfile.id)
                    .apply()
                return defaultProfile.id
            }

            val profileId = preferences.getString(KEY_ACTIVE_PROVIDER, null)
            if (!profileId.isNullOrBlank() && registry.profiles.any { it.id == profileId }) {
                return profileId
            }
            val fallback = registry.requireProfile(AiLimbsBridgeProviderCatalog.DEFAULT_PROFILE_ID)
            preferences.edit().putString(KEY_ACTIVE_PROVIDER, fallback.id).apply()
            return fallback.id
        }
    }
}
