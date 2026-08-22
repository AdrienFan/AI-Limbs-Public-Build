package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter
import com.ai.assistance.operit.core.tools.system.AccessibilityProviderInstaller
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.AndroidShellExecutor
import com.ai.assistance.operit.core.tools.system.action.ActionListenerFactory
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.data.repository.UIHierarchyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AiLimbsUiCapabilityStatus(
    val preferredPermissionLevel: AndroidPermissionLevel,
    val activeBackend: String,
    val selectedBackendAvailable: Boolean,
    val directUiReady: Boolean,
    val accessibilityProviderInstalled: Boolean,
    val accessibilityProviderVersion: String?,
    val accessibilityServiceEnabled: Boolean,
    val automaticUiBaseEnabled: Boolean,
    val automaticUiSubagentEnabled: Boolean,
    val uiControllerModelName: String?,
    val uiControllerImageEnabled: Boolean,
    val uiSubagentReady: Boolean,
    val nextAction: String?
)

/** Owns the capability checks and explicit user-authorized setup for AI Limbs UI control. */
class AiLimbsUiCapabilityService(context: Context) {
    private val appContext = context.applicationContext

    suspend fun readStatus(): AiLimbsUiCapabilityStatus = withContext(Dispatchers.IO) {
        val preferredLevel =
            androidPermissionPreferences.getPreferredPermissionLevel()
                ?: AndroidPermissionLevel.STANDARD
        val activeBackend = ToolGetter.getUITools(appContext)::class.java.simpleName
        val selectedExecutor = ShellExecutorFactory.getExecutor(appContext, preferredLevel)
        val selectedPermission = selectedExecutor.hasPermission()
        val selectedBackendAvailable =
            selectedExecutor.isAvailable() && selectedPermission.granted
        val providerVersion = AccessibilityProviderInstaller.getInstalledVersion(appContext)
        val accessibilityEnabled =
            if (providerVersion == null) {
                false
            } else {
                UIHierarchyManager.isAccessibilityServiceEnabled(appContext)
            }

        val packageManager = AIToolHandler.getInstance(appContext).getOrCreatePackageManager()
        val baseEnabled =
            runCatching { packageManager.isPackageEnabled(AUTOMATIC_UI_BASE) }
                .getOrDefault(false)
        val subagentEnabled =
            runCatching { packageManager.isPackageEnabled(AUTOMATIC_UI_SUBAGENT) }
                .getOrDefault(false)

        val functionalConfigManager = FunctionalConfigManager(appContext)
        val modelConfigManager = ModelConfigManager(appContext)
        val uiControllerConfigId =
            functionalConfigManager.getConfigIdForFunction(FunctionType.UI_CONTROLLER)
        val uiControllerConfig = modelConfigManager.getModelConfig(uiControllerConfigId)
        val uiControllerModelName =
            uiControllerConfig?.modelName?.trim()?.takeIf { it.isNotEmpty() }
        val uiControllerImageEnabled =
            uiControllerConfig?.enableDirectImageProcessing == true

        val directUiReady =
            when (preferredLevel) {
                AndroidPermissionLevel.STANDARD -> false
                AndroidPermissionLevel.ACCESSIBILITY -> accessibilityEnabled
                else -> selectedBackendAvailable
            }
        val subagentReady =
            directUiReady &&
                subagentEnabled &&
                uiControllerModelName != null &&
                uiControllerImageEnabled
        val nextAction =
            when {
                preferredLevel == AndroidPermissionLevel.STANDARD ->
                    "Select ACCESSIBILITY, DEBUGGER, ADMIN, or ROOT as the UI permission level."
                preferredLevel == AndroidPermissionLevel.ACCESSIBILITY && providerVersion == null ->
                    "Install the accessibility provider app."
                preferredLevel == AndroidPermissionLevel.ACCESSIBILITY && !accessibilityEnabled ->
                    "Enable the Operit accessibility provider in Android settings."
                !directUiReady ->
                    "Authorize the selected ${preferredLevel.name} permission backend."
                !subagentReady ->
                    "Configure a UI_CONTROLLER model with direct image processing for the UI subagent."
                else -> null
            }

        AiLimbsUiCapabilityStatus(
            preferredPermissionLevel = preferredLevel,
            activeBackend = activeBackend,
            selectedBackendAvailable = selectedBackendAvailable,
            directUiReady = directUiReady,
            accessibilityProviderInstalled = providerVersion != null,
            accessibilityProviderVersion = providerVersion,
            accessibilityServiceEnabled = accessibilityEnabled,
            automaticUiBaseEnabled = baseEnabled,
            automaticUiSubagentEnabled = subagentEnabled,
            uiControllerModelName = uiControllerModelName,
            uiControllerImageEnabled = uiControllerImageEnabled,
            uiSubagentReady = subagentReady,
            nextAction = nextAction
        )
    }

    suspend fun selectAccessibilityMode() {
        androidPermissionPreferences.savePreferredPermissionLevel(
            AndroidPermissionLevel.ACCESSIBILITY
        )
        AndroidShellExecutor.clearPreferredPermissionLevelCache()
        ShellExecutorFactory.clearCache()
        ActionListenerFactory.clearCache()
    }

    fun installAccessibilityProvider() {
        AccessibilityProviderInstaller.launchInstall(appContext)
    }

    fun openAccessibilitySettings() {
        appContext.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private companion object {
        const val AUTOMATIC_UI_BASE = "Automatic_ui_base"
        const val AUTOMATIC_UI_SUBAGENT = "Automatic_ui_subagent"
    }
}
