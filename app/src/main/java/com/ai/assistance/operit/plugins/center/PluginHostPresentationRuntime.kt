package com.ai.assistance.operit.plugins.center

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationEntry
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHandle
import com.ai.limbs.plugin.runtime.InProcessPluginPresentationHost
import com.ai.limbs.plugin.runtime.InProcessProviderBinding
import com.ai.limbs.plugin.runtime.InProcessProviderDirectory
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-only loader for signed android_inprocess presentation entries.
 *
 * The BUSINESS entry is never mounted here. A presentation entry receives only
 * [InProcessPluginPresentationHost], which has no capability/service publication,
 * native runtime, child runtime or business registration APIs.
 */
internal class ResidentPluginPresentationRuntime(
    context: Context,
    private val client: ResidentUiProxyClient,
    private val providerDirectory: ResidentProviderDirectory
) {
    private val appContext = context.applicationContext
    private val store = PluginStore.fromContext(appContext)
    private val stateRepository = PluginStateRepository(store)
    private val active = ConcurrentHashMap<String, ActivePresentation>()
    private val failedFingerprint = ConcurrentHashMap<String, String>()

    private data class Descriptor(
        val pluginId: String,
        val version: String,
        val runtimeEntry: String,
        val presentationEntryClass: String
    ) {
        val fingerprint: String
            get() = "$pluginId|$version|$runtimeEntry|$presentationEntryClass"
    }

    private data class ActivePresentation(
        val fingerprint: String,
        val handle: InProcessPluginPresentationHandle,
        val host: Host,
        val scope: CoroutineScope
    )

    suspend fun reconcile(array: JSONArray) {
        val desired = linkedMapOf<String, Descriptor>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val descriptor = Descriptor(
                pluginId = item.getString("plugin_id").trim(),
                version = item.getString("version").trim(),
                runtimeEntry = item.getString("runtime_entry").trim(),
                presentationEntryClass = item.getString("presentation_entry_class").trim()
            )
            require(descriptor.pluginId.isNotEmpty() && descriptor.version.isNotEmpty())
            require(descriptor.runtimeEntry.isNotEmpty() && descriptor.presentationEntryClass.isNotEmpty())
            check(desired.put(descriptor.pluginId, descriptor) == null) {
                "Duplicate presentation descriptor: ${descriptor.pluginId}"
            }
        }

        active.entries.toList().forEach { (pluginId, mounted) ->
            val next = desired[pluginId]
            if (next == null || next.fingerprint != mounted.fingerprint) {
                retire(pluginId, mounted)
            }
        }
        failedFingerprint.keys.retainAll(desired.keys)

        desired.values.forEach { descriptor ->
            val mounted = active[descriptor.pluginId]
            if (mounted?.fingerprint == descriptor.fingerprint) return@forEach
            if (failedFingerprint[descriptor.pluginId] == descriptor.fingerprint) return@forEach
            try {
                mount(descriptor)
                failedFingerprint.remove(descriptor.pluginId)
            } catch (error: Throwable) {
                failedFingerprint[descriptor.pluginId] = descriptor.fingerprint
                HostRuntimeLoggerFactory.plugin(descriptor.pluginId).e(
                    "ResidentPresentation",
                  "Presentation entry mount failed: ${error.message ?: error::class.java.simpleName}",
                    error
                )
            }
        }
    }

    suspend fun disconnectFailClosed() {
        failedFingerprint.clear()
        active.entries.toList().forEach { (pluginId, mounted) ->
            active.remove(pluginId, mounted)
            val failures = mutableListOf<Throwable>()

            val stopped =
                runCatching {
                    withTimeoutOrNull(DISCONNECT_CLEANUP_TIMEOUT_MS) {
                        mounted.handle.stop()
                        true
                    } ?: false
                }.getOrElse { error ->
                    failures += error
                    false
                }
            if (!stopped) {
                failures += IllegalStateException(
                    "Presentation stop timed out during Resident disconnect: $pluginId"
                )
            }

            runCatching { mounted.host.revokeAllPageProviders() }
                .onFailure(failures::add)

            mounted.scope.cancel()
            val joined =
                runCatching {
                    withTimeoutOrNull(DISCONNECT_CLEANUP_TIMEOUT_MS) {
                        mounted.scope.coroutineContext[Job]?.join()
                        true
                    } ?: false
                }.getOrElse { error ->
                    failures += error
                    false
                }
            if (!joined) {
                failures += IllegalStateException(
                    "Presentation scope did not stop during Resident disconnect: $pluginId"
                )
            }

            if (failures.isNotEmpty()) {
                HostRuntimeLoggerFactory.plugin(pluginId).e(
                    "ResidentPresentation",
                    "Fail-closed presentation cleanup completed with ${failures.size} error(s)",
                    IllegalStateException("Resident presentation disconnected").also { failure ->
                        failures.forEach(failure::addSuppressed)
                    }
                )
            }
        }
    }

    private suspend fun mount(descriptor: Descriptor) {
        val state = stateRepository.read(descriptor.pluginId)
            ?: throw PluginInstallException("PRESENTATION_STATE_MISSING", "Plugin state is missing")
        check(state.enabled && state.lastState == PluginLifecycleState.ACTIVE && state.activeVersion == descriptor.version) {
            "Presentation descriptor does not match ACTIVE plugin state: ${descriptor.pluginId}"
        }

        val versionDir = store.versionDir(descriptor.pluginId, descriptor.version)
        val contentDir = store.contentIn(versionDir).canonicalFile
        val manifest = stateRepository.readInstalledManifest(descriptor.pluginId, descriptor.version)
        check(manifest.pluginId == descriptor.pluginId && manifest.version == descriptor.version)
        check(manifest.runtime.kind == "android_inprocess")
        check(manifest.runtime.entry?.trim() == descriptor.runtimeEntry)
        val config = manifest.runtime.configJson?.let(::JSONObject) ?: JSONObject()
        check(config.optString("presentation_entry_class").trim() == descriptor.presentationEntryClass)

        val runtimeApk = File(contentDir, descriptor.runtimeEntry).canonicalFile
        check(runtimeApk.isFile && runtimeApk.path.startsWith(contentDir.path + File.separator)) {
            "Presentation runtime APK is outside verified content: ${descriptor.runtimeEntry}"
        }

        val optimizedDir = File(store.cacheDir(descriptor.pluginId), "presentation-dex/${descriptor.version}")
            .apply { check(mkdirs() || isDirectory) }
        val loader = DexClassLoader(
            runtimeApk.absolutePath,
            optimizedDir.absolutePath,
            null,
            appContext.classLoader
        )
        val entry = loader.loadClass(descriptor.presentationEntryClass)
            .getDeclaredConstructor()
            .newInstance() as? InProcessPluginPresentationEntry
            ?: throw PluginInstallException(
                "PRESENTATION_ENTRY_TYPE_INVALID",
                "${descriptor.presentationEntryClass} does not implement InProcessPluginPresentationEntry"
            )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = Host(descriptor, runtimeApk, loader, scope)
        val handle = try {
            entry.mount(host)
        } catch (error: Throwable) {
            val cleanup = runCatching { host.revokeAllPageProviders() }.exceptionOrNull()
            scope.cancel()
            scope.coroutineContext[Job]?.join()
            cleanup?.let(error::addSuppressed)
            throw error
        }
        val mounted = ActivePresentation(descriptor.fingerprint, handle, host, scope)
        val existing = active.putIfAbsent(descriptor.pluginId, mounted)
        if (existing != null) {
            val cleanupFailure = retireDetached(mounted)
            val failure = PluginInstallException(
                "PRESENTATION_ALREADY_MOUNTED",
                "Presentation entry is already mounted: ${descriptor.pluginId}"
            )
            cleanupFailure?.let(failure::addSuppressed)
            throw failure
        }
    }

    private suspend fun retire(pluginId: String, mounted: ActivePresentation) {
        val failure = retireDetached(mounted)
        if (failure == null) {
            active.remove(pluginId, mounted)
        } else {
            throw failure
        }
    }

    private suspend fun retireDetached(mounted: ActivePresentation): Throwable? {
        val failures = mutableListOf<Throwable>()
        try {
            mounted.handle.stop()
        } catch (error: Throwable) {
            failures += error
        }
        try {
            mounted.host.revokeAllPageProviders()
        } catch (error: Throwable) {
            failures += error
        }
        mounted.scope.cancel()
        try {
            mounted.scope.coroutineContext[Job]?.join()
        } catch (error: Throwable) {
            failures += error
        }
        if (failures.isEmpty()) return null
        return IllegalStateException(
            "Presentation retirement failed; Host-local UI ownership is retained"
        ).also { failure ->
            failures.forEach(failure::addSuppressed)
        }
    }

    private companion object {
        const val DISCONNECT_CLEANUP_TIMEOUT_MS = 1_500L
    }

    private inner class Host(
        private val descriptor: Descriptor,
        override val runtimeEntryFile: File,
        private val runtimeClassLoader: ClassLoader,
        override val scope: CoroutineScope
    ) : InProcessPluginPresentationHost {
        private val pageProviderHandles =
            java.util.concurrent.CopyOnWriteArrayList<AutoCloseable>()

        override val applicationContext: Context = appContext
        override val pluginId: String = descriptor.pluginId
        override val version: String = descriptor.version
        override val dataDir: File = store.dataDir(pluginId).apply { check(mkdirs() || isDirectory) }
        override val cacheDir: File = File(store.cacheDir(pluginId), "presentation").apply { mkdirs() }
        override val logger = HostRuntimeLoggerFactory.plugin(pluginId)

        override val providers: InProcessProviderDirectory = object : InProcessProviderDirectory {
            override fun resolve(id: String): InProcessProviderBinding? =
                providerDirectory.resolve(id)?.let(::binding)

            override fun snapshot(): List<InProcessProviderBinding> =
                providerDirectory.snapshot().map(::binding)

            override fun observe(id: String): StateFlow<InProcessProviderBinding?> =
                providerDirectory.observe(id)
                    .map { value -> value?.let(::binding) }
                    .stateIn(scope, SharingStarted.Eagerly, resolve(id))

            private fun binding(value: com.ai.assistance.operit.plugins.system.SystemPluginProviderBindingV2) =
                InProcessProviderBinding(
                    ownerPluginId = value.ownerPluginId,
                    id = value.id,
                    metadata = value.metadata.toMap(),
                    payload = value.payload
                )
        }

        override fun createPluginContext(baseContext: Context): Context =
            PresentationArchiveContext(baseContext, runtimeEntryFile, runtimeClassLoader)

        override fun registerPageProvider(
            id: String,
            provider: com.ai.limbs.plugin.runtime.InProcessPageProvider,
            metadata: Map<String, String>
        ): AutoCloseable {
            val delegate =
                providerDirectory.registerLocalPageProvider(pluginId, id, provider, metadata)
            pageProviderHandles += delegate
            return AutoCloseable {
                if (pageProviderHandles.remove(delegate)) {
                    delegate.close()
                }
            }
        }

        fun revokeAllPageProviders() {
            val failures = mutableListOf<Throwable>()
            pageProviderHandles.toList().asReversed().forEach { handle ->
                try {
                    handle.close()
                } catch (error: Throwable) {
                    failures += error
                } finally {
                    pageProviderHandles.remove(handle)
                }
            }
            if (failures.isNotEmpty()) {
                throw IllegalStateException(
                    "Could not revoke all Host-local presentation providers"
                ).also { failure ->
                    failures.forEach(failure::addSuppressed)
                }
            }
        }

        override suspend fun invokeHostCapability(id: String, parametersJson: String): String {
            val parameters = parseParameters(parametersJson)
            return client.command(
                JSONObject()
                    .put("command", "presentation_host_capability")
                    .put("owner_plugin_id", pluginId)
                    .put("capability_id", id.trim().lowercase())
                    .put("parameters", parameters)
            ).toString()
        }

        override suspend fun invokePluginCapability(id: String, parametersJson: String): String {
            val parameters = parseParameters(parametersJson)
            return client.command(
                JSONObject()
                    .put("command", "presentation_plugin_capability")
                    .put("owner_plugin_id", pluginId)
                    .put("capability_id", id.trim().lowercase())
                    .put("parameters", parameters)
            ).toString()
        }

        private fun parseParameters(raw: String): JSONObject =
            runCatching { JSONObject(raw) }.getOrElse {
                throw PluginInstallException(
                    "PRESENTATION_PARAMETERS_INVALID",
                    "Presentation capability parameters must be a JSON object"
                )
            }
    }

    /** UI Context backed by the verified plugin APK while retaining the Host Activity/window chain. */
    private class PresentationArchiveContext(
        base: Context,
        private val runtimeApk: File,
        private val runtimeClassLoader: ClassLoader
    ) : ContextWrapper(base) {
        private val archiveInfo by lazy {
            requireNotNull(packageManager.getPackageArchiveInfo(runtimeApk.absolutePath, 0)?.applicationInfo) {
                "Could not read presentation APK resources: ${runtimeApk.name}"
            }.apply {
                sourceDir = runtimeApk.absolutePath
                publicSourceDir = runtimeApk.absolutePath
            }
        }

        private val archiveResources: Resources by lazy {
            val pluginResources = packageManager.getResourcesForApplication(archiveInfo)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val loader = ResourcesLoader()
                buildList {
                    add(baseContext.applicationInfo.sourceDir)
                    baseContext.applicationInfo.splitSourceDirs?.let { addAll(it.asList()) }
                }.distinct().forEach { apkPath ->
                    val provider = ParcelFileDescriptor.open(
                        File(apkPath),
                        ParcelFileDescriptor.MODE_READ_ONLY
                    ).use { descriptor -> ResourcesProvider.loadFromApk(descriptor) }
                    loader.addProvider(provider)
                }
                pluginResources.addLoaders(loader)
            }
            pluginResources
        }

        override fun getResources(): Resources = archiveResources
        override fun getAssets(): AssetManager = archiveResources.assets
        override fun getClassLoader(): ClassLoader = runtimeClassLoader
        override fun getPackageName(): String = baseContext.packageName
    }
}
