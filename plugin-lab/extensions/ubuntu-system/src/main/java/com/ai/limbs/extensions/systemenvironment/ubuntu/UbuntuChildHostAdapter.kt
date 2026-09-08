package com.ai.limbs.extensions.systemenvironment.ubuntu

import android.content.Context
import com.ai.limbs.plugin.runtime.ChildExtensionHost
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessCapabilitySpec
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessProviderBinding
import com.ai.limbs.plugin.runtime.InProcessProviderDirectory
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.plugin.runtime.InProcessServiceBinding
import com.ai.limbs.plugin.runtime.InProcessServiceDirectory
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class UbuntuChildHostAdapter(
    private val child: ChildExtensionHost
) : InProcessPluginHost {
    private val capabilitiesById = ConcurrentHashMap<String, InProcessCapabilitySpec>()

    override val applicationContext: Context =
        child.createExtensionContext(child.applicationContext)
    override val pluginId: String = child.extensionId
    override val version: String = child.version
    override val scope = child.scope
    override val dataDir = child.dataDir
    override val cacheDir = child.cacheDir
    override val runtimeEntryFile = child.runtimeEntryFile
    override val nativeRuntime = child.nativeRuntime

    override val providers: InProcessProviderDirectory = object : InProcessProviderDirectory {
        override fun resolve(id: String): InProcessProviderBinding? = null
        override fun snapshot(): List<InProcessProviderBinding> = emptyList()
        override fun observe(id: String): StateFlow<InProcessProviderBinding?> =
            MutableStateFlow(null)
    }

    override val services: InProcessServiceDirectory = object : InProcessServiceDirectory {
        override fun resolve(id: String, minApi: Int?): InProcessServiceBinding? = null
    }

    override fun createPluginContext(baseContext: Context): Context =
        child.createExtensionContext(baseContext)

    override fun createRuntimeContext(
        baseContext: Context,
        runtimeEntryFile: java.io.File,
        runtimeClassLoader: ClassLoader
    ): Context = child.createExtensionContext(baseContext)

    override fun registerProvider(
        id: String,
        payload: Any,
        metadata: Map<String, String>
    ) {
        error("Ubuntu child may not register a parent provider")
    }

    override fun registerCapability(
        id: String,
        displayName: String,
        description: String,
        executor: InProcessCapabilityExecutor
    ) {
        registerCapability(
            InProcessCapabilitySpec(
                id = id,
                displayName = displayName,
                description = description,
                executor = executor
            )
        )
    }

    override fun registerCapability(spec: InProcessCapabilitySpec) {
        check(capabilitiesById.putIfAbsent(spec.id, spec) == null) {
            "Duplicate Ubuntu child capability: ${spec.id}"
        }
    }

    override fun registerHomeTile(tile: InProcessHomeTile) {
        error("Ubuntu child may not register a home tile")
    }

    override fun registerScreen(screen: InProcessScreen) {
        error("Ubuntu child may not register a screen")
    }

    override fun registerExtension(
        point: String,
        id: String,
        payload: Any,
        metadata: Map<String, String>
    ) {
        error("Ubuntu child may not register a parent extension")
    }

    override suspend fun invokeHostCapability(
        id: String,
        parametersJson: String
    ): String = child.invokeHostCapability(id, parametersJson)

    fun capabilitySpecs(): Map<String, InProcessCapabilitySpec> =
        capabilitiesById.toMap()
}
