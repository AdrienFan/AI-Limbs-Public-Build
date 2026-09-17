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
    val installMetadata: PluginInstallMetadata?,
    val usage: PluginUsageStats,
    val backup: PluginBackupSnapshot?,
    val mountedVersion: String?,
    val contributions: List<PluginContributionRecord>
)
private data class ActivePluginMount(
    val version: String,
    val runtime: HostedPluginRuntime
)

internal data class PluginActiveAuthorization(
    val pluginId: String,
    val version: String,
    val roles: Set<String>,
    val grantedScopes: Set<String>
)

internal data class PluginWorkerServiceAuthorization(
    val caller: PluginActiveAuthorization,
    val serviceId: String,
    val requiredApi: Int
)

private data class PluginWorkerAuthorizationSnapshot(
    val authorization: PluginActiveAuthorization,
    val serviceMinApis: Map<String, Int>
)

internal class PluginManager(
    private val appContext: Context,
    private val runtimeRole: PluginRuntimeRole,
    val store: PluginStore,
    private val trustVerifier: PluginTrustVerifier,
    val runtimeAdapters: PluginRuntimeAdapterRegistry,
    val contributions: PluginContributionRegistry,
    private val extensionRouter: ExtensionRouter,
    private val capabilityBinder: PluginCapabilityBinder,
    private val surfacePolicy: HostSurfacePolicy,
    private val usageStore: PluginUsageStore,
    private val inactivityPolicy: PluginInactivityPolicyStore,
    private val backupStore: PluginBackupStore,
    private val backupPolicy: PluginBackupPolicyStore,
    private val runtimeHost: PluginRuntimeHost,
    private val pluginContextFactory: PluginContextFactory,
    private val identityRegistry: OfficialPluginIdentityRegistry,
    private val packageVerifier: PluginPackageVerifier
) {
    private val stateRepository = PluginStateRepository(store)
    private val activeMounts = ConcurrentHashMap<String, ActivePluginMount>()
    private val workerAuthorizations = ConcurrentHashMap<String, PluginWorkerAuthorizationSnapshot>()
    private val mutex = Mutex()

    fun initialize() {
        store.initialize()
        backupStore.initialize()
    }

    suspend fun install(
        sourcePackage: File,
        options: PluginInstallOptions = PluginInstallOptions()
    ): PluginInstallResult = locked {
        val result = installLocked(sourcePackage, options)
        autoBackupIfEligibleLocked(result.pluginId)
        result
    }

    suspend fun enable(pluginId: String): PluginPersistentState = locked {
        val state = enableLocked(pluginId)
        autoBackupIfEligibleLocked(pluginId)
        state
    }
    suspend fun disable(
        pluginId: String,
        adminAuthorized: Boolean = false
    ): PluginPersistentState = locked {
        val state = requireState(pluginId)
        val version = state.activeVersion
        if (
            version != null &&
            isTrustedOfficialSystemPlugin(pluginId, version) &&
            !adminAuthorized
        ) {
            throw PluginInstallException(
                "ADMIN_AUTH_REQUIRED",
                "Disabling a system plugin requires administrator authorization"
            )
        }
        disableLocked(pluginId)
    }

    suspend fun activateVersion(
        pluginId: String,
        version: String
    ): PluginPersistentState = locked {
        val state = activateVersionLocked(pluginId, version)
        autoBackupIfEligibleLocked(pluginId)
        state
    }

    suspend fun rollback(pluginId: String): PluginPersistentState = locked {
        val state = requireState(pluginId)
        val target = state.previousVersion
            ?: throw PluginInstallException("ROLLBACK_UNAVAILABLE", "No previous version is available for $pluginId")
        activateVersionLocked(pluginId, target)
    }

    suspend fun uninstall(
        pluginId: String,
        removeData: Boolean = false,
        adminAuthorized: Boolean = false
    ) = locked {
        val state = requireState(pluginId)
        val version = state.activeVersion
        if (
            version != null &&
            isTrustedOfficialSystemPlugin(pluginId, version) &&
            !adminAuthorized
        ) {
            throw PluginInstallException(
                "ADMIN_AUTH_REQUIRED",
                "Uninstalling a system plugin requires administrator authorization"
            )
        }
        ensureNoEnabledDependents(pluginId)
        requireCleanRuntimeStop(pluginId, unmountLocked(pluginId))
        store.deletePlugin(pluginId, removeData)
    }

    suspend fun restoreEnabledPlugins() = locked {
        restoreEnabledPluginsLocked()
    }

    internal suspend fun restoreEnabledPlugin(pluginId: String): Boolean = locked {
        val state = stateRepository.read(pluginId) ?: return@locked false
        if (!state.enabled) return@locked false
        val version = state.activeVersion
            ?: throw PluginInstallException(
                "PLUGIN_ACTIVE_VERSION_MISSING",
                "Enabled plugin has no active version: $pluginId"
            )
        val manifest = stateRepository.readInstalledManifest(pluginId, version)
        check(manifest.activationMode == PluginActivationMode.HOT) {
            "Resident targeted restore requires HOT activation: $pluginId"
        }
        mountLocked(pluginId, version)
        true
    }

    suspend fun reconcileHostSurfacePolicy() = locked {
        reconcileHostSurfacePolicyLocked()
    }

    suspend fun reconcileInactivityPolicy() = locked {
        reconcileInactivityPolicyLocked()
    }

    suspend fun backup(pluginId: String): PluginBackupSnapshot = locked {
        backupLocked(pluginId)
    }

    suspend fun restoreBackup(pluginId: String): PluginPersistentState = locked {
        restoreBackupLocked(pluginId)
    }

    suspend fun deleteBackup(pluginId: String) = locked {
        backupStore.delete(pluginId)
    }

    suspend fun backupSnapshots(): List<PluginBackupSnapshot> = locked {
        backupStore.snapshots().map { backup ->
            val state = stateRepository.read(backup.pluginId)
            backup.copy(
                installed = state != null,
                installedVersion = state?.activeVersion
            )
        }
    }

    suspend fun reconcileBackupPolicy() = locked {
        reconcileBackupPolicyLocked()
    }

    suspend fun shutdown(handoff: com.ai.assistance.operit.core.tools.system.resident.ResidentPermissionHandoff? = null) = locked {
        shutdownLocked(handoff)
    }

    suspend fun snapshots(): List<PluginSnapshot> = locked {
        store.listPluginIds().map(::snapshotLocked)
    }

    suspend fun snapshot(pluginId: String): PluginSnapshot = locked {
        snapshotLocked(pluginId)
    }

    internal suspend fun activeAuthorization(pluginId: String): PluginActiveAuthorization = locked {
        val state = requireState(pluginId)
        val version = state.activeVersion ?: throw PluginInstallException(
            "PLUGIN_ACTIVE_VERSION_MISSING",
            "Plugin has no active version: $pluginId"
        )
        val mount = activeMounts[pluginId]
        if (!state.enabled || state.lastState != PluginLifecycleState.ACTIVE || mount?.version != version) {
            throw PluginInstallException(
                "PLUGIN_NOT_ACTIVE",
                "Plugin is not mounted as its active version: $pluginId"
            )
        }
        val manifest = stateRepository.readInstalledManifest(pluginId, version)
        val metadata = stateRepository.readInstallMetadata(pluginId, version)
            ?: throw PluginInstallException(
                "VERSION_STORE_CORRUPT",
                "Active version has no install metadata: $pluginId $version"
            )
        if (metadata.grantedScopes != manifest.permissions.requestedScopes) {
            throw PluginInstallException(
                "PLUGIN_SCOPE_STATE_INVALID",
                "Stored scope approval does not match the active manifest"
            )
        }
        PluginActiveAuthorization(
            pluginId = pluginId,
            version = version,
            roles = manifest.roles.toSet(),
            grantedScopes = metadata.grantedScopes.toSet()
        )
    }

    internal suspend fun workerAuthorization(pluginId: String, version: String): PluginActiveAuthorization {
        val snapshot = workerAuthorizations[pluginId]
            ?: throw PluginInstallException("PLUGIN_WORKER_NOT_AUTHORIZED", "Worker has no active Core authorization: $pluginId")
        val authorization = snapshot.authorization
        if (authorization.version != version) {
            throw PluginInstallException("PLUGIN_WORKER_NOT_AUTHORIZED", "Worker version is not authorized: $pluginId $version")
        }
        return authorization
    }

    internal suspend fun workerServiceAuthorization(
        pluginId: String,
        version: String,
        serviceId: String,
        requestedMinApi: Int?
    ): PluginWorkerServiceAuthorization {
        val snapshot = workerAuthorizations[pluginId]
            ?: throw PluginInstallException("PLUGIN_WORKER_NOT_AUTHORIZED", "Worker has no active Core authorization: $pluginId")
        val authorization = snapshot.authorization
        if (authorization.version != version) {
            throw PluginInstallException("PLUGIN_WORKER_NOT_AUTHORIZED", "Worker version is not authorized: $pluginId $version")
        }
        val declaredMinApi = snapshot.serviceMinApis[serviceId]
            ?: throw PluginInstallException("SERVICE_ACCESS_NOT_DECLARED", "$pluginId did not declare service dependency: $serviceId")
        return PluginWorkerServiceAuthorization(
            authorization,
            serviceId,
            maxOf(requestedMinApi ?: 0, declaredMinApi)
        )
    }

    private fun publishWorkerAuthorization(manifest: PluginManifest, metadata: PluginInstallMetadata) {
        if (runtimeRole != PluginRuntimeRole.BUSINESS ||
            manifest.runtime.kind != OfficialPluginIdentityRegistry.RUNTIME_ANDROID_INPROCESS
        ) return
        val authorization = PluginActiveAuthorization(
            pluginId = manifest.pluginId,
            version = manifest.version,
            roles = manifest.roles.toSet(),
            grantedScopes = metadata.grantedScopes.toSet()
        )
        val serviceMinApis = manifest.dependencies.services.associate {
            it.serviceId to (it.minApi ?: 0)
        }
        workerAuthorizations[manifest.pluginId] =
            PluginWorkerAuthorizationSnapshot(authorization, serviceMinApis)
    }

    private fun revokeWorkerAuthorization(pluginId: String, version: String? = null) {
        if (version == null) {
            workerAuthorizations.remove(pluginId)
            return
        }
        workerAuthorizations.computeIfPresent(pluginId) { _, snapshot ->
            if (snapshot.authorization.version == version) null else snapshot
        }
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
            if (verified.manifest.runtime.kind == OfficialPluginIdentityRegistry.RUNTIME_ANDROID_INPROCESS) {
                if (!identityRegistry.isApproved(verified.manifest)) {
                    throw PluginInstallException(
                        "INPROCESS_IDENTITY_NOT_APPROVED",
                        "android_inprocess identity has not been approved by Plugin Center"
                    )
                }
                if (!trust.isTrusted || trust.signerId != OfficialPluginIdentityRegistry.OFFICIAL_SIGNER_ID) {
                    throw PluginInstallException(
                        "INPROCESS_OFFICIAL_SIGNATURE_REQUIRED",
                        "android_inprocess requires the official AI Limbs plugin signer"
                    )
                }
            }
            if (!trust.isTrusted && !options.allowUntrustedForDevelopment) {
                throw PluginInstallException(
                    "PLUGIN_UNTRUSTED",
                    trust.reason ?: "Plugin publisher is not trusted"
                )
            }
            val requestedScopes = verified.manifest.permissions.requestedScopes
            AiLimbsHostPrimitiveCatalog.requireInstallableScopes(requestedScopes)
            if (options.approvedScopes != requestedScopes) {
                throw PluginInstallException(
                    "PLUGIN_SCOPE_APPROVAL_REQUIRED",
                    "Approved scopes must exactly match requested scopes: $requestedScopes"
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
            if (metadata.grantedScopes != manifest.permissions.requestedScopes) {
                throw PluginInstallException(
                    "VERSION_SCOPE_CONFLICT",
                    "Installed version has different scope approvals; install a new version"
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
                sourceFileName = sourcePackage.name,
                grantedScopes = manifest.permissions.requestedScopes
            )
        )
        store.discardManagedPackage(transaction)
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
        if (!state.enabled) usageStore.markEnabled(pluginId)
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
        val surfaceBlock = surfacePolicy.blockingReason(manifest)
        if (surfaceBlock != null) {
            return writeState(
                state.copy(
                    enabled = true,
                    lastState = PluginLifecycleState.BLOCKED,
                    lastError = surfaceBlock
                )
            )
        }
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
        val surfaceBlock = surfacePolicy.blockingReason(candidateManifest)
        if (surfaceBlock != null) {
            requireCleanRuntimeStop(pluginId, unmountLocked(pluginId))
            return writeState(
                current.copy(
                    activeVersion = version,
                    previousVersion = previousVersion,
                    enabled = true,
                    lastState = PluginLifecycleState.BLOCKED,
                    lastError = surfaceBlock
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
                val manifest = try {
                    stateRepository.readInstalledManifest(pluginId, version)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    val message = error.message ?: error::class.java.simpleName
                    AppLogger.e(TAG, "Plugin restore manifest failed: $pluginId $version", error)
                    stateRepository.write(
                        state.copy(
                            lastState = PluginLifecycleState.FAILED,
                            lastError = "Installed plugin manifest could not be read: $message",
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                    pending.remove(pluginId)
                    madeProgress = true
                    continue
                }
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
                val surfaceBlock = surfacePolicy.blockingReason(manifest)
                if (surfaceBlock != null) {
                    stateRepository.write(
                        state.copy(
                            enabled = true,
                            lastState = PluginLifecycleState.BLOCKED,
                            lastError = surfaceBlock,
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
                    if (error is PluginInstallException && error.code == "RUNTIME_MOUNT_CLEANUP_FAILED") {
                        AppLogger.e(TAG, "Plugin restore left unreleased runtime resources: $pluginId $version", error)
                        throw error
                    }
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
            val blockedByDependency = version?.let { blockedDependencyReason(pluginId, it) }
            stateRepository.write(
                state.copy(
                    lastState = if (blockedByDependency != null) PluginLifecycleState.BLOCKED else PluginLifecycleState.FAILED,
                    lastError = blockedByDependency ?: reason,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun reconcileHostSurfacePolicyLocked() {
        var changed: Boolean
        do {
            changed = false
            activeMounts.keys.toList().sorted().forEach { pluginId ->
                val state = stateRepository.read(pluginId) ?: return@forEach
                val version = state.activeVersion ?: return@forEach
                val manifest = runCatching { stateRepository.readInstalledManifest(pluginId, version) }.getOrNull()
                    ?: return@forEach
                val surfaceReason = surfacePolicy.blockingReason(manifest)
                val dependencyReason = if (surfaceReason == null) blockedDependencyReason(pluginId, version) else null
                val reason = surfaceReason ?: dependencyReason ?: return@forEach
                requireCleanRuntimeStop(pluginId, unmountLocked(pluginId))
                stateRepository.write(
                    state.copy(
                        enabled = true,
                        lastState = PluginLifecycleState.BLOCKED,
                        lastError = reason,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
                changed = true
            }
        } while (changed)
        restoreEnabledPluginsLocked()
    }

    private suspend fun reconcileInactivityPolicyLocked() {
        val policy = inactivityPolicy.snapshot()
        if (!policy.enabled) return
        val now = System.currentTimeMillis()
        store.listPluginIds().sorted().forEach { pluginId ->
            val state = stateRepository.read(pluginId) ?: return@forEach
            if (!state.enabled || state.lastState != PluginLifecycleState.ACTIVE) return@forEach
            val version = state.activeVersion ?: return@forEach
            if (isAutoDisableExempt(pluginId, version)) return@forEach
            val usage = usageStore.snapshot(pluginId)
            val baseline = maxOf(
                usage.lastUsedAtEpochMs ?: 0L,
                usageStore.enabledSince(pluginId) ?: 0L,
                policy.enabledAtEpochMs
            )
            if (baseline <= 0L || now - baseline < policy.thresholdMillis) return@forEach
            try {
                disableLocked(pluginId)
                AppLogger.i(TAG, "Auto-disabled inactive plugin: $pluginId")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                AppLogger.w(TAG, "Skipped auto-disable for $pluginId: ${error.message}")
            }
        }
    }

    private fun isTrustedOfficialSystemPlugin(pluginId: String, version: String): Boolean {
        val metadata = stateRepository.readInstallMetadata(pluginId, version) ?: return false
        val manifest = runCatching { stateRepository.readInstalledManifest(pluginId, version) }.getOrNull() ?: return false
        return identityRegistry.isTrusted(manifest, metadata)
    }

    private fun isAutoDisableExempt(pluginId: String, version: String): Boolean {
        if (isTrustedOfficialSystemPlugin(pluginId, version)) return true
        return extensionRouter.listBindingsByOwner(pluginId).any { binding ->
            binding.point == PluginExtensionPoints.UI_THEME
        }
    }

    private suspend fun shutdownLocked(handoff: com.ai.assistance.operit.core.tools.system.resident.ResidentPermissionHandoff?) {
        val failures = mutableListOf<Throwable>()
        val mountedPluginIds = activeMounts.keys.toList().sortedDescending()
        mountedPluginIds.forEach { pluginId ->
            val previousState = stateRepository.read(pluginId)
            val stopResult = unmountLocked(pluginId, handoff, ownerShutdown = true)
            if (stopResult != null && !stopResult.stoppedCleanly) {
                failures += PluginInstallException(
                    stopResult.errorCode ?: "RUNTIME_STOP_FAILED",
                    "Cannot retire runtime owner: $pluginId: ${stopResult.message}"
                )
            }
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
        if (failures.isNotEmpty()) {
            val failure = PluginInstallException(
                "RUNTIME_RETIREMENT_FAILED",
                "${failures.size} plugin runtime(s) did not stop; ownership remains with this process"
            )
            failures.forEach(failure::addSuppressed)
            throw failure
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
        surfacePolicy.requireManifestAllowed(manifest)
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
        val installMetadata = stateRepository.readInstallMetadata(pluginId, version)
            ?: throw PluginInstallException(
                "VERSION_STORE_CORRUPT",
                "Installed version exists without valid install metadata"
            )
        if (installMetadata.grantedScopes != manifest.permissions.requestedScopes) {
            throw PluginInstallException(
                "PLUGIN_SCOPE_STATE_INVALID",
                "Stored scope approval does not match the active manifest"
            )
        }
        publishWorkerAuthorization(manifest, installMetadata)
        val mountScope = PluginMountScope(manifest, contributions, extensionRouter, capabilityBinder, surfacePolicy)
        try {
            val dataDir = store.dataDir(pluginId).apply { mkdirs() }
            val cacheDir = store.cacheDir(pluginId).apply { mkdirs() }
            val payloadContext =
                pluginContextFactory.create(
                    manifest = manifest,
                    mountScope = mountScope,
                    dataDir = dataDir,
                    cacheDir = cacheDir,
                    grantedScopes = installMetadata.grantedScopes
                )
            val hostedRuntime =
                runtimeHost.mount(
                    adapter = adapter,
                    context =
                        PluginRuntimeAdapterContext(
                            runtimeRole = runtimeRole,
                            appContext = appContext,
                            manifest = manifest,
                            versionDir = versionDir,
                            contentDir = store.contentIn(versionDir),
                            dataDir = dataDir,
                            cacheDir = cacheDir,
                            installMetadata = installMetadata,
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
            revokeWorkerAuthorization(pluginId, version)
            mountScope.revokeAll()
            val revokeFailure = runCatching { mountScope.requireCleanRevocation() }.exceptionOrNull()
            if (error is CancellationException) {
                revokeFailure?.let(error::addSuppressed)
                throw error
            }
            val reportedError = when {
                revokeFailure == null -> error
                error is PluginInstallException && error.code == "RUNTIME_MOUNT_CLEANUP_FAILED" ->
                    error.also { it.addSuppressed(revokeFailure) }
                else -> PluginInstallException(
                    "RUNTIME_MOUNT_CLEANUP_FAILED",
                    "Plugin $pluginId failed to mount and owned resources did not revoke cleanly",
                    error
                ).also { it.addSuppressed(revokeFailure) }
            }
            val latest = stateRepository.read(pluginId) ?: state
            stateRepository.write(
                latest.copy(
                    lastState = PluginLifecycleState.FAILED,
                    lastError = reportedError.message,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
            throw reportedError
        }
    }
    private suspend fun unmountLocked(
        pluginId: String,
        handoff: com.ai.assistance.operit.core.tools.system.resident.ResidentPermissionHandoff? = null,
        ownerShutdown: Boolean = false
    ): PluginRuntimeStopResult? {
        val mount = activeMounts[pluginId] ?: return null
        stateRepository.read(pluginId)?.let { state ->
            stateRepository.write(
                state.copy(
                    lastState = PluginLifecycleState.UNMOUNTING,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
        val result = runtimeHost.stop(mount.runtime, handoff, ownerShutdown)
        if (result.stoppedCleanly) {
            activeMounts.remove(pluginId, mount)
            revokeWorkerAuthorization(pluginId, mount.version)
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

    private fun blockedDependencyReason(pluginId: String, version: String): String? {
        val manifest = stateRepository.readInstalledManifest(pluginId, version)
        for (dependency in manifest.dependencies.plugins) {
            val dependencyState = stateRepository.read(dependency.pluginId) ?: continue
            if (dependencyState.enabled && dependencyState.lastState == PluginLifecycleState.BLOCKED) {
                return "依赖插件被宿主策略阻断：${dependency.pluginId}"
            }
        }
        return null
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
            if (runtimeRole == PluginRuntimeRole.BUSINESS &&
                dependency.serviceId in BUSINESS_OPTIONAL_PRESENTATION_SERVICES) {
                // These services are implemented by the Host UI system plugin. A BUSINESS parent
                // may declare them for its presentation half while treating them as optional at
                // runtime (System Environment Center does exactly that). They must not prevent its
                // non-UI service/child point from mounting in Resident Core.
                continue
            }
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

    private fun backupLocked(pluginId: String): PluginBackupSnapshot {
        val state = requireState(pluginId)
        val version = state.activeVersion
            ?: throw PluginInstallException("ACTIVE_VERSION_MISSING", "No active version is selected for $pluginId")
        return backupStore.backup(pluginId, version, state.enabled)
    }

    private suspend fun restoreBackupLocked(pluginId: String): PluginPersistentState {
        if (stateRepository.read(pluginId) != null || store.pluginDir(pluginId).exists()) {
            throw PluginInstallException("BACKUP_TARGET_ALREADY_INSTALLED", "Plugin is already installed: $pluginId")
        }
        val backup = backupStore.snapshot(pluginId)
            ?: throw PluginInstallException("BACKUP_NOT_FOUND", "No plugin backup exists for $pluginId")
        val actualDigest = PluginPackageVerifier.sha256(backup.packageFile)
        if (actualDigest != backup.packageSha256) {
            throw PluginInstallException("BACKUP_DIGEST_MISMATCH", "Plugin backup is corrupted: $pluginId")
        }
        installLocked(
            backup.packageFile,
            PluginInstallOptions(
                allowUntrustedForDevelopment = surfacePolicy.developerMode,
                enableAfterInstall = backup.wasEnabled,
                approvedScopes = backup.manifest.permissions.requestedScopes
            )
        )
        var state = requireState(pluginId)
        if (state.activeVersion != backup.version) {
            state = activateVersionLocked(pluginId, backup.version)
        }
        return state
    }

    private fun reconcileBackupPolicyLocked() {
        if (!backupPolicy.snapshot().enabled) return
        store.listPluginIds().forEach(::autoBackupIfEligibleLocked)
    }

    private fun autoBackupIfEligibleLocked(pluginId: String) {
        if (!backupPolicy.snapshot().enabled) return
        val state = stateRepository.read(pluginId) ?: return
        val version = state.activeVersion ?: return
        val highFrequency = usageStore.snapshot(pluginId).useCount >= PluginBackupPolicyStore.HIGH_FREQUENCY_USE_COUNT
        if (!isTrustedOfficialSystemPlugin(pluginId, version) && !highFrequency) return
        val existing = backupStore.snapshot(pluginId)
        if (existing?.version == version) return
        runCatching { backupStore.backup(pluginId, version, state.enabled) }
            .onFailure { AppLogger.w(TAG, "Automatic backup failed for $pluginId", it) }
    }

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
        val versions = store.listVersions(pluginId)
        val installMetadata = state?.activeVersion?.let { version ->
            stateRepository.readInstallMetadata(pluginId, version)
        }
        return PluginSnapshot(
            pluginId = pluginId,
            versions = versions,
            persistentState = state,
            activeManifest = manifest,
            installMetadata = installMetadata,
            usage = usageStore.snapshot(pluginId),
            backup = backupStore.snapshot(pluginId),
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
        private val BUSINESS_OPTIONAL_PRESENTATION_SERVICES = setOf(
            "system.plugin_center.ui_accessories"
        )
    }
}
