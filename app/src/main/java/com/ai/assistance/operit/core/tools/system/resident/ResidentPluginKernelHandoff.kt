package com.ai.assistance.operit.core.tools.system.resident

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsInteractionCycleRuntime
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import com.ai.assistance.operit.util.AppLogger
import kotlin.system.exitProcess
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Host-side Resident ON ownership transaction.
 *
 * Core prepares the permission backend first. Host ingress is then frozen and drained before the
 * policy snapshot is staged, takeover is armed and the Host Plugin Kernel is retired.
 *
 * Failure is split at the exact destructive-retirement boundary. Before Plugin Kernel retirement
 * mutates Host ownership, rollback may resume the same LEGACY_HOST VM. Once retirement begins, the
 * VM is permanently tainted: rollback cleans Core/fence/policy state, schedules a cold Host restart
 * and terminates this process instead of ever returning into partially retired business code.
 */
internal object ResidentPluginKernelHandoff {
    suspend fun execute(
        context: Context,
        backendRequirement: ResidentBackendRequirement
    ): Nothing {
        val app = context.applicationContext
        val before = PluginPlatformKernel.lifecycleSnapshot()
        check(before.getBoolean("started") && before.getString("runtime_role") == "legacy_host") {
            "Host Plugin Kernel is not the active LEGACY_HOST owner"
        }
        check(before.getInt("pid") == Process.myPid() && before.getBoolean("owner_lease_held")) {
            "Host Plugin Kernel ownership identity is invalid"
        }

        val handoff = ResidentCoreController.prepareHandoff(app, backendRequirement)
        val coreSession = handoff.coreSessionId
        val hostPid = Process.myPid()
        var policyFrozen = false
        var policyStaged = false
        var takeoverArmed = false
        var destructiveRetirementStarted = false

        try {
            val policyState = AiLimbsInteractionCycleRuntime.freezeAndExportForResidentHandoff(app)
            policyFrozen = true
            ResidentPolicyStateHandoff.stage(
                context = app,
                coreSession = coreSession,
                corePid = handoff.coreProcessId,
                hostPid = hostPid,
                policyState = policyState
            )
            policyStaged = true
            val armed = ResidentCoreController.armBusinessTakeover(app, coreSession, backendRequirement)
            takeoverArmed = true
            check(armed.getString("business_phase") == "waiting_for_host_exit") {
                "Resident Core did not arm business takeover"
            }
            check(armed.getInt("expected_host_pid") == hostPid) {
                "Resident Core armed takeover for a different Host process"
            }

            PluginPlatformKernel.shutdownForResidentHandoff(handoff.permissionHandoff) {
                destructiveRetirementStarted = true
            }
            val retired = PluginPlatformKernel.lifecycleSnapshot()
            check(retired.getString("phase") == "stopped" && !retired.getBoolean("started")) {
                "Host Plugin Kernel did not retire cleanly"
            }
            check(retired.getBoolean("owner_lease_held")) {
                "Host must retain plugin_kernel lease until process exit"
            }
        } catch (error: Throwable) {
            if (!destructiveRetirementStarted) {
                val rollbackErrors = rollbackBeforeDestructiveRetirement(
                    app = app,
                    coreSession = coreSession,
                    hostPid = hostPid,
                    policyFrozen = policyFrozen,
                    policyStaged = policyStaged,
                    takeoverArmed = takeoverArmed
                ).toMutableList()
                if (rollbackErrors.isNotEmpty()) {
                    val rollbackFailure = IllegalStateException(
                        "Pre-destructive Resident rollback could not be verified; " +
                            "this Host VM must be recycled",
                        error
                    )
                    runCatching {
                        AiLimbsResidentRuntime.blockAutomaticOnRetryAfterHandoffFailure(
                            context = app,
                            error = rollbackFailure,
                            cleanupErrors = rollbackErrors
                        )
                    }.onFailure { persistError ->
                        rollbackErrors +=
                            "persist_retry_block: ${persistError.message ?: persistError.javaClass.simpleName}"
                    }
                    scheduleColdHostRestart(app, rollbackFailure, rollbackErrors)
                }
                throw error
            }

            rollbackAfterDestructiveRetirementAndRestart(
                app = app,
                coreSession = coreSession,
                hostPid = hostPid,
                policyFrozen = policyFrozen,
                policyStaged = policyStaged,
                takeoverArmed = takeoverArmed,
                originalError = error
            )
        }

        // Do not return into Host code after a successful retirement. Process death is the ownership
        // boundary; Core waits for this PID to disappear before attempting the plugin_kernel lease.
        // Schedule the UI relaunch before exiting so Resident ON is a complete role-switch transaction
        // instead of requiring the user to tap the launcher again. The delay gives Core time to acquire
        // plugin_kernel and restore business ownership before the fresh MainActivity resolves its role.
        val relaunchScheduled = scheduleUiRelaunch(
            app = app,
            requestCode = SUCCESS_RESTART_REQUEST_CODE,
            delayMs = SUCCESS_RESTART_DELAY_MS
        )
        if (relaunchScheduled) {
            AppLogger.i(TAG, "Resident ON completed; automatic UI relaunch scheduled")
        } else {
            AppLogger.e(TAG, "Resident ON completed but automatic UI relaunch could not be scheduled")
        }
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    private suspend fun rollbackBeforeDestructiveRetirement(
        app: Context,
        coreSession: String,
        hostPid: Int,
        policyFrozen: Boolean,
        policyStaged: Boolean,
        takeoverArmed: Boolean
    ): List<String> = withContext(NonCancellable) {
        val errors = mutableListOf<String>()

        fun record(stage: String, error: Throwable) {
            errors += "$stage: ${error.message ?: error.javaClass.simpleName}"
            AppLogger.e(TAG, "Pre-destructive handoff rollback failed at $stage", error)
        }

        if (takeoverArmed) {
            runCatching { ResidentCoreController.cancelBusinessTakeover(app, coreSession) }
                .onFailure { record("cancel_takeover", it) }
        }
        if (policyStaged) {
            runCatching { ResidentPolicyStateHandoff.clearByHost(app, coreSession, hostPid) }
                .onFailure { record("clear_policy_handoff", it) }
        }
        if (policyFrozen) {
            runCatching { AiLimbsInteractionCycleRuntime.cancelResidentHandoffFreeze(app) }
                .onFailure { record("cancel_policy_freeze", it) }
        }

        val coreStopped =
            runCatching {
                val stopped = ResidentCoreController.stop(app)
                check(stopped.optBoolean("stopped", false)) {
                    "Core stop did not report stopped=true: $stopped"
                }
                true
            }.getOrElse {
                record("stop_core", it)
                false
            }

        if (coreStopped) {
            runCatching {
                if (ResidentPolicyStateHandoff.snapshot(app) != null) {
                    ResidentPolicyStateHandoff.clearAfterVerifiedOwnerLoss(app)
                }
            }.onFailure { record("clear_stale_policy_after_core_exit", it) }
            runCatching {
                if (ResidentBusinessTakeoverFence.snapshot(app) != null) {
                    ResidentBusinessTakeoverFence.clearAfterVerifiedOwnerLoss(app)
                }
            }.onFailure { record("clear_stale_fence_after_core_exit", it) }
        }

        runCatching {
            val status = ResidentCoreController.status(app)
            check(!status.optBoolean("available", false) && status.optString("phase") == "stopped") {
                "Core rollback is not proven stopped: $status"
            }
        }.onFailure { record("verify_core_stopped", it) }
        runCatching {
            check(ResidentPolicyStateHandoff.snapshot(app) == null) {
                "Resident policy handoff remains after rollback"
            }
        }.onFailure { record("verify_policy_cleared", it) }
        runCatching {
            check(ResidentBusinessTakeoverFence.snapshot(app) == null) {
                "Resident takeover fence remains after rollback"
            }
        }.onFailure { record("verify_fence_cleared", it) }
        if (policyFrozen) {
            runCatching {
                val policy = AiLimbsInteractionCycleRuntime.state(app).snapshot()
                check(!policy.optBoolean("resident_handoff_frozen", false)) {
                    "Interaction Cycle remains frozen after rollback: $policy"
                }
            }.onFailure { record("verify_policy_unfrozen", it) }
        }
        runCatching {
            val hostKernel = PluginPlatformKernel.lifecycleSnapshot()
            check(
                hostKernel.getBoolean("started") &&
                    hostKernel.getString("runtime_role") == "legacy_host" &&
                    hostKernel.getBoolean("owner_lease_held") &&
                    hostKernel.getInt("pid") == Process.myPid()
            ) {
                "LEGACY_HOST is not intact after pre-destructive rollback: $hostKernel"
            }
        }.onFailure { record("verify_legacy_host_intact", it) }

        errors.distinct()
    }

    private suspend fun rollbackAfterDestructiveRetirementAndRestart(
        app: Context,
        coreSession: String,
        hostPid: Int,
        policyFrozen: Boolean,
        policyStaged: Boolean,
        takeoverArmed: Boolean,
        originalError: Throwable
    ): Nothing = withContext(NonCancellable) {
        val cleanupErrors = mutableListOf<String>()

        fun recordCleanupFailure(stage: String, error: Throwable) {
            cleanupErrors += "$stage: ${error.message ?: error.javaClass.simpleName}"
            AppLogger.e(TAG, "Post-destructive handoff rollback failed at $stage", error)
        }

        // Stop activation first so Core cannot interpret the imminent Host death as permission to
        // acquire plugin_kernel while this rollback is trying to restore LEGACY_HOST ownership.
        if (takeoverArmed) {
            runCatching { ResidentCoreController.cancelBusinessTakeover(app, coreSession) }
                .onFailure { recordCleanupFailure("cancel_takeover", it) }
        }
        if (policyStaged) {
            runCatching { ResidentPolicyStateHandoff.clearByHost(app, coreSession, hostPid) }
                .onFailure { recordCleanupFailure("clear_policy_handoff", it) }
        }
        if (policyFrozen) {
            runCatching { AiLimbsInteractionCycleRuntime.cancelResidentHandoffFreeze(app) }
                .onFailure { recordCleanupFailure("cancel_policy_freeze", it) }
        }

        val coreStopped =
            runCatching {
                ResidentCoreController.stop(app)
                true
            }.getOrElse {
                recordCleanupFailure("stop_core", it)
                false
            }

        if (coreStopped) {
            if (ResidentPolicyStateHandoff.snapshot(app) != null) {
                runCatching { ResidentPolicyStateHandoff.clearAfterVerifiedOwnerLoss(app) }
                    .onFailure { recordCleanupFailure("clear_stale_policy_after_core_exit", it) }
            }
            if (ResidentBusinessTakeoverFence.snapshot(app) != null) {
                runCatching { ResidentBusinessTakeoverFence.clearAfterVerifiedOwnerLoss(app) }
                    .onFailure { recordCleanupFailure("clear_stale_fence_after_core_exit", it) }
            }
        }

        if (ResidentPolicyStateHandoff.snapshot(app) != null) {
            cleanupErrors += "policy handoff remains after destructive rollback"
        }
        if (ResidentBusinessTakeoverFence.snapshot(app) != null) {
            cleanupErrors += "takeover fence remains after destructive rollback"
        }

        runCatching {
            AiLimbsResidentRuntime.blockAutomaticOnRetryAfterHandoffFailure(
                context = app,
                error = originalError,
                cleanupErrors = cleanupErrors
            )
        }.onFailure { recordCleanupFailure("persist_retry_block", it) }

        scheduleColdHostRestart(app, originalError, cleanupErrors)
    }

    private fun scheduleColdHostRestart(
        app: Context,
        originalError: Throwable,
        cleanupErrors: List<String>
    ): Nothing {
        val restartScheduled = scheduleUiRelaunch(
            app = app,
            requestCode = COLD_RESTART_REQUEST_CODE,
            delayMs = COLD_RESTART_DELAY_MS
        )

        AppLogger.e(
            TAG,
            "Resident ON handoff cannot safely continue in this Host VM; " +
                "restartScheduled=$restartScheduled cleanupErrors=$cleanupErrors. " +
                "This VM will terminate and must never resume LEGACY_HOST.",
            originalError
        )

        Process.killProcess(Process.myPid())
        exitProcess(1)
    }

    private fun scheduleUiRelaunch(app: Context, requestCode: Int, delayMs: Long): Boolean = runCatching {
        val launchIntent = checkNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName)) {
            "No launch intent for ${app.packageName}"
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pending = PendingIntent.getActivity(
            app,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.RTC, System.currentTimeMillis() + delayMs, pending)
        true
    }.onFailure { error ->
        AppLogger.e(TAG, "Could not schedule AI Limbs UI relaunch", error)
    }.getOrDefault(false)

    private const val TAG = "ResidentPluginKernelHandoff"
    private const val COLD_RESTART_REQUEST_CODE = 0xA12
    private const val COLD_RESTART_DELAY_MS = 500L
    private const val SUCCESS_RESTART_REQUEST_CODE = 0xA13
    private const val SUCCESS_RESTART_DELAY_MS = 1_500L
}
