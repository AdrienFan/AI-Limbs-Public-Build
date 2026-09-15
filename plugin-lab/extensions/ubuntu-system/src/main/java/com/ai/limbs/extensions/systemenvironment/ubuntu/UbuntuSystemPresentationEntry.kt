package com.ai.limbs.extensions.systemenvironment.ubuntu

import android.content.Context
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.TerminalUiController
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuRuntimePhase as TerminalRuntimePhase
import com.ai.limbs.plugin.runtime.ChildExtensionPresentationEntry
import com.ai.limbs.plugin.runtime.ChildExtensionPresentationHandle
import com.ai.limbs.plugin.runtime.ChildExtensionPresentationHost
import com.ai.limbs.plugin.runtime.InProcessPluginUiHost
import com.ai.limbs.plugin.runtime.InProcessProviderDirectory
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentCapabilityEndpoint
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentCapabilityIds
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentConfigurableDisplayAdapter
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentContract
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentIdleMode
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentIdlePolicy
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentRuntimeController
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentRuntimePhase
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentRuntimeState
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentSubsystemContribution
import java.io.File
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Host-only Ubuntu presentation. No TerminalManager, PTY, process, or native runtime is created here. */
class UbuntuSystemPresentationEntry : ChildExtensionPresentationEntry {
    override suspend fun mount(
        host: ChildExtensionPresentationHost
    ): ChildExtensionPresentationHandle {
        require(host.extensionId == EXTENSION_ID) { "Unexpected Ubuntu presentation identity" }
        require(host.target.parentPluginId == SystemEnvironmentContract.PARENT_PLUGIN_ID)
        require(host.target.point == SystemEnvironmentContract.EXTENSION_POINT)
        require(host.target.apiVersion == SystemEnvironmentContract.API_VERSION)

        val terminal = UbuntuResidentPresentationController(host)
        val uiHost = UbuntuPresentationUiHostAdapter(host)
        val nativeLibraryDir = host.applicationContext.applicationInfo.nativeLibraryDir
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: host.cacheDir
        val page = UbuntuSubsystemPageProvider(
            host = uiHost,
            terminal = terminal,
            nativeLibraryDir = nativeLibraryDir,
            settingsControllerFactory = { context -> UbuntuResidentSettingsController(context, host) }
        )
        val contribution = SystemEnvironmentSubsystemContribution(
            subsystemId = host.extensionId,
            displayName = "Ubuntu",
            description = "AI Limbs 官方 Ubuntu 系统环境",
            runtime = PresentationRuntimeController(host, terminal),
            display = object : SystemEnvironmentConfigurableDisplayAdapter {
                override fun createView(context: Context) =
                    page.createView(context, EMPTY_SHARED_UI)

                override fun createConfigurationView(
                    context: Context,
                    onRequestDisplay: () -> Unit
                ) = page.createConfigurationView(context, onRequestDisplay)
            },
            capabilities = object : SystemEnvironmentCapabilityEndpoint {
                override val supportedCapabilityIds: Set<String> = SystemEnvironmentCapabilityIds.ALL
                override suspend fun invoke(capabilityId: String, parametersJson: String): String =
                    terminal.invokeCapability(capabilityId, parametersJson)
            }
        )
        val registration = host.registerPresentationProvider(
            id = PRESENTATION_PROVIDER_ID,
            payload = contribution,
            metadata = mapOf(
                "kind" to PRESENTATION_KIND,
                "extension_id" to host.extensionId,
                "version" to host.version,
                "parent_plugin_id" to host.target.parentPluginId,
                "point" to host.target.point,
                "api_version" to host.target.apiVersion.toString(),
                "display_name" to "Ubuntu"
            )
        )
        return ChildExtensionPresentationHandle { registration.close() }
    }

    private class PresentationRuntimeController(
        host: ChildExtensionPresentationHost,
        private val terminal: TerminalUiController
    ) : SystemEnvironmentRuntimeController {
        override val state: StateFlow<SystemEnvironmentRuntimeState> =
            terminal.ubuntuRuntimeState
                .map { value ->
                    SystemEnvironmentRuntimeState(
                        phase = when (value.phase) {
                            TerminalRuntimePhase.STOPPED -> SystemEnvironmentRuntimePhase.STOPPED
                            TerminalRuntimePhase.STARTING -> SystemEnvironmentRuntimePhase.STARTING
                            TerminalRuntimePhase.RUNNING -> SystemEnvironmentRuntimePhase.RUNNING
                            TerminalRuntimePhase.STOPPING -> SystemEnvironmentRuntimePhase.STOPPING
                            TerminalRuntimePhase.ERROR -> SystemEnvironmentRuntimePhase.FAILED
                        },
                        detail = value.error ?: value.detail
                    )
                }
                .stateIn(
                    host.scope,
                    SharingStarted.Eagerly,
                    SystemEnvironmentRuntimeState(SystemEnvironmentRuntimePhase.STOPPED)
                )

        override val idlePolicy: StateFlow<SystemEnvironmentIdlePolicy> =
            terminal.ubuntuIdlePolicy
                .map { value ->
                    SystemEnvironmentIdlePolicy(
                        mode = SystemEnvironmentIdleMode.valueOf(value.mode.name),
                        customMinutes = value.customMinutes
                    )
                }
                .stateIn(
                    host.scope,
                    SharingStarted.Eagerly,
                    SystemEnvironmentIdlePolicy()
                )

        override suspend fun start() { terminal.startUbuntu(false) }
        override suspend fun stop() { terminal.stopUbuntu() }
        override suspend fun setIdlePolicy(policy: SystemEnvironmentIdlePolicy) {
            terminal.updateUbuntuIdlePolicy(
                com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuIdlePolicy(
                    mode = com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuIdleMode.valueOf(policy.mode.name),
                    customMinutes = policy.customMinutes
                )
            )
        }
    }

    private class UbuntuPresentationUiHostAdapter(
        private val child: ChildExtensionPresentationHost
    ) : InProcessPluginUiHost {
        override val applicationContext: Context get() = child.applicationContext
        override val pluginId: String get() = child.extensionId
        override val version: String get() = child.version
        override val scope get() = child.scope
        override val dataDir: File get() = child.dataDir
        override val cacheDir: File get() = child.cacheDir
        override val logger get() = child.logger
        override val runtimeEntryFile: File get() = child.runtimeEntryFile
        override val providers: InProcessProviderDirectory get() = child.providers
        override fun createPluginContext(baseContext: Context): Context =
            child.createExtensionContext(baseContext)
        override suspend fun invokeHostCapability(id: String, parametersJson: String): String =
            error("Ubuntu presentation may not invoke Host business primitives directly: $id")
    }

    private companion object {
        const val EXTENSION_ID = "ai_limbs.system_environment.ubuntu"
        const val PRESENTATION_PROVIDER_ID = "presentation.system_environment.ubuntu"
        const val PRESENTATION_KIND = "system_environment_presentation"
        val EMPTY_SHARED_UI = object : com.ai.limbs.plugin.runtime.InProcessSharedUiHost {
            override fun supports(componentId: String): Boolean = false
            override fun createComponent(componentId: String, parametersJson: String) =
                error("Ubuntu child display does not embed Plugin Center shared components")
        }
    }
}
