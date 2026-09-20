package com.ai.assistance.operit.core.tools.system.resident

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.util.AppLogger

class ResidentBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val app = context.applicationContext
        runCatching {
            AiLimbsResidentRuntime.initialize(app)
            if (!AiLimbsResidentRuntime.isEnabledForHost()) {
                AppLogger.i(TAG, "Resident boot recovery skipped because desired state is OFF")
                return
            }
            AIForegroundService.ensureResidentKeepAlive(app)
            AppLogger.i(TAG, "Resident cold recovery requested after ${intent.action}")
        }.onFailure { error ->
            AppLogger.e(TAG, "Resident cold recovery entry failed", error)
        }
    }

    companion object { private const val TAG = "ResidentBootReceiver" }
}
