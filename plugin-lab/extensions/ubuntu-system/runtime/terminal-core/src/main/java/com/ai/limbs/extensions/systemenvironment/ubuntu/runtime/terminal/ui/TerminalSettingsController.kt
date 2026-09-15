package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.ui

import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.PackageManagerType
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.SSHConfig
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.SourceConfig
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.utils.VirtualKeyboardLayoutConfig
import kotlinx.coroutines.flow.StateFlow

/** Presentation-facing settings contract. Implementations decide whether state is local or Core-proxied. */
interface TerminalSettingsController {
    val cacheSize: StateFlow<String>
    val updateStatus: StateFlow<String>
    val isCalculatingCache: StateFlow<Boolean>
    val ftpServerStatus: StateFlow<String>
    val isFtpServerRunning: StateFlow<Boolean>
    val isManagingFtpServer: StateFlow<Boolean>
    val hasUpdateAvailable: StateFlow<Boolean>
    val sourceConfigs: StateFlow<Map<PackageManagerType, SourceConfig>>
    val sshConfig: StateFlow<SSHConfig?>
    val sshEnabled: StateFlow<Boolean>
    val showSshToolsMissingDialog: StateFlow<Boolean>
    val showOpensshMissingDialog: StateFlow<Boolean>
    val sharedTmpEnabled: StateFlow<Boolean>
    val chrootEnabled: StateFlow<Boolean>
    val chrootMountStatus: StateFlow<String>
    val chrootMountDetails: StateFlow<String>
    val isInspectingChrootMounts: StateFlow<Boolean>
    val isUnmountingChrootMounts: StateFlow<Boolean>
    val virtualKeyboardLayout: StateFlow<VirtualKeyboardLayoutConfig>

    fun onSshToolsMissingDialogDismissed()
    fun onOpensshMissingDialogDismissed()
    fun updateSource(pm: PackageManagerType, sourceId: String)
    fun addCustomSource(pm: PackageManagerType, name: String, url: String, isHttps: Boolean)
    fun deleteCustomSource(pm: PackageManagerType, sourceId: String)
    fun getCacheSize()
    fun clearCache()
    fun checkForUpdates()
    fun openGitHubRepo()
    fun openGitHubReleases()
    fun startFtpServer()
    fun stopFtpServer()
    fun saveSSHConfig(config: SSHConfig)
    fun deleteSSHConfig()
    fun setSSHEnabled(enabled: Boolean)
    fun setSharedTmpEnabled(enabled: Boolean)
    fun setChrootEnabled(enabled: Boolean)
    fun inspectChrootMounts()
    fun unmountChrootMounts()
    fun saveVirtualKeyboardLayout(layout: VirtualKeyboardLayoutConfig)
}

internal class LocalTerminalSettingsController(
    private val viewModel: SettingsViewModel
) : TerminalSettingsController {
    override val cacheSize = viewModel.cacheSize
    override val updateStatus = viewModel.updateStatus
    override val isCalculatingCache = viewModel.isCalculatingCache
    override val ftpServerStatus = viewModel.ftpServerStatus
    override val isFtpServerRunning = viewModel.isFtpServerRunning
    override val isManagingFtpServer = viewModel.isManagingFtpServer
    override val hasUpdateAvailable = viewModel.hasUpdateAvailable
    override val sourceConfigs = viewModel.sourceConfigs
    override val sshConfig = viewModel.sshConfig
    override val sshEnabled = viewModel.sshEnabled
    override val showSshToolsMissingDialog = viewModel.showSshToolsMissingDialog
    override val showOpensshMissingDialog = viewModel.showOpensshMissingDialog
    override val sharedTmpEnabled = viewModel.sharedTmpEnabled
    override val chrootEnabled = viewModel.chrootEnabled
    override val chrootMountStatus = viewModel.chrootMountStatus
    override val chrootMountDetails = viewModel.chrootMountDetails
    override val isInspectingChrootMounts = viewModel.isInspectingChrootMounts
    override val isUnmountingChrootMounts = viewModel.isUnmountingChrootMounts
    override val virtualKeyboardLayout = viewModel.virtualKeyboardLayout

    override fun onSshToolsMissingDialogDismissed() = viewModel.onSshToolsMissingDialogDismissed()
    override fun onOpensshMissingDialogDismissed() = viewModel.onOpensshMissingDialogDismissed()
    override fun updateSource(pm: PackageManagerType, sourceId: String) = viewModel.updateSource(pm, sourceId)
    override fun addCustomSource(pm: PackageManagerType, name: String, url: String, isHttps: Boolean) =
        viewModel.addCustomSource(pm, name, url, isHttps)
    override fun deleteCustomSource(pm: PackageManagerType, sourceId: String) = viewModel.deleteCustomSource(pm, sourceId)
    override fun getCacheSize() = viewModel.getCacheSize()
    override fun clearCache() = viewModel.clearCache()
    override fun checkForUpdates() = viewModel.checkForUpdates()
    override fun openGitHubRepo() = viewModel.openGitHubRepo()
    override fun openGitHubReleases() = viewModel.openGitHubReleases()
    override fun startFtpServer() = viewModel.startFtpServer()
    override fun stopFtpServer() = viewModel.stopFtpServer()
    override fun saveSSHConfig(config: SSHConfig) = viewModel.saveSSHConfig(config)
    override fun deleteSSHConfig() = viewModel.deleteSSHConfig()
    override fun setSSHEnabled(enabled: Boolean) = viewModel.setSSHEnabled(enabled)
    override fun setSharedTmpEnabled(enabled: Boolean) = viewModel.setSharedTmpEnabled(enabled)
    override fun setChrootEnabled(enabled: Boolean) = viewModel.setChrootEnabled(enabled)
    override fun inspectChrootMounts() = viewModel.inspectChrootMounts()
    override fun unmountChrootMounts() = viewModel.unmountChrootMounts()
    override fun saveVirtualKeyboardLayout(layout: VirtualKeyboardLayoutConfig) = viewModel.saveVirtualKeyboardLayout(layout)
}
