package com.ai.assistance.operit.plugins.center

import com.ai.limbs.plugin.runtime.ChildExtensionBackupSnapshot
import com.ai.limbs.plugin.runtime.ChildExtensionSnapshot
import com.ai.limbs.plugin.runtime.ExtensionHubService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal const val EXTENSION_HUB_INCOMPATIBLE_MESSAGE =
    "Plugin Extension Hub 版本过旧，请更新后重试"

private fun extensionHubIncompatible(cause: LinkageError): PluginInstallException =
    PluginInstallException(
        "EXTENSION_HUB_INCOMPATIBLE",
        EXTENSION_HUB_INCOMPATIBLE_MESSAGE,
        cause
    )

internal fun ExtensionHubService.compatBackupSnapshots(): StateFlow<List<ChildExtensionBackupSnapshot>> =
    try {
        backupSnapshots()
    } catch (_: LinkageError) {
        MutableStateFlow(emptyList())
    }
internal suspend fun ExtensionHubService.compatSetAutoBackupPolicy(
    enabled: Boolean,
    highFrequencyUseCount: Long
): Boolean = try {
    setAutoBackupPolicy(enabled, highFrequencyUseCount)
    true
} catch (_: LinkageError) {
    false
}

internal suspend fun ExtensionHubService.compatBackup(
    extensionId: String
): ChildExtensionBackupSnapshot = try {
    backup(extensionId)
} catch (error: LinkageError) {
    throw extensionHubIncompatible(error)
}

internal suspend fun ExtensionHubService.compatRestoreBackup(
    extensionId: String
): ChildExtensionSnapshot = try {
    restoreBackup(extensionId)
} catch (error: LinkageError) {
    throw extensionHubIncompatible(error)
}
internal suspend fun ExtensionHubService.compatDeleteBackup(
    extensionId: String
): Boolean = try {
    deleteBackup(extensionId)
} catch (error: LinkageError) {
    throw extensionHubIncompatible(error)
}
