package com.ai.assistance.operit.core.application

import android.app.Notification
import android.app.Service
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.ai.assistance.operit.util.AppLogger

data class AiLimbsBackgroundSurvivalPolicy(
    val persistentBackgroundRequested: Boolean,
    val dozeAllowlisted: Boolean,
    val systemExemptedEligible: Boolean,
    val systemExemptedRejectedForProcess: Boolean,
    val systemExemptedRequested: Boolean,
    val primaryTypes: Int,
    val fallbackTypes: Int
)

data class AiLimbsBackgroundSurvivalApplyResult(
    val appliedTypes: Int,
    val systemExemptedApplied: Boolean,
    val changed: Boolean
)

class AiLimbsBackgroundSurvivalManager(context: Context) {
    private val appContext = context.applicationContext

    @Volatile
    private var systemExemptedRejectedForProcess = false

    @Volatile
    private var lastAppliedTypes: Int? = null

    fun buildPolicy(
        persistentBackgroundRequested: Boolean,
        dataSync: Boolean = true,
        specialUse: Boolean = false,
        microphone: Boolean = false
    ): AiLimbsBackgroundSurvivalPolicy {
        val dozeAllowlisted = isDozeAllowlisted()
        val systemExemptedEligible =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && dozeAllowlisted
        val systemExemptedRequested =
            persistentBackgroundRequested &&
                systemExemptedEligible &&
                !systemExemptedRejectedForProcess
        val fallbackTypes = ForegroundServiceCompat.buildTypes(
            dataSync = dataSync,
            specialUse = specialUse,
            microphone = microphone,
            systemExempted = false
        )
        val primaryTypes = ForegroundServiceCompat.buildTypes(
            dataSync = dataSync,
            specialUse = specialUse,
            microphone = microphone,
            systemExempted = systemExemptedRequested
        )
        return AiLimbsBackgroundSurvivalPolicy(
            persistentBackgroundRequested = persistentBackgroundRequested,
            dozeAllowlisted = dozeAllowlisted,
            systemExemptedEligible = systemExemptedEligible,
            systemExemptedRejectedForProcess = systemExemptedRejectedForProcess,
            systemExemptedRequested = systemExemptedRequested,
            primaryTypes = primaryTypes,
            fallbackTypes = fallbackTypes
        )
    }

    fun applyForeground(
        service: Service,
        notificationId: Int,
        notification: Notification,
        policy: AiLimbsBackgroundSurvivalPolicy,
        reason: String,
        force: Boolean = false
    ): AiLimbsBackgroundSurvivalApplyResult {
        if (!force && lastAppliedTypes == policy.primaryTypes) {
            return AiLimbsBackgroundSurvivalApplyResult(
                appliedTypes = policy.primaryTypes,
                systemExemptedApplied = policy.systemExemptedRequested,
                changed = false
            )
        }

        return try {
            ForegroundServiceCompat.startForeground(
                service = service,
                notificationId = notificationId,
                notification = notification,
                types = policy.primaryTypes
            )
            lastAppliedTypes = policy.primaryTypes
            if (policy.systemExemptedRequested) {
                AppLogger.i(
                    TAG,
                    "AI Limbs systemExempted foreground applied: reason=$reason, " +
                        "types=0x${policy.primaryTypes.toString(16)}, dozeAllowlisted=${policy.dozeAllowlisted}"
                )
            }
            AiLimbsBackgroundSurvivalApplyResult(
                appliedTypes = policy.primaryTypes,
                systemExemptedApplied = policy.systemExemptedRequested,
                changed = true
            )
        } catch (error: RuntimeException) {
            if (!policy.systemExemptedRequested || !isSystemExemptedFallbackError(error)) {
                throw error
            }

            systemExemptedRejectedForProcess = true
            AppLogger.w(
                TAG,
                "AI Limbs systemExempted foreground rejected; falling back: reason=$reason, " +
                    "error=${error.javaClass.simpleName}: ${error.message}",
                error
            )
            ForegroundServiceCompat.startForeground(
                service = service,
                notificationId = notificationId,
                notification = notification,
                types = policy.fallbackTypes
            )
            lastAppliedTypes = policy.fallbackTypes
            AiLimbsBackgroundSurvivalApplyResult(
                appliedTypes = policy.fallbackTypes,
                systemExemptedApplied = false,
                changed = true
            )
        }
    }

    private fun isDozeAllowlisted(): Boolean {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return runCatching {
            powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
        }.getOrDefault(false)
    }

    private fun isSystemExemptedFallbackError(error: RuntimeException): Boolean {
        if (error is SecurityException || error is IllegalArgumentException) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            error.javaClass.simpleName == "ForegroundServiceTypeNotAllowedException"
    }

    companion object {
        private const val TAG = "AiLimbsBackgroundSurvival"
    }
}
