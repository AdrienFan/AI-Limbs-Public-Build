/*
 * Shared ABI for System Environment Center .ailp and its .ailx subsystems.
 * Keep this host copy source-compatible with Plugin Lab system-environment-contract@1.
 * Implementations remain in plugins; AI Limbs owns only the shared type identity.
 */
package com.ai.limbs.systemenvironment.contract

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.StateFlow

object SystemEnvironmentContract {
    const val PARENT_PLUGIN_ID = "plugin.system.environment_center"
    const val EXTENSION_POINT = "ai_limbs.system_environment.subsystem"
    const val API_VERSION = 1
    const val SCREEN_ID = "plugin.system_environment_center.screen"
}

object SystemEnvironmentCapabilityIds {
    const val STATUS = "plugin.system_environment.status"
    const val START = "plugin.system_environment.start"
    const val STOP = "plugin.system_environment.stop"
    const val IDLE_GET = "plugin.system_environment.idle.get"
    const val IDLE_SET = "plugin.system_environment.idle.set"
    const val SESSION_CREATE = "plugin.system_environment.session.create"
    const val SESSION_EXECUTE = "plugin.system_environment.session.execute"
    const val COMMAND = "plugin.system_environment.command"
    const val FILESYSTEM = "plugin.system_environment.filesystem"
    const val PROCESS = "plugin.system_environment.process"
    const val SESSION_INPUT = "plugin.system_environment.session.input"
    const val SESSION_INTERRUPT = "plugin.system_environment.session.interrupt"
    const val SESSION_SCREEN = "plugin.system_environment.session.screen"
    const val SESSION_CLOSE = "plugin.system_environment.session.close"

    val ALL: Set<String> = linkedSetOf(
        STATUS,
        START,
        STOP,
        IDLE_GET,
        IDLE_SET,
        SESSION_CREATE,
        SESSION_EXECUTE,
        COMMAND,
        FILESYSTEM,
        PROCESS,
        SESSION_INPUT,
        SESSION_INTERRUPT,
        SESSION_SCREEN,
        SESSION_CLOSE
    )
}

enum class SystemEnvironmentRuntimePhase {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED
}

data class SystemEnvironmentRuntimeState(
    val phase: SystemEnvironmentRuntimePhase,
    val detail: String? = null
)

enum class SystemEnvironmentIdleMode {
    KEEP_RUNNING,
    MINUTES_10,
    MINUTES_15,
    MINUTES_30,
    MINUTES_60,
    CUSTOM
}

data class SystemEnvironmentIdlePolicy(
    val mode: SystemEnvironmentIdleMode = SystemEnvironmentIdleMode.KEEP_RUNNING,
    val customMinutes: Int = 30
) {
    val timeoutMinutes: Int?
        get() = when (mode) {
            SystemEnvironmentIdleMode.KEEP_RUNNING -> null
            SystemEnvironmentIdleMode.MINUTES_10 -> 10
            SystemEnvironmentIdleMode.MINUTES_15 -> 15
            SystemEnvironmentIdleMode.MINUTES_30 -> 30
            SystemEnvironmentIdleMode.MINUTES_60 -> 60
            SystemEnvironmentIdleMode.CUSTOM -> customMinutes
        }
}

interface SystemEnvironmentRuntimeController {
    val state: StateFlow<SystemEnvironmentRuntimeState>
    val idlePolicy: StateFlow<SystemEnvironmentIdlePolicy>

    suspend fun start()
    suspend fun stop()
    suspend fun setIdlePolicy(policy: SystemEnvironmentIdlePolicy)
}

fun interface SystemEnvironmentDisplayAdapter {
    fun createView(context: Context): View
}

interface SystemEnvironmentConfigurableDisplayAdapter : SystemEnvironmentDisplayAdapter {
    fun createConfigurationView(
        context: Context,
        onRequestDisplay: () -> Unit
    ): View
}

interface SystemEnvironmentCapabilityEndpoint {
    val supportedCapabilityIds: Set<String>
    suspend fun invoke(capabilityId: String, parametersJson: String): String
}

data class SystemEnvironmentSubsystemContribution(
    val subsystemId: String,
    val displayName: String,
    val description: String,
    val runtime: SystemEnvironmentRuntimeController,
    val display: SystemEnvironmentDisplayAdapter,
    val capabilities: SystemEnvironmentCapabilityEndpoint
)
