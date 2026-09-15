package com.ai.assistance.operit.core.tools.system.resident

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ai.assistance.operit.util.AppLogger

class ResidentCoreBootstrapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        require(intent.action == context.packageName + ".action.RESIDENT_CORE_BIND")
        val pending = goAsync()
        Thread({
            try { ResidentCoreBootstrap.offerBackend(context.applicationContext, intent) }
            catch (error: Exception) { AppLogger.e("ResidentCoreBootstrap", "Core bootstrap failed", error) }
            finally { pending.finish() }
        }, "resident-bootstrap-offer").apply { isDaemon = true; start() }
    }
}
