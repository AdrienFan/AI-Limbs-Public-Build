package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import android.os.Process
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutor
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsInteractionCycleRuntime
import com.ai.assistance.operit.plugins.center.PluginPlatformKernel
import kotlin.system.exitProcess

/**
 * Host-side one-way Plugin Kernel ownership handoff. Not wired to Resident ON/OFF yet.
 *
 * Success is intentionally terminal for the old Host process: Core is armed first, the existing
 * kernel retires with the prepared permission retention permit, and only then does this process
 * exit so its process-held plugin_kernel lease can be acquired by Core.
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
        val policyState = AiLimbsInteractionCycleRuntime.freezeAndExportForResidentHandoff(app)

        try {
            ResidentPolicyStateHandoff.stage(
                context = app,
                coreSession = coreSession,
                corePid = handoff.coreProcessId(),
                hostPid = hostPid,
                policyState = policyState
            )
            val armed = ResidentCoreController.armBusinessTakeover(coreSession)
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
            runCatching { ResidentPolicyStateHandoff.clearByHost(app, coreSession, hostPid) }
            runCatching { ResidentCoreController.cancelBusinessTakeover(coreSession) }
            AiLimbsInteractionCycleRuntime.cancelResidentHandoffFreeze(app)
            throw error
        }

        // Do not return into Host code after a successful retirement. Process death is the ownership
        // boundary; Core waits for this PID to disappear before attempting the plugin_kernel lease.
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
