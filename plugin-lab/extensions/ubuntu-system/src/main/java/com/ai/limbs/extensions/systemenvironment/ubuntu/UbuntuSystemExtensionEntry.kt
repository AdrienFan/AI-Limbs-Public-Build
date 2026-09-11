package com.ai.limbs.extensions.systemenvironment.ubuntu

import android.view.View
import com.ai.limbs.plugin.runtime.ChildAiIngressDiscovery
import com.ai.limbs.plugin.runtime.ChildExtensionEntry
import com.ai.limbs.plugin.runtime.ChildExtensionHandle
import com.ai.limbs.plugin.runtime.ChildExtensionHost
import com.ai.limbs.plugin.runtime.InProcessSharedUiHost
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentCapabilityEndpoint
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentCapabilityIds
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentContract
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentConfigurableDisplayAdapter
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentIdleMode
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentIdlePolicy
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentRuntimeController
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentRuntimePhase
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentRuntimeState
import com.ai.limbs.systemenvironment.contract.SystemEnvironmentSubsystemContribution
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.TerminalManager
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuIdleMode
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuIdlePolicy
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuRuntimePhase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONObject

class UbuntuSystemExtensionEntry : ChildExtensionEntry {
    override suspend fun mount(host: ChildExtensionHost): ChildExtensionHandle {
        require(host.target.parentPluginId == SystemEnvironmentContract.PARENT_PLUGIN_ID)
        require(host.target.point == SystemEnvironmentContract.EXTENSION_POINT)
        require(host.target.apiVersion == SystemEnvironmentContract.API_VERSION)

        val adapter = UbuntuChildHostAdapter(host)
        val subsystem = UbuntuSubsystem.mount(adapter)
        val capabilitySpecs = adapter.capabilitySpecs()
        val supportedCapabilityNames = capabilitySpecs.values.flatMapTo(linkedSetOf()) { spec ->
            listOf(spec.id) + spec.invokeAliases
        }
        require(SystemEnvironmentCapabilityIds.ALL.all { it in supportedCapabilityNames }) {
            "Ubuntu child must preserve the legacy system-environment aliases"
        }

        host.publishAiIngressDiscovery(ubuntuToolDiscovery())

        host.publish(
            SystemEnvironmentSubsystemContribution(
                subsystemId = host.extensionId,
                displayName = "Ubuntu",
                description = "Ubuntu Noble arm64、PTY 与终端显示适配器。",
                runtime = UbuntuRuntimeController(host, subsystem.terminal),
                display = object : SystemEnvironmentConfigurableDisplayAdapter {
                    override fun createView(context: android.content.Context): View =
                        subsystem.pageProvider.createView(context, NoSharedUi)

                    override fun createConfigurationView(
                        context: android.content.Context,
                        onRequestDisplay: () -> Unit
                    ): View = subsystem.pageProvider.createConfigurationView(
                        context = context,
                        onRequestDisplay = onRequestDisplay
                    )
                },
                capabilities = object : SystemEnvironmentCapabilityEndpoint {
                    override val supportedCapabilityIds = supportedCapabilityNames
                    override suspend fun invoke(
                        capabilityId: String,
                        parametersJson: String
                    ): String {
                        val normalized = capabilityId.trim().lowercase()
                        val spec = capabilitySpecs[normalized]
                            ?: capabilitySpecs.values.firstOrNull { candidate ->
                                candidate.invokeAliases.any { it.trim().lowercase() == normalized }
                            }
                            ?: error("Unsupported Ubuntu capability: $capabilityId")
                        return spec.executor.invoke(parametersJson)
                    }
                }
            ),
            metadata = mapOf(
                "kind" to "system_environment_subsystem",
                "display_adapter" to "android_view",
                "runtime" to "ubuntu_noble_arm64"
            )
        )

        return ChildExtensionHandle { subsystem.close() }
    }
}

private fun ubuntuToolDiscovery(): ChildAiIngressDiscovery =
    ChildAiIngressDiscovery(
        schemaId = "ai_limbs.subsystem_tool_discovery.v1",
        payloadJson = JSONObject()
            .put("type", "SUBSYSTEM_TOOL_DISCOVERY")
            .put("display_name", "Ubuntu")
            .put("query_tool", "ail-tool")
            .put("query_existing_first", true)
            .put("reuse_existing_first", true)
            .put("install_only_if_no_match", true)
            .put("cleanup_install_artifacts_after_verified", true)
            .put(
                "instruction",
                "Before installing a new Ubuntu tool, query ail-tool first and prefer an existing suitable tool. " +
                    "Install only when no suitable match exists; after verification, clean expendable packages, caches, and temporary files."
            )
            .toString()
    )

private class UbuntuRuntimeController(
    host: ChildExtensionHost,
    private val terminal: TerminalManager
) : SystemEnvironmentRuntimeController {
    override val state: StateFlow<SystemEnvironmentRuntimeState> =
        terminal.ubuntuRuntimeState
            .map { ubuntu ->
                SystemEnvironmentRuntimeState(
                    phase = when (ubuntu.phase) {
                        UbuntuRuntimePhase.STOPPED -> SystemEnvironmentRuntimePhase.STOPPED
                        UbuntuRuntimePhase.STARTING -> SystemEnvironmentRuntimePhase.STARTING
                        UbuntuRuntimePhase.RUNNING -> SystemEnvironmentRuntimePhase.RUNNING
                        UbuntuRuntimePhase.STOPPING -> SystemEnvironmentRuntimePhase.STOPPING
                        UbuntuRuntimePhase.ERROR -> SystemEnvironmentRuntimePhase.FAILED
                    },
                    detail = ubuntu.error ?: ubuntu.detail
                )
            }
            .stateIn(
                host.scope,
                SharingStarted.Eagerly,
                terminal.currentUbuntuRuntimeState().let { ubuntu ->
                    SystemEnvironmentRuntimeState(
                        phase = SystemEnvironmentRuntimePhase.valueOf(
                            if (ubuntu.phase == UbuntuRuntimePhase.ERROR) "FAILED" else ubuntu.phase.name
                        ),
                        detail = ubuntu.error ?: ubuntu.detail
                    )
                }
            )

    override val idlePolicy: StateFlow<SystemEnvironmentIdlePolicy> =
        terminal.ubuntuIdlePolicy
            .map(::toContract)
            .stateIn(
                host.scope,
                SharingStarted.Eagerly,
                toContract(terminal.currentUbuntuIdlePolicy())
            )

    override suspend fun start() {
        terminal.startUbuntu(offerDevelopmentPrompt = false)
    }

    override suspend fun stop() {
        terminal.stopUbuntu()
    }

    override suspend fun setIdlePolicy(policy: SystemEnvironmentIdlePolicy) {
        terminal.updateUbuntuIdlePolicy(
            UbuntuIdlePolicy(
                mode = UbuntuIdleMode.valueOf(policy.mode.name),
                customMinutes = policy.customMinutes
            )
        )
    }

    private fun toContract(policy: UbuntuIdlePolicy): SystemEnvironmentIdlePolicy =
        SystemEnvironmentIdlePolicy(
            mode = SystemEnvironmentIdleMode.valueOf(policy.mode.name),
            customMinutes = policy.customMinutes
        )
}

private object NoSharedUi : InProcessSharedUiHost {
    override fun supports(componentId: String): Boolean = false

    override fun createComponent(componentId: String, parametersJson: String): View =
        error("Ubuntu display adapter does not host Plugin Center components")
}
