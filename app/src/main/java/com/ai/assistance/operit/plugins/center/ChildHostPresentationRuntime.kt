package com.ai.assistance.operit.plugins.center

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import com.ai.limbs.plugin.runtime.ChildExtensionPresentationEntry
import com.ai.limbs.plugin.runtime.ChildExtensionPresentationHandle
import com.ai.limbs.plugin.runtime.ChildExtensionPresentationHost
import com.ai.limbs.plugin.runtime.ChildExtensionTarget
import com.ai.limbs.plugin.runtime.InProcessProviderBinding
import com.ai.limbs.plugin.runtime.InProcessProviderDirectory
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject

/** Host-only loader for presentation halves of ACTIVE Resident child extensions. */
internal class ResidentChildPresentationRuntime(
    context: Context,
    private val client: ResidentUiProxyClient,
    private val providerDirectory: ResidentProviderDirectory
) {
    private val appContext = context.applicationContext
    private val extensionsRoot = File(appContext.filesDir, "ai_limbs/child_runtime/extensions")
    private val dataRoot = File(appContext.filesDir, "ai_limbs/child_runtime/data")
    private val active = ConcurrentHashMap<String, ActivePresentation>()
    private val failedFingerprint = ConcurrentHashMap<String, String>()

    private data class Descriptor(
        val extensionId: String,
        val version: String,
        val target: ChildExtensionTarget,
        val runtimeEntry: String,
        val presentationEntryClass: String
    ) {
        val fingerprint: String
            get() = "$extensionId|$version|${target.parentPluginId}|${target.point}|${target.apiVersion}|$runtimeEntry|$presentationEntryClass"
    }

    private data class ActivePresentation(
        val fingerprint: String,
        val handle: ChildExtensionPresentationHandle,
        val host: Host,
        val scope: CoroutineScope
    )

    suspend fun reconcile(array: JSONArray) {
        val desired = linkedMapOf<String, Descriptor>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val descriptor = Descriptor(
                extensionId = item.getString("extension_id").trim(),
                version = item.getString("version").trim(),
                target = ChildExtensionTarget(
                    parentPluginId = item.getString("parent_plugin_id").trim(),
                    point = item.getString("point").trim(),
                    apiVersion = item.getInt("api_version")
                ),
                runtimeEntry = item.getString("runtime_entry").trim(),
                presentationEntryClass = item.getString("presentation_entry_class").trim()
            )
            require(descriptor.extensionId.isNotEmpty() && descriptor.version.isNotEmpty())
            require(descriptor.target.parentPluginId.isNotEmpty() && descriptor.target.point.isNotEmpty())
            require(descriptor.runtimeEntry.isNotEmpty() && descriptor.presentationEntryClass.isNotEmpty())
            check(desired.put(descriptor.extensionId, descriptor) == null) {
                "Duplicate child presentation descriptor: ${descriptor.extensionId}"
            }
        }

        active.entries.toList().forEach { (extensionId, mounted) ->
            val next = desired[extensionId]
            if (next == null || next.fingerprint != mounted.fingerprint) retire(extensionId, mounted)
        }
        failedFingerprint.keys.retainAll(desired.keys)

        desired.values.forEach { descriptor ->
            if (active[descriptor.extensionId]?.fingerprint == descriptor.fingerprint) return@forEach
            if (failedFingerprint[descriptor.extensionId] == descriptor.fingerprint) return@forEach
            try {
                mount(descriptor)
                failedFingerprint.remove(descriptor.extensionId)
            } catch (error: Throwable) {
                failedFingerprint[descriptor.extensionId] = descriptor.fingerprint
                HostRuntimeLoggerFactory.extension(descriptor.extensionId).e(
                    "ResidentChildPresentation",
                    "Child presentation entry mount failed: ${error.message ?: error::class.java.simpleName}",
                    error
                )
            }
        }
    }

    private suspend fun mount(descriptor: Descriptor) {
        val extensionDir = File(extensionsRoot, descriptor.extensionId).canonicalFile
        check(extensionDir.isDirectory && extensionDir.path.startsWith(extensionsRoot.canonicalPath + File.separator)) {
            "Child presentation install directory is unavailable: ${descriptor.extensionId}"
        }
        val manifestFile = File(extensionDir, "extension.json")
        check(manifestFile.isFile) { "Installed child manifest is missing: ${descriptor.extensionId}" }
        val manifest = JSONObject(manifestFile.readText())
        check(manifest.getString("format") == "AIL_EXTENSION_V1")
        check(manifest.getInt("schema_version") == 1)
        check(manifest.getString("extension_id").trim() == descriptor.extensionId)
        check(manifest.getString("version").trim() == descriptor.version)
        val target = manifest.getJSONObject("target")
        check(target.getString("plugin_id").trim() == descriptor.target.parentPluginId)
        check(target.getString("extension_point").trim() == descriptor.target.point)
        check(target.getInt("api") == descriptor.target.apiVersion)
        val runtime = manifest.getJSONObject("runtime")
        check(runtime.getString("kind") == "android_child")
        check(runtime.getString("entry").trim() == descriptor.runtimeEntry)
        check(
            runtime.getJSONObject("config").optString("presentation_entry_class").trim() ==
                descriptor.presentationEntryClass
        )

        val runtimeApk = File(extensionDir, descriptor.runtimeEntry).canonicalFile
        check(runtimeApk.isFile && runtimeApk.path.startsWith(extensionDir.path + File.separator)) {
            "Child presentation runtime APK escapes admitted extension root"
        }
        val optimizedDir = File(
            appContext.codeCacheDir,
            "ai_limbs/child_presentation/${descriptor.extensionId}/${descriptor.version}"
        ).apply { check(mkdirs() || isDirectory) }
        val loader = DexClassLoader(
            runtimeApk.absolutePath,
            optimizedDir.absolutePath,
            null,
            appContext.classLoader
        )
        val entry = loader.loadClass(descriptor.presentationEntryClass)
            .getDeclaredConstructor()
            .newInstance() as? ChildExtensionPresentationEntry
            ?: throw PluginInstallException(
                "CHILD_PRESENTATION_ENTRY_TYPE_INVALID",
                "${descriptor.presentationEntryClass} does not implement ChildExtensionPresentationEntry"
            )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = Host(descriptor, runtimeApk, loader, scope)
        val handle = try {
            entry.mount(host)
        } catch (error: Throwable) {
            val cleanup = runCatching { host.revokeAllProviders() }.exceptionOrNull()
            scope.cancel()
            scope.coroutineContext[Job]?.join()
            cleanup?.let(error::addSuppressed)
            throw error
        }
        val mounted = ActivePresentation(descriptor.fingerprint, handle, host, scope)
        val existing = active.putIfAbsent(descriptor.extensionId, mounted)
        if (existing != null) {
            val cleanupFailure = retireDetached(mounted)
            val failure = PluginInstallException(
                "CHILD_PRESENTATION_ALREADY_MOUNTED",
                "Child presentation is already mounted: ${descriptor.extensionId}"
            )
            cleanupFailure?.let(failure::addSuppressed)
            throw failure
        }
    }

    private suspend fun retire(extensionId: String, mounted: ActivePresentation) {
        val failure = retireDetached(mounted)
        if (failure == null) active.remove(extensionId, mounted) else throw failure
    }

    private suspend fun retireDetached(mounted: ActivePresentation): Throwable? {
        val failures = mutableListOf<Throwable>()
        try { mounted.handle.stop() } catch (error: Throwable) { failures += error }
        try { mounted.host.revokeAllProviders() } catch (error: Throwable) { failures += error }
        mounted.scope.cancel()
        try { mounted.scope.coroutineContext[Job]?.join() } catch (error: Throwable) { failures += error }
        if (failures.isEmpty()) return null
        return IllegalStateException(
            "Child presentation retirement failed; Host-local ownership is retained"
        ).also { failure -> failures.forEach(failure::addSuppressed) }
    }

    private inner class Host(
        private val descriptor: Descriptor,
        override val runtimeEntryFile: File,
        private val runtimeClassLoader: ClassLoader,
        override val scope: CoroutineScope
    ) : ChildExtensionPresentationHost {
        private val providerHandles = CopyOnWriteArrayList<AutoCloseable>()

        override val applicationContext: Context = appContext
        override val extensionId: String = descriptor.extensionId
        override val version: String = descriptor.version
        override val target: ChildExtensionTarget = descriptor.target
        override val dataDir: File = File(dataRoot, extensionId).apply { check(mkdirs() || isDirectory) }
        override val cacheDir: File = File(
            appContext.cacheDir,
            "ai_limbs/child_presentation/$extensionId"
        ).apply { check(mkdirs() || isDirectory) }
        override val logger = HostRuntimeLoggerFactory.extension(extensionId)

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

        override fun createExtensionContext(baseContext: Context): Context =
            ChildPresentationArchiveContext(baseContext, runtimeEntryFile, runtimeClassLoader)

        override fun registerPresentationProvider(
            id: String,
            payload: Any,
            metadata: Map<String, String>
        ): AutoCloseable {
            val handle = providerDirectory.registerLocalPresentationProvider(
                extensionId, id, payload, metadata
            )
            providerHandles += handle
            return AutoCloseable {
                if (providerHandles.remove(handle)) handle.close()
            }
        }

        override suspend fun invokeChildCapability(id: String, parametersJson: String): String {
            val parameters = runCatching { JSONObject(parametersJson) }.getOrElse {
                throw PluginInstallException(
                    "CHILD_PRESENTATION_PARAMETERS_INVALID",
                    "Child presentation capability parameters must be a JSON object"
                )
            }
            return client.command(
                JSONObject()
                    .put("command", "presentation_child_capability")
                    .put("extension_id", extensionId)
                    .put("parent_plugin_id", target.parentPluginId)
                    .put("capability_id", id.trim().lowercase())
                    .put("parameters", parameters)
            ).toString()
        }

        override suspend fun invokePresentationCommand(command: String, parametersJson: String): String {
            val normalizedCommand = command.trim().lowercase()
            require(normalizedCommand.matches(Regex("[a-z0-9][a-z0-9_.-]{0,127}"))) {
                "Invalid child presentation command: $command"
            }
            val parameters = runCatching { JSONObject(parametersJson) }.getOrElse {
                throw PluginInstallException(
                    "CHILD_PRESENTATION_PARAMETERS_INVALID",
                    "Child presentation command parameters must be a JSON object"
                )
            }
            return client.command(
                JSONObject()
                    .put("command", "presentation_child_command")
                    .put("extension_id", extensionId)
                    .put("parent_plugin_id", target.parentPluginId)
                    .put("presentation_command", normalizedCommand)
                    .put("parameters", parameters)
            ).toString()
        }

        fun revokeAllProviders() {
            val failures = mutableListOf<Throwable>()
            providerHandles.toList().asReversed().forEach { handle ->
                try { handle.close() } catch (error: Throwable) { failures += error }
                finally { providerHandles.remove(handle) }
            }
            if (failures.isNotEmpty()) {
                throw IllegalStateException(
                    "Could not revoke all Host-local child presentation providers"
                ).also { failure -> failures.forEach(failure::addSuppressed) }
            }
        }
    }

    private class ChildPresentationArchiveContext(
        base: Context,
        private val runtimeApk: File,
        private val runtimeClassLoader: ClassLoader
    ) : ContextWrapper(base) {
        private val archiveInfo by lazy {
            requireNotNull(packageManager.getPackageArchiveInfo(runtimeApk.absolutePath, 0)?.applicationInfo) {
                "Could not read child presentation APK resources: ${runtimeApk.name}"
            }.apply {
                sourceDir = runtimeApk.absolutePath
                publicSourceDir = runtimeApk.absolutePath
            }
        }

        private val archiveResources: Resources by lazy {
            val resources = packageManager.getResourcesForApplication(archiveInfo)
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
                resources.addLoaders(loader)
            }
            resources
        }

        override fun getResources(): Resources = archiveResources
        override fun getAssets(): AssetManager = archiveResources.assets
        override fun getClassLoader(): ClassLoader = runtimeClassLoader
        override fun getPackageName(): String = baseContext.packageName
    }
}
