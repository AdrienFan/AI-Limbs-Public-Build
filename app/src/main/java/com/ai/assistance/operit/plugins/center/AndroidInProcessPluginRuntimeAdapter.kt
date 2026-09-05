package com.ai.assistance.operit.plugins.center

import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessProviderBinding
import com.ai.limbs.plugin.runtime.InProcessProviderDirectory
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.plugin.runtime.InProcessServiceBinding
import com.ai.limbs.plugin.runtime.InProcessServiceDirectory
import dalvik.system.DexClassLoader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONObject

internal class AndroidInProcessPluginRuntimeAdapter(
    private val contributions: PluginContributionRegistry,
    private val notificationHost: PluginNotificationHost,
    private val identityRegistry: OfficialPluginIdentityRegistry
) : PluginRuntimeAdapter {
    override val kind: String = "android_inprocess"

    override suspend fun mount(context: PluginRuntimeAdapterContext): PluginRuntimeHandle {
        requireSystemPlugin(context)
        val entryFile = runtimeEntry(context)
        freezeRuntimeApk(entryFile)
        val entryClass = runtimeEntryClass(context)
        val optimizedDir = File(context.cacheDir, "dex/${context.manifest.version}").apply { mkdirs() }
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loader = DexClassLoader(
            entryFile.absolutePath,
            optimizedDir.absolutePath,
            null,
            context.appContext.classLoader
        )
        val entry = try {
            val type = loader.loadClass(entryClass)
            val instance = type.getDeclaredConstructor().newInstance()
            instance as? InProcessPluginEntry
                ?: throw PluginInstallException(
                    "INPROCESS_ENTRY_TYPE_INVALID",
                    "$entryClass does not implement InProcessPluginEntry"
                )
        } catch (error: PluginInstallException) {
            runtimeScope.cancel()
            throw error
        } catch (error: Throwable) {
            runtimeScope.cancel()
            throw PluginInstallException(
                "INPROCESS_ENTRY_LOAD_FAILED",
                "Could not load $entryClass: ${error.message ?: error::class.java.simpleName}",
                error
            )
        }

        val host = Host(context, runtimeScope, contributions, notificationHost)
        val handle = try {
            entry.mount(host)
        } catch (error: Throwable) {
            runtimeScope.cancel()
            throw error
        }
        return object : PluginRuntimeHandle {
            override suspend fun stop() {
                try {
                    handle.stop()
                } finally {
                    runtimeScope.cancel()
                }
            }
        }
    }

    private fun requireSystemPlugin(context: PluginRuntimeAdapterContext) {
        if (!identityRegistry.isTrusted(context.manifest, context.installMetadata)) {
            throw PluginInstallException(
                "INPROCESS_SYSTEM_IDENTITY_REQUIRED",
                "android_inprocess requires Plugin Center approval and the official plugin signer"
            )
        }
    }

    private fun runtimeEntry(context: PluginRuntimeAdapterContext): File {
        val raw = context.manifest.runtime.entry
            ?: throw PluginInstallException("RUNTIME_ENTRY_MISSING", "android_inprocess requires runtime.entry")
        val root = context.contentDir.canonicalFile
        val file = File(root, raw).canonicalFile
        if (!file.isFile || !file.path.startsWith(root.path + File.separator)) {
            throw PluginInstallException("RUNTIME_ENTRY_NOT_FOUND", "Runtime APK was not found: $raw")
        }
        return file
    }

    private fun freezeRuntimeApk(file: File) {
        if (file.canWrite() && !file.setReadOnly()) {
            throw PluginInstallException(
                "INPROCESS_RUNTIME_READONLY_FAILED",
                "Could not make privileged runtime APK read-only: ${file.name}"
            )
        }
        if (file.canWrite()) {
            throw PluginInstallException(
                "INPROCESS_RUNTIME_WRITABLE",
                "Privileged runtime APK remains writable: ${file.name}"
            )
        }
    }

    private fun runtimeEntryClass(context: PluginRuntimeAdapterContext): String {
        val config = context.manifest.runtime.configJson?.let(::JSONObject) ?: JSONObject()
        return config.optString("entry_class").trim().ifBlank {
            throw PluginInstallException(
                "INPROCESS_ENTRY_CLASS_MISSING",
                "android_inprocess runtime.config.entry_class is required"
            )
        }
    }


    private class Host(
        private val context: PluginRuntimeAdapterContext,
        override val scope: CoroutineScope,
        private val contributions: PluginContributionRegistry,
        private val notificationHost: PluginNotificationHost
    ) : InProcessPluginHost {
        override val applicationContext = context.appContext
        override val pluginId: String = context.manifest.pluginId
        override val version: String = context.manifest.version
        override val dataDir: File = context.dataDir
        override val cacheDir: File = context.cacheDir
        override val services: InProcessServiceDirectory = object : InProcessServiceDirectory {
            override fun resolve(id: String, minApi: Int?): InProcessServiceBinding? {
                val resolved = context.payloadContext.serviceResolver.resolve(id, minApi)
                    ?: return null
                return InProcessServiceBinding(
                    resolved.ownerPluginId,
                    resolved.serviceId,
                    resolved.apiVersion,
                    resolved.metadata.toMap()
                ) { operation, parametersJson ->
                    val parameters = runCatching { JSONObject(parametersJson) }.getOrElse {
                        throw PluginInstallException(
                            "INPROCESS_PARAMETERS_INVALID",
                            "Plugin service parameters must be JSON"
                        )
                    }
                    resolved.invoke(operation, parameters).toString()
                }
            }
        }

        override val providers: InProcessProviderDirectory = object : InProcessProviderDirectory {
            override fun resolve(id: String): InProcessProviderBinding? {
                if (id == com.ai.limbs.plugin.runtime.InProcessSystemIds.NOTIFICATION_HOST_PROVIDER) {
                    return notificationHost.bindingFor(pluginId, context.payloadContext.permissions.grantedScopes)
                }
                return contributions.find(PluginContributionKind.PROVIDER, id)?.let(::providerBinding)
            }

            override fun snapshot(): List<InProcessProviderBinding> = buildList {
                notificationHost.bindingFor(pluginId, context.payloadContext.permissions.grantedScopes)?.let(::add)
                addAll(
                    contributions.listAll()
                        .filter { it.kind == PluginContributionKind.PROVIDER }
                        .map(::providerBinding)
                )
            }

            override fun observe(id: String): StateFlow<InProcessProviderBinding?> =
                contributions.revision
                    .map { resolve(id) }
                    .stateIn(scope, SharingStarted.Eagerly, resolve(id))
        }

        override fun registerProvider(id: String, payload: Any, metadata: Map<String, String>) {
            context.payloadContext.registrar.registerProvider(id, payload, metadata)
        }

        override fun registerCapability(
            id: String,
            displayName: String,
            description: String,
            executor: InProcessCapabilityExecutor
        ) {
            context.payloadContext.registrar.registerCapability(
                id,
                PluginCapabilitySpec(
                    displayName = displayName,
                    description = description,
                    executor = PluginCapabilityExecutor { parameters ->
                        val raw = executor.invoke(parameters.toString())
                        runCatching { JSONObject(raw) }.getOrElse {
                            JSONObject().put("content", raw)
                        }
                    }
                )
            )
        }

        override fun registerHomeTile(tile: InProcessHomeTile) {
            context.payloadContext.registrar.registerExtension(
                PluginExtensionPoints.UI_HOME_TILE,
                tile.id,
                PluginHomeTileSpec(
                    ownerPluginId = pluginId,
                    id = tile.id,
                    title = tile.title,
                    description = tile.description,
                    screenId = tile.screenId
                )
            )
        }

        override fun registerScreen(screen: InProcessScreen) {
            // Do not parse component JSON here.  android_inprocess plugins and declarative plugins
            // must cross the same opaque ui.screen@2 boundary so future component types never create
            // another Host runtime dependency.
            context.payloadContext.registrar.registerExtension(
                PluginExtensionPoints.UI_SCREEN,
                screen.id,
                PluginScreenSpec(
                    ownerPluginId = pluginId,
                    id = screen.id,
                    title = screen.title,
                    description = screen.description,
                    schemaId = screen.schemaId,
                    documentJson = screen.documentJson
                )
            )
        }

        override suspend fun invokeHostCapability(id: String, parametersJson: String): String {
            val parameters = runCatching { JSONObject(parametersJson) }.getOrElse {
                throw PluginInstallException("INPROCESS_PARAMETERS_INVALID", "Host capability parameters must be JSON")
            }
            if (id == PluginSigningHostService.CAPABILITY_ID) {
                if (pluginId != PluginSigningHostService.PACKAGER_PLUGIN_ID) {
                    throw PluginInstallException("PRIVATE_HOST_CAPABILITY_FORBIDDEN", "Private signing host is reserved for the official Packager plugin")
                }
                return PluginSigningHostService(applicationContext).execute(parameters).toString()
            }
            return context.payloadContext.capabilityInvoker.invoke(id, parameters).toString()
        }

        private fun providerBinding(record: PluginContributionRecord) =
            InProcessProviderBinding(
                ownerPluginId = record.ownerPluginId,
                id = record.id,
                metadata = record.metadata.toMap(),
                payload = record.payload
            )
    }
}
