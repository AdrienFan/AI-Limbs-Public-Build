package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.os.Process
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutor
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsInteractionCycleRuntime
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import kotlin.system.exitProcess

/**
 * Host-side Resident ON ownership transaction.
 *
 * Core prepares the permission backend first. Host ingress is then frozen and drained before the
 * policy snapshot is staged, takeover is armed and the Host Plugin Kernel is retired. Any failure
 * after backend prepare rolls the transaction back by unfreezing Host policy and stopping Core so
 * the prepared permission backend is explicitly released back to Host.
 */
internal object ResidentPluginKernelHandoff {
    suspend fun execute(context: Context, executor: ShellExecutor): Nothing {
        val app = context.applicationContext
        val before = PluginPlatformKernel.lifecycleSnapshot()
        check(before.getBoolean("started") && before.getString("runtime_role") == "legacy_host") {
            "Host Plugin Kernel is not the active LEGACY_HOST owner"
        }
        check(before.getInt("pid") == Process.myPid() && before.getBoolean("owner_lease_held")) {
            "Host Plugin Kernel ownership identity is invalid"
        }

        val handoff = ResidentCoreController.prepareHandoff(app, executor)
        val coreSession = handoff.coreSessionId()
        val hostPid = Process.myPid()
        var policyFrozen = false
        var policyStaged = false
        var takeoverArmed = false

        try {
            val policyState = AiLimbsInteractionCycleRuntime.freezeAndExportForResidentHandoff(app)
            policyFrozen = true
            ResidentPolicyStateHandoff.stage(
                context = app,
                coreSession = coreSession,
                corePid = handoff.coreProcessId(),
                hostPid = hostPid,
                policyState = policyState
            )
            policyStaged = true
            val armed = ResidentCoreController.armBusinessTakeover(app, coreSession)
            takeoverArmed = true
            check(armed.getString("business_phase") == "waiting_for_host_exit") {
                "Resident Core did not arm business takeover"
            }
            check(armed.getInt("expected_host_pid") == hostPid) {
                "Resident Core armed takeover for a different Host process"
            }

            PluginPlatformKernel.shutdownForResidentHandoff(handoff)
            val retired = PluginPlatformKernel.lifecycleSnapshot()
            check(retired.getString("phase") == "stopped" && !retired.getBoolean("started")) {
                "Host Plugin Kernel did not retire cleanly"
            }
            check(retired.getBoolean("owner_lease_held")) {
                "Host must retain plugin_kernel lease until process exit"
            }
        } catch (error: Throwable) {
            if (policyStaged) {
                runCatching { ResidentPolicyStateHandoff.clearByHost(app, coreSession, hostPid) }
            }
            if (takeoverArmed) {
                runCatching { ResidentCoreController.cancelBusinessTakeover(app, coreSession) }
            }
            if (policyFrozen) {
                runCatching { AiLimbsInteractionCycleRuntime.cancelResidentHandoffFreeze(app) }
            }
            // prepare_handoff establishes a Core lifetime on the permission backend before Host
            // retirement. If anything later fails, stopping Core is the rollback transaction that
            // releases that prepared lifetime back to the still-running Host control plane.
            runCatching { ResidentCoreController.stop(app) }
            throw error
        }

        // Do not return into Host code after a successful retirement. Process death is the ownership
        // boundary; Core waits for this PID to disappear before attempting the plugin_kernel lease.
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
