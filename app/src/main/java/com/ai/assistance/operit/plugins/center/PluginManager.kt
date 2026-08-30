package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

enum class PluginInstallDisposition {
    INSTALLED,
    ALREADY_INSTALLED
}

data class PluginInstallResult(
    val disposition: PluginInstallDisposition,
    val pluginId: String,
    val version: String,
    val packageSha256: String,
    val trust: PluginTrustDecision,
    val state: PluginPersistentState
)

data class PluginSnapshot(
    val pluginId: String,
    val versions: List<String>,
    val persistentState: PluginPersistentState?,
    val activeManifest: PluginManifest?,
    val mountedVersion: String?,
    val contributions: List<PluginContributionRecord>
)
private data class ActivePluginMount(
    val version: String,
    val runtime: HostedPluginRuntime
)

internal class PluginManager(
    private val appContext: Context,
    val store: PluginStore,
    private val trustVerifier: PluginTrustVerifier,
    val runtimeAdapters: PluginRuntimeAdapterRegistry,
    val contributions: PluginContributionRegistry,
    private val extensionRouter: ExtensionRouter,
    private val capabilityBinder: PluginCapabilityBinder,
    private val runtimeHost: PluginRuntimeHost,
    private val pluginContextFactory: PluginContextFactory,
    private val packageVerifier: PluginPackageVerifier = PluginPackageVerifier()
) {
    private val stateRepository = PluginStateRepository(store)
    private val activeMounts = ConcurrentHashMap<String, ActivePluginMount>()
    private val mutex = Mutex()

    fun initialize() {
        store.initialize()
    }

    suspend fun install(
        sourcePackage: File,
        options: PluginInstallOptions = PluginInstallOptions()
    ): PluginInstallResult = locked {
        installLocked(sourcePackage, options)
    }

    suspend fun enable(pluginId: String): PluginPersistentState = locked {
        enableLocked(pluginId)
    }
    suspend fun disable(pluginId: String): PluginPersistentState = locked {
        disableLocked(pluginId)
    }

    suspend fun activateVersion(
        pluginId: String,
        version: String
    ): PluginPersistentState = locked {
        activateVersionLocked(pluginId, version)
    }

    suspend fun rollback(pluginId: String): PluginPersistentState = locked {
        val state = requireState(pluginId)
        val target = state.previousVersion
            ?: throw PluginInstallException("ROLLBACK_UNAVAILABLE", "No previous version is available for $pluginId")
        activateVersionLocked(pluginId, target)
    }

    suspend fun uninstall(
        pluginId: String,
        removeData: Boolean = false
    ) = locked {
        ensureNoEnabledDependents(pluginId)
        requireCleanRuntimeStop(pluginId, unmountLocked(pluginId))
        store.deletePlugin(pluginId, removeData)
    }

    suspend fun restoreEnabledPlugins() = locked {
        restoreEnabledPluginsLocked()
    }

    suspend fun shutdown() = locked {
        shutdownLocked()
    }

    suspend fun snapshots(): List<PluginSnapshot> = locked {
        store.listPluginIds().map(::snapshotLocked)
    }

    suspend fun snapshot(pluginId: String): PluginSnapshot = locked {
        snapshotLocked(pluginId)
    }
    private suspend fun installLocked(
        sourcePackage: File,
        options: PluginInstallOptions
    ): PluginInstallResult {
        if (!sourcePackage.name.lowercase().endsWith(PluginAbi.PACKAGE_EXTENSION)) {
            throw PluginInstallException("PACKAGE_EXTENSION_INVALID", "Plugin package must use ${PluginAbi.PACKAGE_EXTENSION}")
        }
        val transaction = store.createStagingTransaction()
        try {
            val managedPackage = store.managedPackageIn(transaction)
            store.copyIntoManagedStaging(sourcePackage, managedPackage)
            val verified = packageVerifier.verifyAndExtract(
                managedPackage = managedPackage,
                contentDir = store.contentIn(transaction)
            )
            val trust = trustVerifier.verify(
                managedPackage = managedPackage,
                contentDir = store.contentIn(transaction),
                manifest = verified.manifest,
                packageSha256 = verified.packageSha256
            )
            if (!trust.isTrusted && !options.allowUntrustedForDevelopment) {
                throw PluginInstallException(
                    "PLUGIN_UNTRUSTED",
                    trust.reason ?: "Plugin publisher is not trusted"
                )
            }
            return commitInstallLocked(sourcePackage, transaction, verified, trust, options)
        } finally {
            if (transaction.exists()) transaction.deleteRecursively()
        }
    }
    private suspend fun commitInstallLocked(
        sourcePackage: File,
        transaction: File,
        verified: VerifiedPluginPackage,
        trust: PluginTrustDecision,
        options: PluginInstallOptions
    ): PluginInstallResult {
        val manifest = verified.manifest
        val existingDir = store.versionDir(manifest.pluginId, manifest.version)
        if (existingDir.exists()) {
            val metadata = stateRepository.readInstallMetadata(manifest.pluginId, manifest.version)
                ?: throw PluginInstallException(
                    "VERSION_STORE_CORRUPT",
                    "Installed version exists without valid install metadata"
                )
            if (metadata.packageSha256 != verified.packageSha256) {
                throw PluginInstallException(
                    "VERSION_DIGEST_CONFLICT",
                    "${manifest.pluginId} ${manifest.version} is already installed with different content"
                )
            }
            val state = stateRepository.read(manifest.pluginId)
                ?: defaultState(manifest.pluginId, manifest.version).also(stateRepository::write)
            if (options.enableAfterInstall && !state.enabled) enableLocked(manifest.pluginId)
            return PluginInstallResult(
                PluginInstallDisposition.ALREADY_INSTALLED,
                manifest.pluginId,
                manifest.version,
                verified.packageSha256,
                trust,
                stateRepository.read(manifest.pluginId) ?: state
            )
        }
        stateRepository.writeInstallMetadata(
            transaction,
            PluginInstallMetadata(
                pluginId = manifest.pluginId,
                version = manifest.version,
                packageSha256 = verified.packageSha256,
                installedAtEpochMs = System.currentTimeMillis(),
                trustVerdict = trust.verdict,
                signerId = trust.signerId,
                sourceFileName = sourcePackage.name
            )
        )
        store.commitVersion(transaction, manifest.pluginId, manifest.version)
        val previousState = stateRepository.read(manifest.pluginId)
        val state = if (previousState == null) {
            defaultState(manifest.pluginId, manifest.version)
        } else {
            previousState.copy(updatedAtEpochMs = System.currentTimeMillis())
        }
        stateRepository.write(state)
        if (options.enableAfterInstall && !state.enabled) enableLocked(manifest.pluginId)
        return PluginInstallResult(
            PluginInstallDisposition.INSTALLED,
            manifest.pluginId,
            manifest.version,
            verified.packageSha256,
            trust,
            stateRepository.read(manifest.pluginId) ?: state
        )
    }

    private suspend fun enableLocked(pluginId: String): PluginPersistentState {
        val state = requireState(pluginId)
        val version = state.activeVersion
            ?: throw PluginInstallException("ACTIVE_VERSION_MISSING", "No active version is selected for $pluginId")
        val manifest = stateRepository.readInstalledManifest(pluginId, version)
        if (state.enabled && activeMounts[pluginId]?.version == version) {
            return writeState(
                state.copy(
                    lastState = PluginLifecycleState.ACTIVE,
                    lastError = null
                )
            )
        }
        if (state.enabled && manifest.activationMode != PluginActivationMode.HOT) return state
        val enabling = state.copy(
            enabled = true,
            lastState = if (manifest.activationMode == PluginActivationMode.HOT) {
                PluginLifecycleState.MOUNTING
            } else {
                PluginLifecycleState.PENDING_RESTART
            },
            lastError = null,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        stateRepository.write(enabling)
        if (manifest.activationMode != PluginActivationMode.HOT) return enabling
        return try {
            mountLocked(pluginId, version)
            requireState(pluginId)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val failed = enabling.copy(
                enabled = false,
                lastState = PluginLifecycleState.FAILED,
                lastError = error.message,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            stateRepository.write(failed)
            throw error
        }
    }

    private suspend fun disableLocked(pluginId: String): PluginPersistentState {
        ensureNoEnabledDependents(pluginId)
        val current = requireState(pluginId)
        requireCleanRuntimeStop(pluginId, unmountLocked(pluginId))
        val disabled = current.copy(
            enabled = false,
            lastState = PluginLifecycleState.DISABLED,
            lastError = null,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        stateRepository.write(disabled)
        return disabled
    }
    private suspend fun activateVersionLocked(
        pluginId: String,
        version: String
    ): PluginPersistentState {
        val current = requireState(pluginId)
        if (current.activeVersion == version) return current
        val candidateDir = store.versionDir(pluginId, version)
        if (!candidateDir.isDirectory) {
            throw PluginInstallException("VERSION_NOT_INSTALLED", "$pluginId $version is not installed")
        }
        if (version in current.quarantinedVersions) {
            throw PluginInstallException("VERSION_QUARANTINED", "$pluginId $version is quarantined")
        }
        ensureVersionSatisfiesDependents(pluginId, version)
        val candidateManifest = stateRepository.readInstalledManifest(pluginId, version)
        val previousVersion = current.activeVersion
        if (!current.enabled) {
            return writeState(
                current.copy(
                    activeVersion = version,
                    previousVersion = previousVersion,
                    lastState = PluginLifecycleState.INSTALLED,
                    lastError = null
                )
            )
        }
        if (candidateManifest.activationMode != PluginActivationMode.HOT) {
            return writeState(
                current.copy(
                    activeVersion = version,
                    previousVersion = previousVersion,
                    lastState = PluginLifecycleState.PENDING_RESTART,
                    lastError = null
                )
            )
        }
        requireCleanRuntimeStop(pluginId, unmountLocked(pluginId))
        writeState(
            current.copy(
                activeVersion = version,
                previousVersion = previousVersion,
                lastState = PluginLifecycleState.MOUNTING,
                lastError = null
            )
        )
        return try {
            mountLocked(pluginId, version)
            requireState(pluginId)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val failureMessage = "Activation of $pluginId $version failed: ${error.message}"
            val quarantined = current.quarantinedVersions + version
            store.quarantineVersion(pluginId, version)
            stateRepository.write(
                current.copy(
                    activeVersion = previousVersion,
                    previousVersion = null,
                    enabled = current.enabled,
                    lastState = PluginLifecycleState.FAILED,
                    lastError = failureMessage,
                    quarantinedVersions = quarantined,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
            var restoreFailure: Throwable? = null
            if (previousVersion != null && current.enabled) {
                try {
                    mountLocked(pluginId, previousVersion)
                } catch (restoreError: Throwable) {
                    if (restoreError is CancellationException) throw restoreError
                    restoreFailure = restoreError
                    AppLogger.e(TAG, "Failed to restore $pluginId $previousVersion", restoreError)
                }
            }
            val latest = requireState(pluginId)
            stateRepository.write(
                latest.copy(
                    lastState = if (restoreFailure == null && previousVersion != null) PluginLifecycleState.ACTIVE else PluginLifecycleState.FAILED,
                    lastError = if (restoreFailure == null && previousVersion != null) {
                        "$failureMessage; previous version restored"
                    } else {
                        "$failureMessage; restore failed: ${restoreFailure?.message ?: "no previous version"}"
                    },
                    quarantinedVersions = quarantined,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
            throw PluginInstallException(
                "ACTIVATION_FAILED_ROLLED_BACK",
                failureMessage,
                error
            )
        }
    }
    private suspend fun restoreEnabledPluginsLocked() {
        val pending = store.listPluginIds()
            .filter { stateRepository.read(it)?.enabled == true }
            .toMutableSet()
        var madeProgress: Boolean
        do {
            madeProgress = false
            for (pluginId in pending.toList().sorted()) {
                val state = stateRepository.read(pluginId)
                if (state == null) {
                    pending.remove(pluginId)
                    madeProgress = true
                    continue
                }
                val version = state.activeVersion
                if (version == null) {
                    stateRepository.write(
                        state.copy(
                            lastState = PluginLifecycleState.FAILED,
                            lastError = "No active version is selected",
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                    pending.remove(pluginId)
                    madeProgress = true
                    continue
                }
                val manifest = stateRepository.readInstalledManifest(pluginId, version)
                if (manifest.activationMode != PluginActivationMode.HOT) {
                    stateRepository.write(
                        state.copy(
                            lastState = PluginLifecycleState.PENDING_RESTART,
                            lastError = null,
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                    pending.remove(pluginId)
                    madeProgress = true
                    continue
                }
                val dependencyFailure = dependencyError(pluginId, version)
                if (dependencyFailure != null) continue
                try {
                    mountLocked(pluginId, version)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    AppLogger.e(TAG, "Plugin restore failed: $pluginId $version", error)
                }
                pending.remove(pluginId)
                madeProgress = true
            }
        } while (madeProgress && pending.isNotEmpty())
        pending.sorted().forEach { pluginId ->
            val state = stateRepository.read(pluginId) ?: return@forEach
            val version = state.activeVersion
            val reason = version?.let { dependencyError(pluginId, it) }
                ?: "Plugin dependencies could not be resolved"
            stateRepository.write(
                state.copy(
                    lastState = PluginLifecycleState.FAILED,
                    lastError = reason,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun shutdownLocked() {
        val mountedPluginIds = activeMounts.keys.toList().sortedDescending()
        mountedPluginIds.forEach { pluginId ->
            val previousState = stateRepository.read(pluginId)
            val stopResult = unmountLocked(pluginId)
            if (previousState != null && (stopResult == null || stopResult.stoppedCleanly)) {
                stateRepository.write(
                    previousState.copy(
                        lastState =
                            if (previousState.enabled) {
                                PluginLifecycleState.INSTALLED
                            } else {
                                PluginLifecycleState.DISABLED
                            },
                        lastError = null,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private suspend fun mountLocked(
        pluginId: String,
        version: String
    ): ActivePluginMount {
        activeMounts[pluginId]?.let { current ->
            val currentState = stateRepository.read(pluginId)
            if (
                current.version == version &&
                    currentState?.enabled == true &&
                    currentState.lastState == PluginLifecycleState.ACTIVE
            ) {
                return current
            }
            requireCleanRuntimeStop(pluginId, unmountLocked(pluginId))
        }
        dependencyError(pluginId, version)?.let { reason ->
            throw PluginInstallException("DEPENDENCY_UNSATISFIED", reason)
        }
        val manifest = stateRepository.readInstalledManifest(pluginId, version)
        val adapter = runtimeAdapters.resolve(manifest.runtime.kind)
            ?: throw PluginInstallException(
                "RUNTIME_ADAPTER_MISSING",
                "No runtime adapter is registered for ${manifest.runtime.kind}"
            )
        val state = requireState(pluginId)
        stateRepository.write(
            state.copy(
                lastState = PluginLifecycleState.MOUNTING,
                lastError = null,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        val versionDir = store.versionDir(pluginId, version)
        val mountScope = PluginMountScope(manifest, contributions, extensionRouter, capabilityBinder)
        try {
            val dataDir = store.dataDir(pluginId).apply { mkdirs() }
            val cacheDir = store.cacheDir(pluginId).apply { mkdirs() }
            val payloadContext =
                pluginContextFactory.create(
                    manifest = manifest,
                    mountScope = mountScope,
                    dataDir = dataDir,
                    cacheDir = cacheDir
                )
            val hostedRuntime =
                runtimeHost.mount(
                    adapter = adapter,
                    context =
                        PluginRuntimeAdapterContext(
                            appContext = appContext,
                            manifest = manifest,
                            versionDir = versionDir,
                            contentDir = store.contentIn(versionDir),
                            payloadContext = payloadContext
                        ),
                    scope = mountScope
                )
            val mount = ActivePluginMount(version, hostedRuntime)
            activeMounts[pluginId] = mount
            val latest = requireState(pluginId)
            stateRepository.write(
                latest.copy(
                    enabled = true,
                    lastState = PluginLifecycleState.ACTIVE,
                    lastError = null,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
            return mount
        } catch (error: Throwable) {
            activeMounts.remove(pluginId)
            mountScope.revokeAll()
            if (error is CancellationException) throw error
            val latest = stateRepository.read(pluginId) ?: state
            stateRepository.write(
                latest.copy(
                    lastState = PluginLifecycleState.FAILED,
                    lastError = error.message,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
            throw error
        }
    }
    private suspend fun unmountLocked(pluginId: String): PluginRuntimeStopResult? {
        val mount = activeMounts[pluginId] ?: return null
        stateRepository.read(pluginId)?.let { state ->
            stateRepository.write(
                state.copy(
                    lastState = PluginLifecycleState.UNMOUNTING,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
        val result = runtimeHost.stop(mount.runtime)
        if (result.stoppedCleanly) {
            activeMounts.remove(pluginId, mount)
        } else {
            stateRepository.read(pluginId)?.let { state ->
                stateRepository.write(
                    state.copy(
                        enabled = false,
                        lastState = PluginLifecycleState.FAILED,
                        lastError = listOfNotNull(result.errorCode, result.message).joinToString(": "),
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }
            AppLogger.e(TAG, "Plugin runtime stop was not clean: $pluginId ${result.errorCode}: ${result.message}")
        }
        return result
    }

    private fun requireCleanRuntimeStop(pluginId: String, result: PluginRuntimeStopResult?) {
        if (result == null || result.stoppedCleanly) return
        throw PluginInstallException(
            result.errorCode ?: "RUNTIME_STOP_FAILED",
            result.message ?: "Plugin runtime did not stop cleanly: $pluginId"
        )
    }

    private fun dependencyError(pluginId: String, version: String): String? {
        val manifest = stateRepository.readInstalledManifest(pluginId, version)
        for (dependency in manifest.dependencies.plugins) {
            val state = stateRepository.read(dependency.pluginId)
                ?: return "Required plugin is not installed: ${dependency.pluginId}"
            val activeVersion = state.activeVersion
                ?: return "Required plugin has no active version: ${dependency.pluginId}"
            if (!state.enabled || activeMounts[dependency.pluginId]?.version != activeVersion) {
                return "Required plugin is not active: ${dependency.pluginId}"
            }
            dependency.minVersion?.let { required ->
                val actualSemVer = SemanticVersion.parse(activeVersion)
                val requiredSemVer = SemanticVersion.parse(required)
                if (actualSemVer == null || requiredSemVer == null || actualSemVer < requiredSemVer) {
                    return "${dependency.pluginId} requires version >= $required, active=$activeVersion"
                }
            }
        }
        for (dependency in manifest.dependencies.services) {
            val service = contributions.find(PluginContributionKind.SERVICE, dependency.serviceId)
                ?: return "Required service is not available: ${dependency.serviceId}"
            dependency.minApi?.let { requiredApi ->
                val actualApi = service.apiVersion ?: 0
                if (actualApi < requiredApi) {
                    return "${dependency.serviceId} requires API >= $requiredApi, active=$actualApi"
                }
            }
        }
        return null
    }

    private fun ensureNoEnabledDependents(pluginId: String) {
        val dependents = enabledDependents(pluginId)
        if (dependents.isNotEmpty()) {
            throw PluginInstallException(
                "PLUGIN_HAS_DEPENDENTS",
                "$pluginId is required by enabled plugins: ${dependents.joinToString()}"
            )
        }
    }

    private fun ensureVersionSatisfiesDependents(pluginId: String, version: String) {
        val candidate = SemanticVersion.parse(version)
            ?: throw PluginInstallException("VERSION_INVALID", "Invalid plugin version: $version")
        val incompatible = enabledDependents(pluginId).filter { dependentId ->
            val state = stateRepository.read(dependentId) ?: return@filter false
            val active = state.activeVersion ?: return@filter false
            val manifest = stateRepository.readInstalledManifest(dependentId, active)
            manifest.dependencies.plugins.any { dependency ->
                dependency.pluginId == pluginId && dependency.minVersion?.let { min ->
                    val required = SemanticVersion.parse(min)
                    required != null && candidate < required
                } == true
            }
        }
        if (incompatible.isNotEmpty()) {
            throw PluginInstallException(
                "DEPENDENT_VERSION_CONFLICT",
                "$pluginId $version does not satisfy enabled dependents: ${incompatible.joinToString()}"
            )
        }
    }
    private fun enabledDependents(pluginId: String): List<String> =
        store.listPluginIds().filter { candidateId ->
            if (candidateId == pluginId) return@filter false
            val state = stateRepository.read(candidateId) ?: return@filter false
            if (!state.enabled) return@filter false
            val active = state.activeVersion ?: return@filter false
            runCatching { stateRepository.readInstalledManifest(candidateId, active) }
                .getOrNull()
                ?.dependencies
                ?.plugins
                ?.any { it.pluginId == pluginId } == true
        }.sorted()

    private fun defaultState(pluginId: String, version: String): PluginPersistentState =
        PluginPersistentState(
            pluginId = pluginId,
            activeVersion = version,
            previousVersion = null,
            enabled = false,
            lastState = PluginLifecycleState.INSTALLED,
            lastError = null,
            quarantinedVersions = emptySet(),
            updatedAtEpochMs = System.currentTimeMillis()
        )

    private fun requireState(pluginId: String): PluginPersistentState =
        stateRepository.read(pluginId)
            ?: throw PluginInstallException("PLUGIN_NOT_INSTALLED", "Plugin is not installed: $pluginId")

    private fun writeState(state: PluginPersistentState): PluginPersistentState {
        val updated = state.copy(updatedAtEpochMs = System.currentTimeMillis())
        stateRepository.write(updated)
        return updated
    }

    private fun snapshotLocked(pluginId: String): PluginSnapshot {
        val state = stateRepository.read(pluginId)
        val manifest = state?.activeVersion?.let { version ->
            runCatching { stateRepository.readInstalledManifest(pluginId, version) }.getOrNull()
        }
        return PluginSnapshot(
            pluginId = pluginId,
            versions = store.listVersions(pluginId),
            persistentState = state,
            activeManifest = manifest,
            mountedVersion = activeMounts[pluginId]?.version,
            contributions = contributions.listByOwner(pluginId)
        )
    }
    private suspend fun <T> locked(block: suspend () -> T): T {
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        private const val TAG = "PluginCenter"
    }
}
