package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.core.tools.system.resident.ResidentPermissionHandoff
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class PluginRuntimeTimeouts(
    val mountTimeoutMs: Long = 10_000L,
    val stopTimeoutMs: Long = 5_000L
) {
    init {
        require(mountTimeoutMs > 0) { "Plugin runtime mount timeout must be positive" }
        require(stopTimeoutMs > 0) { "Plugin runtime stop timeout must be positive" }
    }
}

internal enum class PluginRuntimeStopOutcome {
    STOPPED,
    FAILED,
    TIMEOUT
}

internal data class PluginRuntimeStopResult(
    val outcome: PluginRuntimeStopOutcome,
    val errorCode: String? = null,
    val message: String? = null
) {
    val stoppedCleanly: Boolean get() = outcome == PluginRuntimeStopOutcome.STOPPED
}

/** Kernel-only adapter context. Never hand this object to an external plugin payload. */
internal enum class PluginRuntimeRole {
    /** Transitional compatibility mode: the Android Host still owns business and UI. */
    LEGACY_HOST,
    /** Resident Core role: owns plugin business lifecycle and never owns Android UI objects. */
    BUSINESS,
    /** Android Host shell role: owns UI proxies only and must not mount plugin business runtimes. */
    UI_PROXY
}

internal data class PluginRuntimeAdapterContext(
    val runtimeRole: PluginRuntimeRole,
    val appContext: Context,
    val manifest: PluginManifest,
    val versionDir: File,
    val contentDir: File,
    val dataDir: File,
    val cacheDir: File,
    val installMetadata: PluginInstallMetadata,
    val payloadContext: PluginContext
)

internal interface PluginRuntimeHandle {
    suspend fun stop()
    suspend fun stopForResidentHandoff(handoff: ResidentPermissionHandoff) = stop()
}

/**
 * Trusted kernel SPI for constrained runtimes such as Declarative, ToolPkg and WASM.
 * Adapters may use Android Context, but must expose only payloadContext to plugin payloads.
 * Arbitrary in-process Dex/Jar loading is not a supported general plugin runtime boundary.
 * A finite allowlist of privileged AI Limbs plugins may use the hardened android_inprocess adapter.
 * Adapter mount/stop implementations must cooperate with coroutine cancellation and must not leak
 * runtime work outside the handle owned by this host.
 */
internal interface PluginRuntimeAdapter {
    val kind: String
    suspend fun mount(context: PluginRuntimeAdapterContext): PluginRuntimeHandle
}

internal data class HostedPluginRuntime(
    val kind: String,
    val handle: PluginRuntimeHandle,
    val scope: PluginMountScope
)

internal class PluginRuntimeHost(
    private val runtimeRole: PluginRuntimeRole = PluginRuntimeRole.LEGACY_HOST,
    private val timeouts: PluginRuntimeTimeouts = PluginRuntimeTimeouts()
) {
    suspend fun mount(
        adapter: PluginRuntimeAdapter,
        context: PluginRuntimeAdapterContext,
        scope: PluginMountScope
    ): HostedPluginRuntime {
        check(runtimeRole != PluginRuntimeRole.UI_PROXY) {
            "UI_PROXY must not mount plugin business runtimes"
        }
        check(context.runtimeRole == runtimeRole) {
            "Runtime role mismatch: host=$runtimeRole context=${context.runtimeRole}"
        }
        return mount(adapter.kind, scope) { adapter.mount(context) }
    }

    internal suspend fun mount(
        kind: String,
        scope: PluginMountScope,
        operation: suspend () -> PluginRuntimeHandle
    ): HostedPluginRuntime {
        check(runtimeRole != PluginRuntimeRole.UI_PROXY) {
            "UI_PROXY must not mount plugin business runtimes"
        }
        var handle: PluginRuntimeHandle? = null
        try {
            handle = withTimeout(timeouts.mountTimeoutMs) { operation() }
            scope.seal()
            return HostedPluginRuntime(kind = kind, handle = handle, scope = scope)
        } catch (error: TimeoutCancellationException) {
            val cleanupFailure = cleanupFailedMount(handle, scope)
            val failure = PluginInstallException(
                if (cleanupFailure == null) "RUNTIME_MOUNT_TIMEOUT" else "RUNTIME_MOUNT_CLEANUP_FAILED",
                if (cleanupFailure == null) {
                    "Runtime '$kind' did not mount within ${timeouts.mountTimeoutMs}ms"
                } else {
                    "Runtime '$kind' timed out and its partial mount did not revoke cleanly"
                },
                error
            )
            cleanupFailure?.let(failure::addSuppressed)
            throw failure
        } catch (error: CancellationException) {
            cleanupFailedMount(handle, scope)?.let(error::addSuppressed)
            throw error
        } catch (error: Throwable) {
            val cleanupFailure = cleanupFailedMount(handle, scope)
            if (cleanupFailure != null) {
                throw PluginInstallException(
                    "RUNTIME_MOUNT_CLEANUP_FAILED",
                    "Runtime '$kind' mount failed and its partial resources did not revoke cleanly",
                    error
                ).also { it.addSuppressed(cleanupFailure) }
            }
            if (error is PluginInstallException) throw error
            throw PluginInstallException(
                "RUNTIME_MOUNT_FAILED",
                "Runtime '$kind' mount failed: ${error.message ?: error::class.java.simpleName}",
                error
            )
        }
    }

    suspend fun stop(runtime: HostedPluginRuntime, handoff: ResidentPermissionHandoff? = null): PluginRuntimeStopResult {
        runtime.scope.revokeAll()
        return try {
            withTimeout(timeouts.stopTimeoutMs) {
                if (handoff == null) runtime.handle.stop() else runtime.handle.stopForResidentHandoff(handoff)
            }
            runtime.scope.requireCleanRevocation()
            PluginRuntimeStopResult(PluginRuntimeStopOutcome.STOPPED)
        } catch (error: TimeoutCancellationException) {
            PluginRuntimeStopResult(
                outcome = PluginRuntimeStopOutcome.TIMEOUT,
                errorCode = "RUNTIME_STOP_TIMEOUT",
                message = "Runtime '${runtime.kind}' did not stop within ${timeouts.stopTimeoutMs}ms"
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PluginRuntimeStopResult(
                outcome = PluginRuntimeStopOutcome.FAILED,
                errorCode = "RUNTIME_STOP_FAILED",
                message = "Runtime '${runtime.kind}' stop failed: ${error.message ?: error::class.java.simpleName}"
            )
        }
    }

    private suspend fun cleanupFailedMount(
        handle: PluginRuntimeHandle?,
        scope: PluginMountScope
    ): Throwable? = withContext(NonCancellable) {
        val failures = mutableListOf<Throwable>()
        scope.revokeAll()
        runCatching { scope.requireCleanRevocation() }.exceptionOrNull()?.let(failures::add)
        if (handle != null) {
            runCatching { withTimeout(timeouts.stopTimeoutMs) { handle.stop() } }
                .exceptionOrNull()?.let(failures::add)
        }
        if (failures.isEmpty()) null else IllegalStateException(
            "Failed mount left ${failures.size} runtime resource cleanup error(s)"
        ).also { failure -> failures.forEach(failure::addSuppressed) }
    }
}
