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
 * Stable bridge boundary for AI Limbs.
 *
 * The manager resolves a persisted profile through the Provider Registry. The
 * service layer therefore owns only generic state and actions, while concrete
 * transports remain behind their factories.
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
    private var provider =
        registry.create(activeProfileValue, appContext, scope)
    private val stateFlow = MutableStateFlow(provider.state.value)
    private var providerStateJob: Job? = null

    init {
        bindProviderState()
    }

    val state: StateFlow<AiLimbsBridgeState> = stateFlow.asStateFlow()

    val activeProfile: BridgeProfile
        get() = activeProfileValue

    val shouldKeepAlive: Boolean
        get() = provider.enabled && desiredConnected()

    val requiresScreenOffCpuKeepAlive: Boolean
        get() = provider.enabled && provider.requiresScreenOffCpuKeepAlive

    fun availableActions(
        state: AiLimbsBridgeState = this.state.value
    ): List<BridgeAction> {
        if (!provider.enabled) return emptyList()
        return BridgeAction.availableFor(state, provider.supportedActions)
    }

    fun startIfDesired() {
        if (shouldKeepAlive) {
            provider.start()
        } else {
            provider.markStopped()
        }
    }

    fun perform(action: BridgeAction): Boolean {
        if (action !in availableActions()) return false
        return when (action) {
            BridgeAction.CONNECT -> {
                connect()
                true
            }
            BridgeAction.STOP -> {
                stopByUser()
                true
            }
            BridgeAction.RECONNECT -> {
                reconnect()
                true
            }
            BridgeAction.RECOVER -> {
                recover()
                true
            }
            BridgeAction.REPAIR -> {
                rePair()
                true
            }
            BridgeAction.OPEN_AUTH -> openAuthorizationPage()
            BridgeAction.REFRESH -> {
                verifyLiveness()
                true
            }
        }
    }

    fun selectProvider(profileId: String) {
        check(state.value.phase != AiLimbsBridgePhase.RECOVERING) {
            "Bridge provider cannot change while recovery is running"
        }
        val nextProfile = registry.requireProfile(profileId)
        if (nextProfile.id == activeProfileValue.id) return

        val nextProvider = registry.create(nextProfile, appContext, scope)
        provider.stopRuntime()
        preferences.edit().putString(KEY_ACTIVE_PROVIDER, nextProfile.id).apply()
        activeProfileValue = nextProfile
        provider = nextProvider
        bindProviderState()
        startIfDesired()
    }

    fun connect() {
        check(provider.enabled) {
            "Bridge provider is disabled: ${activeProfileValue.id}"
        }
        setDesiredConnected(true)
        provider.start()
    }

    fun stopByUser() {
        setDesiredConnected(false)
        provider.stopByUser()
    }

    fun stopRuntime() {
        provider.stopRuntime()
    }

    fun reconnect() {
        check(provider.enabled) {
            "Bridge provider is disabled: ${activeProfileValue.id}"
        }
        setDesiredConnected(true)
        provider.reconnect()
    }

    fun recover() {
        check(provider.enabled) {
            "Bridge provider is disabled: ${activeProfileValue.id}"
        }
        setDesiredConnected(true)
        provider.recover()
    }

    fun rePair() {
        check(provider.enabled) {
            "Bridge provider is disabled: ${activeProfileValue.id}"
        }
        setDesiredConnected(true)
        provider.rePair()
    }

    fun openAuthorizationPage(): Boolean = provider.openAuthorizationPage()

    fun verifyLiveness() {
        if (shouldKeepAlive && !provider.isRunning) {
            provider.start()
        } else {
            provider.verifyLiveness()
        }
    }

    fun statusSummary(): String =
        "${activeProfileValue.id} ${provider.statusSummary}"

    private fun bindProviderState() {
        providerStateJob?.cancel()
        val selectedProvider = provider
        publishState(selectedProvider.state.value)
        providerStateJob =
            scope.launch {
                selectedProvider.state.collect { newState ->
                    if (provider === selectedProvider) {
                        publishState(newState)
                    }
                }
            }
    }

    private fun publishState(newState: AiLimbsBridgeState) {
        val profileState =
            newState.copy(
                providerId = activeProfileValue.id,
                providerLabel = activeProfileValue.label
            )
        stateFlow.value = profileState
        runtimeStateFlow.value = profileState
    }

    private fun desiredConnected(): Boolean =
        preferences.getBoolean(KEY_DESIRED_CONNECTED, true)

    private fun setDesiredConnected(value: Boolean) {
        preferences.edit().putBoolean(KEY_DESIRED_CONNECTED, value).apply()
    }

    companion object {
        const val ENABLED = true
        private const val PREF_FILE = "ai_limbs_bridge_manager"
        private const val KEY_DESIRED_CONNECTED = "desired_connected"
        private const val KEY_ACTIVE_PROVIDER = "active_provider"

        private val runtimeStateFlow = MutableStateFlow(AiLimbsBridgeState())
        val runtimeState: StateFlow<AiLimbsBridgeState> =
            runtimeStateFlow.asStateFlow()
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
            val profile = registry.requireProfile(activeProviderId(context))
            if (!profile.enabled) return emptyList()
            return BridgeAction.availableFor(
                state,
                registry.supportedActions(profile)
            )
        }

        fun shouldKeepAlive(context: Context): Boolean {
            if (!ENABLED) return false
            val appContext = context.applicationContext
            val preferences =
                appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            if (!preferences.getBoolean(KEY_DESIRED_CONNECTED, true)) return false

            val registry = AiLimbsBridgeProviderCatalog.createRegistry()
            val profile =
                registry.requireProfile(
                    initializeActiveProviderId(preferences, registry)
                )
            return profile.enabled
        }

        private fun initializeActiveProviderId(
            preferences: SharedPreferences,
            registry: BridgeProviderRegistry
        ): String {
            if (!preferences.contains(KEY_ACTIVE_PROVIDER)) {
                val defaultProfile =
                    registry.requireProfile(
                        AiLimbsBridgeProviderCatalog.DEFAULT_PROFILE_ID
                    )
                preferences.edit()
                    .putString(KEY_ACTIVE_PROVIDER, defaultProfile.id)
                    .apply()
                return defaultProfile.id
            }

            val profileId =
                checkNotNull(
                    preferences.getString(KEY_ACTIVE_PROVIDER, null)
                ) {
                    "Persisted active Bridge profile is null"
                }
            registry.requireProfile(profileId)
            return profileId
        }
    }
}
