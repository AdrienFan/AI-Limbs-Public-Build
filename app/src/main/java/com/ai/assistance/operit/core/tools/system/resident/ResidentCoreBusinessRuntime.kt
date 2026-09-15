package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

/**
 * Independent Resident Core business-runtime entry point.
 *
 * Step 3 intentionally starts only the process/runtime skeleton. It does not initialize
 * PluginPlatformKernel, Bridge, Dispatcher, plugins or Interaction Cycle ownership yet. RUNNING
 * therefore means the Core runtime and its main Looper have completed their own startup barrier;
 * it never means business takeover or continuous-work acceptance.
 */
internal class ResidentCoreBusinessRuntime {
    private val lock = Any()
    private var phase = ResidentCoreLifecyclePhase.CREATED
    private var handler: Handler? = null
    private var initializedElapsedMs: Long? = null
    private var startedElapsedMs: Long? = null
    private var stoppedElapsedMs: Long? = null
    private var mainLooperThread: String? = null
    private var lastError: String? = null

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

    fun stop(timeoutMs: Long = START_STOP_TIMEOUT_MS) {
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
            .put("business_attached", false)
            .put("plugin_kernel_started", false)
            .put("last_error", lastError ?: JSONObject.NULL)
    }

    private companion object {
        const val START_STOP_TIMEOUT_MS = 3_000L
    }
}
