package com.ai.limbs.extensions.systemenvironment.ubuntu

import android.content.Context
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.MirrorSource
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.PackageManagerType
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.SSHAuthType
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.SSHConfig
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.SourceConfig
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.ui.TerminalSettingsController
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.utils.UpdateChecker
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.utils.VirtualKeyboardConfigManager
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.utils.VirtualKeyboardLayoutConfig
import com.ai.limbs.plugin.runtime.ChildExtensionPresentationHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Host UI state backed by Core-only Ubuntu settings commands. No Ubuntu business manager is created here. */
internal class UbuntuResidentSettingsController(
    private val context: Context,
    private val host: ChildExtensionPresentationHost
) : TerminalSettingsController {
    private val updateChecker = UpdateChecker(context)
    private val keyboard = VirtualKeyboardConfigManager.getInstance(context)

    private val _cacheSize = MutableStateFlow(str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.cache_size_default))
    override val cacheSize = _cacheSize.asStateFlow()
    private val _updateStatus = MutableStateFlow(str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.update_status_default))
    override val updateStatus = _updateStatus.asStateFlow()
    private val _isCalculatingCache = MutableStateFlow(false)
    override val isCalculatingCache = _isCalculatingCache.asStateFlow()
    private val _ftpServerStatus = MutableStateFlow(str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.ftp_server_not_running))
    override val ftpServerStatus = _ftpServerStatus.asStateFlow()
    private val _isFtpServerRunning = MutableStateFlow(false)
    override val isFtpServerRunning = _isFtpServerRunning.asStateFlow()
    private val _isManagingFtpServer = MutableStateFlow(false)
    override val isManagingFtpServer = _isManagingFtpServer.asStateFlow()
    private val _hasUpdateAvailable = MutableStateFlow(false)
    override val hasUpdateAvailable = _hasUpdateAvailable.asStateFlow()
    private val _sourceConfigs = MutableStateFlow<Map<PackageManagerType, SourceConfig>>(emptyMap())
    override val sourceConfigs = _sourceConfigs.asStateFlow()
    private val _sshConfig = MutableStateFlow<SSHConfig?>(null)
    override val sshConfig = _sshConfig.asStateFlow()
    private val _sshEnabled = MutableStateFlow(false)
    override val sshEnabled = _sshEnabled.asStateFlow()
    private val _showSshToolsMissingDialog = MutableStateFlow(false)
    override val showSshToolsMissingDialog = _showSshToolsMissingDialog.asStateFlow()
    private val _showOpensshMissingDialog = MutableStateFlow(false)
    override val showOpensshMissingDialog = _showOpensshMissingDialog.asStateFlow()
    private val _sharedTmpEnabled = MutableStateFlow(true)
    override val sharedTmpEnabled = _sharedTmpEnabled.asStateFlow()
    private val _chrootEnabled = MutableStateFlow(false)
    override val chrootEnabled = _chrootEnabled.asStateFlow()
    private val _chrootMountStatus = MutableStateFlow(str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.chroot_mount_status_idle))
    override val chrootMountStatus = _chrootMountStatus.asStateFlow()
    private val _chrootMountDetails = MutableStateFlow("")
    override val chrootMountDetails = _chrootMountDetails.asStateFlow()
    private val _isInspectingChrootMounts = MutableStateFlow(false)
    override val isInspectingChrootMounts = _isInspectingChrootMounts.asStateFlow()
    private val _isUnmountingChrootMounts = MutableStateFlow(false)
    override val isUnmountingChrootMounts = _isUnmountingChrootMounts.asStateFlow()
    private val _virtualKeyboardLayout = MutableStateFlow(keyboard.loadLayout())
    override val virtualKeyboardLayout = _virtualKeyboardLayout.asStateFlow()

    init {
        host.scope.launch { runCatching { refreshSnapshot() }.onFailure(::logFailure) }
        checkForUpdates()
    }

    override fun onSshToolsMissingDialogDismissed() { _showSshToolsMissingDialog.value = false }
    override fun onOpensshMissingDialogDismissed() { _showOpensshMissingDialog.value = false }

    override fun updateSource(pm: PackageManagerType, sourceId: String) = coreLaunch {
        invoke("settings.source.select", JSONObject().put("package_manager", pm.name).put("source_id", sourceId))
        refreshSnapshot()
    }

    override fun addCustomSource(pm: PackageManagerType, name: String, url: String, isHttps: Boolean) = coreLaunch {
        invoke("settings.source.add", JSONObject()
            .put("package_manager", pm.name).put("name", name).put("url", url).put("is_https", isHttps))
        refreshSnapshot()
    }

    override fun deleteCustomSource(pm: PackageManagerType, sourceId: String) = coreLaunch {
        invoke("settings.source.delete", JSONObject().put("package_manager", pm.name).put("source_id", sourceId))
        refreshSnapshot()
    }

    override fun getCacheSize() {
        host.scope.launch {
            _isCalculatingCache.value = true
            _cacheSize.value = str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.cache_calculating)
            try {
                val result = invoke("settings.cache.size")
                requireOk(result)
                _cacheSize.value = result.optString("formatted")
            } catch (error: Throwable) {
                _cacheSize.value = str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.cache_calculation_failed)
                logFailure(error)
            } finally {
                _isCalculatingCache.value = false
            }
        }
    }

    override fun clearCache() = coreLaunch(
        before = { _cacheSize.value = str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.environment_resetting) },
        after = { _cacheSize.value = str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.environment_reset_complete) },
        failure = { error -> _cacheSize.value = context.getString(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.environment_reset_failed, error.message ?: "") }
    ) {
        requireOk(invoke("settings.cache.clear"))
        refreshSnapshot()
    }

    override fun checkForUpdates() {
        host.scope.launch {
            _updateStatus.value = str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.checking_updates)
            when (val result = updateChecker.checkForUpdates(showToast = true)) {
                is UpdateChecker.UpdateResult.UpdateAvailable -> {
                    _updateStatus.value = context.getString(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.update_available, result.latestVersion, result.currentVersion)
                    _hasUpdateAvailable.value = true
                }
                is UpdateChecker.UpdateResult.UpToDate -> {
                    _updateStatus.value = context.getString(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.up_to_date, result.currentVersion)
                    _hasUpdateAvailable.value = false
                }
                is UpdateChecker.UpdateResult.Error -> {
                    _updateStatus.value = context.getString(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.update_check_failed, result.message)
                    _hasUpdateAvailable.value = false
                }
            }
        }
    }

    override fun openGitHubRepo() = updateChecker.openGitHubRepo()
    override fun openGitHubReleases() = updateChecker.openGitHubReleases()

    override fun startFtpServer() = ftpMutation(true)
    override fun stopFtpServer() = ftpMutation(false)

    override fun saveSSHConfig(config: SSHConfig) = coreLaunch {
        requireOk(invoke("settings.ssh.save", JSONObject().put("config", sshJson(config))))
        refreshSnapshot()
    }

    override fun deleteSSHConfig() = coreLaunch {
        requireOk(invoke("settings.ssh.delete"))
        refreshSnapshot()
    }

    override fun setSSHEnabled(enabled: Boolean) {
        host.scope.launch {
            try {
                val result = invoke("settings.ssh.enable", JSONObject().put("enabled", enabled))
                if (!result.optBoolean("ok", false)) {
                    when (result.optString("code")) {
                        "SSH_TOOLS_MISSING" -> _showSshToolsMissingDialog.value = true
                        "OPENSSH_SERVER_MISSING" -> _showOpensshMissingDialog.value = true
                        else -> error("Ubuntu Core rejected SSH state change")
                    }
                    return@launch
                }
                applySnapshot(result)
            } catch (error: Throwable) { logFailure(error) }
        }
    }

    override fun setSharedTmpEnabled(enabled: Boolean) = coreLaunch {
        requireOk(invoke("settings.shared_tmp.set", JSONObject().put("enabled", enabled)))
        refreshSnapshot()
    }

    override fun setChrootEnabled(enabled: Boolean) = coreLaunch {
        requireOk(invoke("settings.chroot.set", JSONObject().put("enabled", enabled)))
        refreshSnapshot()
        if (!enabled) {
            _chrootMountStatus.value = str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.chroot_mount_status_idle)
            _chrootMountDetails.value = ""
        }
    }

    override fun inspectChrootMounts() {
        host.scope.launch {
            _isInspectingChrootMounts.value = true
            _chrootMountStatus.value = str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.chroot_mount_status_checking)
            _chrootMountDetails.value = ""
            try { updateMountDisplay(requireOk(invoke("settings.chroot.inspect"))) }
            catch (error: Throwable) { mountFailure(error) }
            finally { _isInspectingChrootMounts.value = false }
        }
    }

    override fun unmountChrootMounts() {
        host.scope.launch {
            _isUnmountingChrootMounts.value = true
            _chrootMountStatus.value = str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.chroot_mount_status_unmounting)
            try {
                val result = requireOk(invoke("settings.chroot.unmount"))
                val removed = result.optInt("removed_count")
                val remaining = result.optJSONArray("mount_points")
                _chrootMountStatus.value = if (removed > 0) {
                    context.getString(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.chroot_mount_status_unmounted, removed)
                } else {
                    str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.chroot_mount_status_none)
                }
                _chrootMountDetails.value = buildString {
                    if (remaining != null) for (i in 0 until remaining.length()) {
                        if (isNotEmpty()) append('\n')
                        append(remaining.optString(i))
                    }
                }
            } catch (error: Throwable) { mountFailure(error) }
            finally { _isUnmountingChrootMounts.value = false }
        }
    }

    override fun saveVirtualKeyboardLayout(layout: VirtualKeyboardLayoutConfig) {
        keyboard.saveLayout(layout)
        _virtualKeyboardLayout.value = keyboard.loadLayout()
    }

    private fun ftpMutation(start: Boolean) {
        host.scope.launch {
            _isManagingFtpServer.value = true
            _ftpServerStatus.value = str(if (start) com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.ftp_server_starting else com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.ftp_server_stopping_progress)
            try {
                val result = invoke(if (start) "settings.ftp.start" else "settings.ftp.stop")
                result.optJSONObject("ftp")?.let(::applyFtp)
                if (!result.optBoolean("ok", false)) {
                    _ftpServerStatus.value = str(if (start) com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.ftp_server_start_failed_env else com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.ftp_server_stop_failed)
                }
            } catch (error: Throwable) {
                _ftpServerStatus.value = if (start) context.getString(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.ftp_server_start_failed, error.message ?: "") else context.getString(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.ftp_server_stop_failed_with_error, error.message ?: "")
                logFailure(error)
            } finally { _isManagingFtpServer.value = false }
        }
    }

    private suspend fun refreshSnapshot() = applySnapshot(requireOk(invoke("settings.snapshot")))

    private fun applySnapshot(snapshot: JSONObject) {
        snapshot.optJSONObject("ftp")?.let(::applyFtp)
        _sshEnabled.value = snapshot.optBoolean("ssh_enabled")
        _sshConfig.value = snapshot.optJSONObject("ssh_config")?.let(::parseSsh)
        _sharedTmpEnabled.value = snapshot.optBoolean("shared_tmp_enabled", true)
        _chrootEnabled.value = snapshot.optBoolean("chroot_enabled", false)
        snapshot.optJSONObject("sources")?.let(::applySources)
    }

    private fun applyFtp(ftp: JSONObject) {
        _isFtpServerRunning.value = ftp.optBoolean("running")
        _ftpServerStatus.value = ftp.optString("status")
    }

    private fun applySources(root: JSONObject) {
        val mapped = linkedMapOf<PackageManagerType, SourceConfig>()
        PackageManagerType.values().forEach { manager ->
            val item = root.optJSONObject(manager.name) ?: return@forEach
            val array = item.optJSONArray("sources")
            val sources = buildList {
                if (array != null) for (index in 0 until array.length()) {
                    val source = array.getJSONObject(index)
                    add(MirrorSource(source.getString("id"), source.getString("name"), source.getString("url"), source.optBoolean("is_https")))
                }
            }
            mapped[manager] = SourceConfig(manager, item.optString("selected_source_id"), sources)
        }
        _sourceConfigs.value = mapped
    }

    private fun updateMountDisplay(result: JSONObject) {
        val count = result.optInt("count")
        val array = result.optJSONArray("mount_points")
        _chrootMountStatus.value = if (count == 0) {
            str(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.chroot_mount_status_none)
        } else {
            context.getString(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.chroot_mount_status_detected, count)
        }
        _chrootMountDetails.value = buildString {
            if (array != null) for (i in 0 until array.length()) {
                if (isNotEmpty()) append('\n')
                append(array.optString(i))
            }
        }
    }

    private fun mountFailure(error: Throwable) {
        _chrootMountStatus.value = context.getString(com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.R.string.chroot_mount_status_failed, error.message ?: "")
        logFailure(error)
    }

    private fun parseSsh(json: JSONObject): SSHConfig {
        val base = SSHConfig(
            host = json.getString("host"), port = json.optInt("port", 22), username = json.getString("username"),
            authType = SSHAuthType.valueOf(json.getString("auth_type")),
            password = json.nullableString("password"), privateKeyPath = json.nullableString("private_key_path"), passphrase = json.nullableString("passphrase"),
            enableReverseTunnel = json.optBoolean("enable_reverse_tunnel"), remoteTunnelPort = json.optInt("remote_tunnel_port", 8881), localSshPort = json.optInt("local_ssh_port", 2223)
        )
        return base.copy(
            localSshUsername = json.optString("local_ssh_username", base.localSshUsername),
            localSshPassword = json.optString("local_ssh_password", base.localSshPassword),
            enablePortForwarding = json.optBoolean("enable_port_forwarding", base.enablePortForwarding),
            localForwardPort = json.optInt("local_forward_port", base.localForwardPort),
            remoteForwardPort = json.optInt("remote_forward_port", base.remoteForwardPort),
            enableKeepAlive = json.optBoolean("enable_keep_alive", base.enableKeepAlive),
            keepAliveInterval = json.optInt("keep_alive_interval", base.keepAliveInterval)
        )
    }

    private fun sshJson(config: SSHConfig): JSONObject = JSONObject()
        .put("host", config.host).put("port", config.port).put("username", config.username).put("auth_type", config.authType.name)
        .putNullable("password", config.password).putNullable("private_key_path", config.privateKeyPath).putNullable("passphrase", config.passphrase)
        .put("enable_reverse_tunnel", config.enableReverseTunnel).put("remote_tunnel_port", config.remoteTunnelPort).put("local_ssh_port", config.localSshPort)
        .put("local_ssh_username", config.localSshUsername).put("local_ssh_password", config.localSshPassword)
        .put("enable_port_forwarding", config.enablePortForwarding).put("local_forward_port", config.localForwardPort).put("remote_forward_port", config.remoteForwardPort)
        .put("enable_keep_alive", config.enableKeepAlive).put("keep_alive_interval", config.keepAliveInterval)

    private suspend fun invoke(command: String, parameters: JSONObject = JSONObject()): JSONObject =
        JSONObject(host.invokePresentationCommand(command, parameters.toString()))

    private fun requireOk(result: JSONObject): JSONObject {
        check(result.optBoolean("ok", false)) { result.optString("error", "Ubuntu Core settings operation failed") }
        return result
    }

    private fun coreLaunch(
        before: () -> Unit = {},
        after: () -> Unit = {},
        failure: (Throwable) -> Unit = {},
        block: suspend () -> Unit
    ) {
        host.scope.launch {
            before()
            try { block(); after() } catch (error: Throwable) { failure(error); logFailure(error) }
        }
    }

    private fun logFailure(error: Throwable) {
        host.logger.w("ResidentUbuntuSettings", "Settings proxy failed: ${error.message ?: error::class.java.simpleName}")
    }

    private fun str(id: Int): String = context.getString(id)
    private fun JSONObject.nullableString(name: String): String? = if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
    private fun JSONObject.putNullable(name: String, value: String?): JSONObject = put(name, value ?: JSONObject.NULL)
}
