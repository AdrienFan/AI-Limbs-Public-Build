package com.ai.limbs.extensions.systemenvironment.ubuntu

import android.content.Context
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.TerminalManager
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.MirrorSource
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.PackageManagerType
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.SSHAuthType
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.SSHConfig
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.utils.CacheManager
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.utils.FtpServerManager
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.utils.SSHConfigManager
import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.utils.SourceManager
import com.ai.limbs.plugin.runtime.ChildPresentationCommandHandler
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Core-owned control surface used only by the matching Resident Host presentation. */
internal class UbuntuPresentationCommandHandler(
    private val context: Context,
    private val terminal: TerminalManager
) : ChildPresentationCommandHandler {
    private val cacheManager = CacheManager(context)
    private val ftpServerManager = FtpServerManager.getInstance(context)
    private val sourceManager = SourceManager(context)
    private val sshConfigManager = SSHConfigManager(context)
    private val prefs = context.getSharedPreferences("terminal_settings", Context.MODE_PRIVATE)

    override suspend fun invoke(command: String, parametersJson: String): String {
        val parameters = if (parametersJson.isBlank()) JSONObject() else JSONObject(parametersJson)
        val result = when (command) {
            "settings.snapshot" -> snapshot()
            "settings.cache.size" -> cacheSize()
            "settings.cache.clear" -> clearCache()
            "settings.ftp.start" -> ftpStart()
            "settings.ftp.stop" -> ftpStop()
            "settings.ssh.save" -> sshSave(parameters)
            "settings.ssh.delete" -> sshDelete()
            "settings.ssh.enable" -> sshEnable(parameters)
            "settings.source.select" -> sourceSelect(parameters)
            "settings.source.add" -> sourceAdd(parameters)
            "settings.source.delete" -> sourceDelete(parameters)
            "settings.shared_tmp.set" -> sharedTmpSet(parameters)
            "settings.chroot.set" -> chrootSet(parameters)
            "settings.chroot.inspect" -> chrootInspect()
            "settings.chroot.unmount" -> chrootUnmount()
            else -> error("Unsupported Ubuntu presentation command: $command")
        }
        return result.toString()
    }

    private suspend fun snapshot(): JSONObject {
        val ssh = sshConfigManager.getConfig()
        return ok()
            .put("ftp", ftpSnapshot())
            .put("sources", sourceSnapshot())
            .put("ssh_enabled", sshConfigManager.isEnabled())
            .put("ssh_config", ssh?.let(::sshJson) ?: JSONObject.NULL)
            .put("ssh_tools_installed", areSshToolsInstalled())
            .put("openssh_server_installed", isOpenSshServerInstalled())
            .put("shared_tmp_enabled", prefs.getBoolean("shared_tmp_enabled", true))
            .put("chroot_enabled", prefs.getBoolean("chroot_enabled", false))
    }

    private suspend fun cacheSize(): JSONObject {
        val bytes = cacheManager.getCacheSize { }
        return ok()
            .put("bytes", bytes)
            .put("formatted", cacheManager.formatSize(bytes))
    }

    private suspend fun clearCache(): JSONObject {
        if (ftpServerManager.isFtpServerRunning()) ftpServerManager.stopFtpServer()
        cacheManager.clearCache(terminal)
        return ok()
    }

    private suspend fun ftpStart(): JSONObject {
        val started = ftpServerManager.startFtpServer()
        return JSONObject()
            .put("ok", started)
            .put("ftp", ftpSnapshot())
    }

    private suspend fun ftpStop(): JSONObject {
        val stopped = ftpServerManager.stopFtpServer()
        return JSONObject()
            .put("ok", stopped)
            .put("ftp", ftpSnapshot())
    }

    private fun ftpSnapshot(): JSONObject = JSONObject()
        .put("running", ftpServerManager.isFtpServerRunning())
        .put("status", ftpServerManager.getFtpServerInfo())

    private suspend fun sshSave(parameters: JSONObject): JSONObject {
        val configJson = parameters.optJSONObject("config") ?: error("SSH config is required")
        sshConfigManager.saveConfig(parseSshConfig(configJson))
        return snapshot()
    }

    private suspend fun sshDelete(): JSONObject {
        sshConfigManager.deleteConfig()
        sshConfigManager.setEnabled(false)
        return snapshot()
    }

    private suspend fun sshEnable(parameters: JSONObject): JSONObject {
        val enabled = parameters.getBoolean("enabled")
        if (enabled && !areSshToolsInstalled()) {
            return JSONObject().put("ok", false).put("code", "SSH_TOOLS_MISSING")
        }
        val config = sshConfigManager.getConfig()
        if (enabled && config?.enableReverseTunnel == true && !isOpenSshServerInstalled()) {
            return JSONObject().put("ok", false).put("code", "OPENSSH_SERVER_MISSING")
        }
        sshConfigManager.setEnabled(enabled)
        return snapshot()
    }

    private fun sourceSelect(parameters: JSONObject): JSONObject {
        val manager = packageManager(parameters)
        val sourceId = parameters.getString("source_id").trim()
        require(sources(manager).any { it.id == sourceId }) { "Unknown source for ${manager.name}: $sourceId" }
        sourceManager.setSelectedSourceId(manager, sourceId)
        return ok().put("sources", sourceSnapshot())
    }

    private fun sourceAdd(parameters: JSONObject): JSONObject {
        val manager = packageManager(parameters)
        val name = parameters.getString("name").trim()
        val url = parameters.getString("url").trim()
        require(name.isNotBlank() && url.isNotBlank()) { "Source name and URL are required" }
        val source = MirrorSource(
            id = "custom_${manager.name.lowercase()}_${System.currentTimeMillis()}",
            name = name,
            url = url,
            isHttps = parameters.optBoolean("is_https", url.startsWith("https://", ignoreCase = true))
        )
        sourceManager.saveCustomSource(manager, source)
        return ok().put("sources", sourceSnapshot())
    }

    private fun sourceDelete(parameters: JSONObject): JSONObject {
        val manager = packageManager(parameters)
        val sourceId = parameters.getString("source_id").trim()
        require(sourceId.startsWith("custom_")) { "Built-in sources cannot be deleted" }
        sourceManager.deleteCustomSource(manager, sourceId)
        if (sourceManager.getSelectedSourceId(manager) == sourceId) {
            sourceManager.setSelectedSourceId(manager, fallbackSourceId(manager))
        }
        return ok().put("sources", sourceSnapshot())
    }

    private fun sharedTmpSet(parameters: JSONObject): JSONObject {
        prefs.edit().putBoolean("shared_tmp_enabled", parameters.getBoolean("enabled")).apply()
        return ok().put("shared_tmp_enabled", prefs.getBoolean("shared_tmp_enabled", true))
    }

    private fun chrootSet(parameters: JSONObject): JSONObject {
        prefs.edit().putBoolean("chroot_enabled", parameters.getBoolean("enabled")).apply()
        return ok().put("chroot_enabled", prefs.getBoolean("chroot_enabled", false))
    }

    private suspend fun chrootInspect(): JSONObject {
        val result = cacheManager.inspectUbuntuMounts()
        return ok()
            .put("count", result.count)
            .put("root_path", result.rootPath)
            .put("mount_points", JSONArray(result.mountPoints))
    }

    private suspend fun chrootUnmount(): JSONObject {
        val removed = cacheManager.unmountUbuntuMounts(terminal)
        val remaining = cacheManager.inspectUbuntuMounts()
        return ok()
            .put("removed_count", removed)
            .put("remaining_count", remaining.count)
            .put("mount_points", JSONArray(remaining.mountPoints))
    }

    private fun sourceSnapshot(): JSONObject = JSONObject().apply {
        PackageManagerType.values().forEach { manager ->
            put(manager.name, JSONObject()
                .put("selected_source_id", sourceManager.getSelectedSourceId(manager))
                .put("sources", JSONArray().apply {
                    sources(manager).forEach { source ->
                        put(JSONObject()
                            .put("id", source.id)
                            .put("name", source.name)
                            .put("url", source.url)
                            .put("is_https", source.isHttps)
                            .put("custom", source.id.startsWith("custom_")))
                    }
                }))
        }
    }

    private fun packageManager(parameters: JSONObject): PackageManagerType =
        PackageManagerType.valueOf(parameters.getString("package_manager").trim().uppercase())

    private fun sources(manager: PackageManagerType): List<MirrorSource> = when (manager) {
        PackageManagerType.APT -> sourceManager.aptSources
        PackageManagerType.PIP -> sourceManager.pipSources
        PackageManagerType.NPM -> sourceManager.npmSources
        PackageManagerType.RUST -> sourceManager.rustSources
    }

    private fun fallbackSourceId(manager: PackageManagerType): String = when (manager) {
        PackageManagerType.APT -> "tuna_apt"
        PackageManagerType.PIP -> "tuna_pip"
        PackageManagerType.NPM -> "taobao_npm"
        PackageManagerType.RUST -> "ustc_rust"
    }

    private fun areSshToolsInstalled(): Boolean {
        val root = ubuntuRoot()
        return File(root, "usr/bin/ssh").isFile && File(root, "usr/bin/sshpass").isFile
    }

    private fun isOpenSshServerInstalled(): Boolean = File(ubuntuRoot(), "usr/sbin/sshd").isFile

    private fun ubuntuRoot(): File =
        File(context.filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu")

    private fun parseSshConfig(json: JSONObject): SSHConfig {
        val base = SSHConfig(
            host = json.getString("host"),
            port = json.optInt("port", 22),
            username = json.getString("username"),
            authType = SSHAuthType.valueOf(json.getString("auth_type").uppercase()),
            password = json.nullableString("password"),
            privateKeyPath = json.nullableString("private_key_path"),
            passphrase = json.nullableString("passphrase"),
            enableReverseTunnel = json.optBoolean("enable_reverse_tunnel", false),
            remoteTunnelPort = json.optInt("remote_tunnel_port", 8881),
            localSshPort = json.optInt("local_ssh_port", 2223)
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
        .put("host", config.host)
        .put("port", config.port)
        .put("username", config.username)
        .put("auth_type", config.authType.name)
        .putNullable("password", config.password)
        .putNullable("private_key_path", config.privateKeyPath)
        .putNullable("passphrase", config.passphrase)
        .put("enable_reverse_tunnel", config.enableReverseTunnel)
        .put("remote_tunnel_port", config.remoteTunnelPort)
        .put("local_ssh_port", config.localSshPort)
        .put("local_ssh_username", config.localSshUsername)
        .put("local_ssh_password", config.localSshPassword)
        .put("enable_port_forwarding", config.enablePortForwarding)
        .put("local_forward_port", config.localForwardPort)
        .put("remote_forward_port", config.remoteForwardPort)
        .put("enable_keep_alive", config.enableKeepAlive)
        .put("keep_alive_interval", config.keepAliveInterval)

    private fun ok(): JSONObject = JSONObject().put("ok", true)

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.putNullable(name: String, value: String?): JSONObject =
        put(name, value ?: JSONObject.NULL)
}
