package com.ai.assistance.operit.plugins.center

import android.content.Context
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
internal data class PluginRuntimeAdapterContext(
    val appContext: Context,
    val manifest: PluginManifest,
    val versionDir: File,
    val contentDir: File,
    val dataDir: File,
    val cacheDir: File,
    val payloadContext: PluginContext
)

internal interface PluginRuntimeHandle {
    suspend fun stop()
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
    private val timeouts: PluginRuntimeTimeouts = PluginRuntimeTimeouts()
) {
    suspend fun mount(
        adapter: PluginRuntimeAdapter,
        context: PluginRuntimeAdapterContext,
        scope: PluginMountScope
    ): HostedPluginRuntime = mount(adapter.kind, scope) { adapter.mount(context) }

    internal suspend fun mount(
        kind: String,
        scope: PluginMountScope,
        operation: suspend () -> PluginRuntimeHandle
    ): HostedPluginRuntime {
        var handle: PluginRuntimeHandle? = null
        try {
            handle = withTimeout(timeouts.mountTimeoutMs) { operation() }
            scope.seal()
            return HostedPluginRuntime(kind = kind, handle = handle, scope = scope)
        } catch (error: TimeoutCancellationException) {
            cleanupFailedMount(handle, scope)
            throw PluginInstallException(
                "RUNTIME_MOUNT_TIMEOUT",
                "Runtime '$kind' did not mount within ${timeouts.mountTimeoutMs}ms",
                error
            )
        } catch (error: CancellationException) {
            cleanupFailedMount(handle, scope)
            throw error
        } catch (error: Throwable) {
            cleanupFailedMount(handle, scope)
            if (error is PluginInstallException) throw error
            throw PluginInstallException(
                "RUNTIME_MOUNT_FAILED",
                "Runtime '$kind' mount failed: ${error.message ?: error::class.java.simpleName}",
                error
            )
        }
    }

    suspend fun stop(runtime: HostedPluginRuntime): PluginRuntimeStopResult {
        runtime.scope.revokeAll()
        return try {
            withTimeout(timeouts.stopTimeoutMs) { runtime.handle.stop() }
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
    ) {
        scope.revokeAll()
        if (handle == null) return
        withContext(NonCancellable) {
            runCatching { withTimeout(timeouts.stopTimeoutMs) { handle.stop() } }
        }
    }
}
