package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsInteractionCycleRuntime
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.plugins.center.PluginRuntimeRole
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

internal enum class ResidentCoreLifecyclePhase {
    CREATED,
    INITIALIZED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}

internal enum class ResidentCoreBusinessPhase {
    DETACHED,
    WAITING_FOR_HOST_EXIT,
    ACQUIRING_OWNER,
    STARTING_KERNEL,
    CLAIMING_BACKEND,
    STARTING_BRIDGE,
    RUNNING,
    CANCELLED,
    STOPPED,
    FAILED
}

/**
 * Independent Resident Core runtime. The process skeleton can RUN without owning business.
 * Plugin Kernel takeover is a second state machine and is never inferred from process liveness.
 */
internal class ResidentCoreBusinessRuntime {
    private val lock = Any()
    private var phase = ResidentCoreLifecyclePhase.CREATED
    private var businessPhase = ResidentCoreBusinessPhase.DETACHED
    private var handler: Handler? = null
    private var initializedElapsedMs: Long? = null
    private var startedElapsedMs: Long? = null
    private var stoppedElapsedMs: Long? = null
    private var mainLooperThread: String? = null
    private var lastError: String? = null
    private var businessError: String? = null
    private var expectedHostPid: Int? = null
    private var pluginKernelStarted = false
    private var bridgeIngressPrepared = false
    private var bridgePluginMounted = false
    private var businessAttached = false
    @Volatile private var stopRequested = false

    fun initialize(context: Context) {
        synchronized(lock) {
            check(phase == ResidentCoreLifecyclePhase.CREATED) {
                "Resident Core runtime cannot initialize from $phase"
            }
            check(context.applicationContext === context) {
                "Resident Core requires its standalone application Context wrapper"
            }
            val mainLooper = checkNotNull(Looper.getMainLooper()) { "Resident Core main Looper is missing" }
            handler = Handler(mainLooper)
            initializedElapsedMs = SystemClock.elapsedRealtime()
            phase = ResidentCoreLifecyclePhase.INITIALIZED
        }
    }

    fun start(timeoutMs: Long = START_STOP_TIMEOUT_MS) {
        val ready = CountDownLatch(1)
        val mainHandler = synchronized(lock) {
            check(phase == ResidentCoreLifecyclePhase.INITIALIZED) {
                "Resident Core runtime cannot start from $phase"
            }
            phase = ResidentCoreLifecyclePhase.STARTING
            checkNotNull(handler)
        }
        check(mainHandler.post {
            try {
                synchronized(lock) {
                    check(phase == ResidentCoreLifecyclePhase.STARTING) {
                        "Resident Core startup barrier ran from $phase"
                    }
                    mainLooperThread = Thread.currentThread().name
                    startedElapsedMs = SystemClock.elapsedRealtime()
                    phase = ResidentCoreLifecyclePhase.RUNNING
                }
            } catch (error: Throwable) {
                fail(error)
            } finally {
                ready.countDown()
            }
        }) { "Resident Core main Looper rejected startup barrier" }
        check(ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "Resident Core main Looper did not execute startup barrier within ${timeoutMs}ms"
        }
        synchronized(lock) {
            check(phase == ResidentCoreLifecyclePhase.RUNNING) {
                "Resident Core startup failed: ${lastError ?: phase.name}"
            }
        }
    }

    fun armBusinessTakeover(context: Context, coreSession: String, hostPid: Int) {
        synchronized(lock) {
            check(phase == ResidentCoreLifecyclePhase.RUNNING) { "Core process is not running" }
            check(businessPhase == ResidentCoreBusinessPhase.DETACHED) {
                "Business takeover cannot arm from $businessPhase"
            }
            check(hostPid > 0 && hostPid != Process.myPid()) { "Invalid Host PID for business takeover" }
        }
        ResidentBusinessTakeoverFence.arm(context, coreSession, Process.myPid(), hostPid)
        synchronized(lock) {
            expectedHostPid = hostPid
            stopRequested = false
            businessError = null
            businessPhase = ResidentCoreBusinessPhase.WAITING_FOR_HOST_EXIT
        }
    }

    fun cancelBusinessTakeover(context: Context, coreSession: String, hostPid: Int) {
        synchronized(lock) {
            check(businessPhase == ResidentCoreBusinessPhase.WAITING_FOR_HOST_EXIT) {
                "Business takeover can only be cancelled before Host exit"
            }
            check(expectedHostPid == hostPid) { "Business takeover Host PID changed" }
            stopRequested = true
            // Keep cancellation state and fence removal atomic with respect to the activation
            // worker, otherwise it could observe stopRequested before CANCELLED and misreport
            // an intentional Host-side cancellation as a Core takeover failure.
            ResidentBusinessTakeoverFence.cancelByHost(context, coreSession, hostPid)
            businessPhase = ResidentCoreBusinessPhase.CANCELLED
        }
    }

    fun activateBusiness(
        context: Context,
        coreSession: String,
        backend: ResidentBackendBinding,
        hostPid: Int,
        onBusinessOwnerReady: () -> Unit = {},
        timeoutMs: Long = BUSINESS_TAKEOVER_TIMEOUT_MS
    ) {
        synchronized(lock) {
            check(businessPhase == ResidentCoreBusinessPhase.WAITING_FOR_HOST_EXIT && expectedHostPid == hostPid) {
                "Business activation was not armed for Host $hostPid"
            }
        }
        var lease: ResidentRuntimeLease? = null
        var leaseAdopted = false
        try {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (processExists(hostPid)) {
                check(!stopRequested) { "Business takeover cancelled while waiting for Host exit" }
                check(SystemClock.elapsedRealtime() < deadline) {
                    "Host PID $hostPid did not exit before Resident Core takeover timeout"
                }
                Thread.sleep(50L)
            }

            synchronized(lock) {
                check(!stopRequested) { "Business takeover cancelled after Host exit" }
                businessPhase = ResidentCoreBusinessPhase.ACQUIRING_OWNER
            }
            val ownerDir = File(context.filesDir, "ai_limbs/runtime_owner")
            while (lease == null) {
                check(!stopRequested) { "Business takeover cancelled before owner lease acquisition" }
                check(SystemClock.elapsedRealtime() < deadline) {
                    "Host exited but plugin_kernel lease was not released before timeout"
                }
                lease = ResidentRuntimeLease.tryAcquire(ownerDir, "plugin_kernel")
                if (lease == null) Thread.sleep(50L)
            }

            // Host is gone and the unique owner lease is held. Transfer gate/generation/receipt
            // authority before any Core policy object or Dispatcher can be created.
            val policyState = ResidentPolicyStateHandoff.consume(context, coreSession, hostPid)
            AiLimbsInteractionCycleRuntime.restoreFromResidentHandoff(context, policyState)

            synchronized(lock) { businessPhase = ResidentCoreBusinessPhase.STARTING_KERNEL }
            runOnMain {
                PluginPlatformKernel.initialize(
                    context = context,
                    role = PluginRuntimeRole.BUSINESS,
                    ownerLease = checkNotNull(lease)
                )
            }
            leaseAdopted = true
            runBlocking(Dispatchers.IO) { PluginPlatformKernel.startOwnerOnly() }
            val kernel = PluginPlatformKernel.lifecycleSnapshot()
            check(kernel.getString("runtime_role") == "business" &&
                kernel.getBoolean("started") && kernel.getBoolean("owner_lease_held") &&
                !kernel.getBoolean("business_runtime_restored") &&
                kernel.getInt("pid") == Process.myPid()) {
                "Resident Core Plugin Kernel did not become the unique owner-only BUSINESS runtime: $kernel"
            }
            synchronized(lock) {
                pluginKernelStarted = true
                businessPhase = ResidentCoreBusinessPhase.CLAIMING_BACKEND
            }

            backend.claimRuntimeOwnership()
            // The policy/Dispatcher plane is part of business ownership. Bring it up before Bridge
            // so a provider that becomes online immediately has a live Core-owned destination.
            onBusinessOwnerReady()
            synchronized(lock) { businessPhase = ResidentCoreBusinessPhase.STARTING_BRIDGE }
            val bridgeMounted = runBlocking(Dispatchers.IO) {
                PluginPlatformKernel.startResidentBridgeIngress()
            }
            val bridgeKernel = PluginPlatformKernel.lifecycleSnapshot()
            check(bridgeKernel.getBoolean("resident_bridge_ingress_prepared")) {
                "Resident Bridge ingress did not reach a prepared state: $bridgeKernel"
            }
            synchronized(lock) {
                bridgeIngressPrepared = true
                bridgePluginMounted = bridgeMounted
            }
            // Publish owned only after Core policy/Dispatcher and the configured Bridge transport
            // plane are ready. A disabled/uninstalled Bridge is explicit configuration, not fallback.
            ResidentBusinessTakeoverFence.markOwned(context, coreSession)
            synchronized(lock) {
                businessAttached = true
                businessPhase = ResidentCoreBusinessPhase.RUNNING
            }
        } catch (error: Throwable) {
            val cancelled = synchronized(lock) {
                stopRequested && (businessPhase == ResidentCoreBusinessPhase.CANCELLED ||
                    businessPhase == ResidentCoreBusinessPhase.STOPPED)
            }
            if (cancelled) {
                if (!leaseAdopted) runCatching { lease?.close() }
                return
            }
            if (!leaseAdopted) runCatching { lease?.close() }
            if (PluginPlatformKernel.isInitialized) {
                runCatching { runBlocking(Dispatchers.IO) { PluginPlatformKernel.shutdown() } }
            }
            synchronized(lock) {
                pluginKernelStarted = PluginPlatformKernel.isStarted
                bridgeIngressPrepared = false
                bridgePluginMounted = false
                businessAttached = false
                businessPhase = ResidentCoreBusinessPhase.FAILED
                businessError = error.toString().take(1024)
            }
            runCatching { ResidentBusinessTakeoverFence.markFailed(context, coreSession, error) }
            throw error
        }
    }

    fun requestStop() {
        stopRequested = true
    }

    fun stop(timeoutMs: Long = START_STOP_TIMEOUT_MS) {
        stopRequested = true
        synchronized(lock) {
            if (businessPhase == ResidentCoreBusinessPhase.WAITING_FOR_HOST_EXIT ||
                businessPhase == ResidentCoreBusinessPhase.ACQUIRING_OWNER) {
                businessPhase = ResidentCoreBusinessPhase.STOPPED
                businessAttached = false
            }
        }
        if (PluginPlatformKernel.isInitialized) {
            runBlocking(Dispatchers.IO) { PluginPlatformKernel.shutdown() }
            synchronized(lock) {
                pluginKernelStarted = false
                bridgeIngressPrepared = false
                bridgePluginMounted = false
                businessAttached = false
                if (businessPhase != ResidentCoreBusinessPhase.CANCELLED &&
                    businessPhase != ResidentCoreBusinessPhase.FAILED) {
                    businessPhase = ResidentCoreBusinessPhase.STOPPED
                }
            }
        }

        val stopped = CountDownLatch(1)
        val mainHandler = synchronized(lock) {
            when (phase) {
                ResidentCoreLifecyclePhase.STOPPED -> return
                ResidentCoreLifecyclePhase.FAILED -> return
                ResidentCoreLifecyclePhase.RUNNING -> phase = ResidentCoreLifecyclePhase.STOPPING
                else -> error("Resident Core runtime cannot stop from $phase")
            }
            checkNotNull(handler)
        }
        check(mainHandler.post {
            try {
                synchronized(lock) {
                    check(phase == ResidentCoreLifecyclePhase.STOPPING) {
                        "Resident Core stop barrier ran from $phase"
                    }
                    stoppedElapsedMs = SystemClock.elapsedRealtime()
                    phase = ResidentCoreLifecyclePhase.STOPPED
                }
            } catch (error: Throwable) {
                fail(error)
            } finally {
                stopped.countDown()
            }
        }) { "Resident Core main Looper rejected stop barrier" }
        check(stopped.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "Resident Core main Looper did not execute stop barrier within ${timeoutMs}ms"
        }
        synchronized(lock) {
            check(phase == ResidentCoreLifecyclePhase.STOPPED) {
                "Resident Core stop failed: ${lastError ?: phase.name}"
            }
        }
    }

    fun fail(error: Throwable) {
        synchronized(lock) {
            if (phase == ResidentCoreLifecyclePhase.STOPPED) return
            lastError = error.toString().take(1024)
            phase = ResidentCoreLifecyclePhase.FAILED
            if (businessPhase != ResidentCoreBusinessPhase.CANCELLED &&
                businessPhase != ResidentCoreBusinessPhase.STOPPED) {
                businessPhase = ResidentCoreBusinessPhase.FAILED
                businessError = error.toString().take(1024)
            }
        }
    }

    fun snapshot(): JSONObject = synchronized(lock) {
        JSONObject()
            .put("phase", phase.name.lowercase())
            .put("runtime_skeleton_ready", phase == ResidentCoreLifecyclePhase.RUNNING)
            .put("main_looper_ready", mainLooperThread != null)
            .put("main_looper_thread", mainLooperThread ?: JSONObject.NULL)
            .put("initialized_elapsed_ms", initializedElapsedMs ?: JSONObject.NULL)
            .put("started_elapsed_ms", startedElapsedMs ?: JSONObject.NULL)
            .put("stopped_elapsed_ms", stoppedElapsedMs ?: JSONObject.NULL)
            .put("business_phase", businessPhase.name.lowercase())
            .put("expected_host_pid", expectedHostPid ?: JSONObject.NULL)
            .put("business_attached", businessAttached)
            .put("plugin_kernel_started", pluginKernelStarted)
            .put("bridge_ingress_prepared", bridgeIngressPrepared)
            .put("bridge_plugin_mounted", bridgePluginMounted)
            .put("plugin_kernel", if (PluginPlatformKernel.isInitialized)
                PluginPlatformKernel.lifecycleSnapshot() else JSONObject.NULL)
            .put("last_error", lastError ?: JSONObject.NULL)
            .put("business_error", businessError ?: JSONObject.NULL)
    }

    private fun runOnMain(action: () -> Unit) {
        val main = checkNotNull(handler)
        if (Looper.myLooper() === Looper.getMainLooper()) {
            action()
            return
        }
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        check(main.post {
            try { action() } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
        }) { "Resident Core main Looper rejected business initialization" }
        check(done.await(BUSINESS_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "Resident Core main Looper did not initialize Plugin Kernel within ${BUSINESS_INIT_TIMEOUT_MS}ms"
        }
        failure.get()?.let { throw it }
    }

    private fun processExists(pid: Int): Boolean = try {
        Os.kill(pid, 0)
        true
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ESRCH) false else throw error
    }

    private companion object {
        const val START_STOP_TIMEOUT_MS = 3_000L
        const val BUSINESS_INIT_TIMEOUT_MS = 10_000L
        const val BUSINESS_TAKEOVER_TIMEOUT_MS = 20_000L
    }
}
