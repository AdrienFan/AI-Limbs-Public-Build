package com.ai.limbs.plugins.permission

import android.content.Context
import android.os.Build
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugins.permission.adb.AdbClient
import com.ai.limbs.plugins.permission.adb.AdbKey
import com.ai.limbs.plugins.permission.adb.AdbPairingClient
import com.ai.limbs.plugins.permission.adb.PreferenceAdbKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.AtomicFile
import org.json.JSONArray
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal data class PermissionState(
    val running: Boolean = false,
    val backend: String = "",
    val uid: Int = -1,
    val busy: Boolean = false,
    val message: String = "正在读取状态",
    val statusError: String = "",
    val managementAvailable: Boolean = false,
    val managementError: String = "",
    val externalEnabled: Boolean = false,
    val pendingCount: Int = 0,
    val apps: List<ExternalApp> = emptyList()
)

private class AuthorizationPersistenceException(message: String, cause: Throwable) : IllegalStateException(message, cause)

internal class PermissionController(private val host: InProcessPluginHost) : PermissionPageController {
    private val gate = Mutex()
    private val mutableState = MutableStateFlow(PermissionState())
    override val state = mutableState.asStateFlow()
    private val key by lazy {
        AdbKey(PreferenceAdbKeyStore(
            host.applicationContext.getSharedPreferences(
                "ai_limbs.permission_service.adb.v1", Context.MODE_PRIVATE
            )
        ), "AI-Limbs@Android")
    }

    private suspend fun invoke(operation: String, params: JSONObject = JSONObject()): JSONObject {
        val result = JSONObject(host.invokeHostCapability(
            "host.privileged.runtime@1", params.put("operation", operation).toString()
        ))
        if (result.optBoolean("success", true).not()) error(result.optString("error", "Host 操作被拒绝"))
        return result
    }

    private val authorizationFile by lazy { AtomicFile(File(host.dataDir, "external_authorizations_v1.json")) }

    private suspend fun externalRpc(payload: JSONObject): JSONObject = invoke("rpc", JSONObject()
        .put("descriptor", "ai_limbs.permission.clients.v1").put("transaction", 0x414950)
        .put("payload", payload))

    override suspend fun refresh(): JSONObject = gate.withLock {
        withContext(Dispatchers.IO) { refreshStatus() }
    }

    fun reportStatusFailure(error: Exception) {
        val detail = error.message ?: error.javaClass.simpleName
        mutableState.value = mutableState.value.copy(statusError = detail, message = "连接状态读取失败，请查看日志中心",
            managementAvailable = false, managementError = "连接状态暂不可确认")
    }

    suspend fun initialize(): JSONObject = gate.withLock {
        withContext(Dispatchers.IO) { refreshStatus(initializeExternal = true) }
    }

    private suspend fun refreshStatus(initializeExternal: Boolean = false): JSONObject {
        val result = invoke("status")
        mutableState.value = mutableState.value.copy(
            running = result.getBoolean("running"), backend = result.getString("backend"),
            uid = result.getInt("uid"), statusError = "",
            message = if (result.getBoolean("running")) "权限服务已连接" else "权限服务未运行"
        )
        if (!result.getBoolean("running")) {
            mutableState.value = mutableState.value.copy(managementAvailable = false,
                managementError = "请先启动权限服务", externalEnabled = false, pendingCount = 0, apps = emptyList())
        } else if (!result.optBoolean("backend_rpc", false)) {
            mutableState.value = mutableState.value.copy(managementAvailable = false,
                managementError = "第三方授权管理需要支持服务端通信接口的新版基座")
        } else {
            try {
                var snapshot = externalRpc(JSONObject().put("operation", "snapshot"))
                require(snapshot.getInt("protocol") == 1) { "第三方授权协议版本不匹配" }
                if (initializeExternal && !snapshot.getBoolean("initialized")) {
                    val config = if (authorizationFile.baseFile.exists() || File(authorizationFile.baseFile.path + ".bak").exists()) {
                        JSONObject(String(authorizationFile.readFully(), Charsets.UTF_8))
                    } else {
                        JSONObject().put("schema", 1).put("enabled", false).put("decisions", JSONArray())
                    }
                    snapshot = externalRpc(JSONObject().put("operation", "restore").put("config", config))
                }
                if (snapshot.getBoolean("initialized")) applyExternalSnapshot(snapshot)
            } catch (error: CancellationException) { throw error }
            catch (error: AuthorizationPersistenceException) {
                host.logger.e("PermissionService", error.message ?: "Authorization persistence failed")
                mutableState.value = mutableState.value.copy(managementError = error.message ?: "授权记录保存失败")
            }
            catch (error: Exception) {
                val detail = error.message ?: error.javaClass.simpleName
                val message = if (detail.contains("Binder invocation to an incorrect interface") ||
                    detail.contains("Backend does not implement the requested protocol")) {
                    "当前运行的服务端不支持新版接口。请先停止权限服务，再使用连接端口重新启动。"
                } else {
                    "第三方授权暂不可用：$detail"
                }
                if (mutableState.value.managementError != message) host.logger.e("PermissionService", "第三方授权协议不可用：$detail")
                mutableState.value = mutableState.value.copy(managementAvailable = false,
                    managementError = message)
            }
        }
        return result.put("third_party", JSONObject()
            .put("available", state.value.managementAvailable).put("enabled", state.value.externalEnabled)
            .put("authorized", state.value.apps.count { it.decision == "allow" || it.decision == "session" })
            .put("pending", state.value.pendingCount).put("error", state.value.managementError))
    }

    private fun applyExternalSnapshot(snapshot: JSONObject) {
        val rows = snapshot.getJSONArray("apps")
        val apps = List(rows.length()) { ExternalApp.fromJson(rows.getJSONObject(it)) }
        mutableState.value = mutableState.value.copy(managementAvailable = true, managementError = "",
            externalEnabled = snapshot.getBoolean("enabled"), pendingCount = snapshot.getInt("pending"), apps = apps)
        try {
            persistAuthorizations(snapshot.getJSONObject("config"))
        } catch (error: Exception) {
            throw AuthorizationPersistenceException("授权变更已在当前服务生效，但保存授权记录失败", error)
        }
    }

    private fun persistAuthorizations(config: JSONObject) {
        val bytes = config.toString().toByteArray(Charsets.UTF_8)
        if (authorizationFile.baseFile.exists() && authorizationFile.readFully().contentEquals(bytes)) return
        val output = authorizationFile.startWrite()
        try {
            output.write(bytes)
            authorizationFile.finishWrite(output)
        } catch (error: Exception) {
            authorizationFile.failWrite(output)
            throw error
        }
    }

    override suspend fun setExternalEnabled(enabled: Boolean) = action("更新第三方接入") {
        check(state.value.managementAvailable) { state.value.managementError }
        applyExternalSnapshot(externalRpc(JSONObject().put("operation", "enable").put("enabled", enabled)))
        log(if (enabled) "第三方 App 接入已启用" else "第三方 App 接入已关闭")
    }

    override suspend fun connectExternal(packageName: String) = action("连接第三方 App") {
        check(state.value.managementAvailable) { state.value.managementError }
        applyExternalSnapshot(externalRpc(JSONObject().put("operation", "connect").put("package_name", packageName)))
    }

    override suspend fun decideExternal(uid: Int, identity: String, mode: String) = action("更新应用授权") {
        check(state.value.managementAvailable) { state.value.managementError }
        require(mode in setOf("allow", "session", "deny", "revoke")) { "未知授权决定" }
        applyExternalSnapshot(externalRpc(JSONObject().put("operation", "decide").put("uid", uid)
            .put("identity", identity).put("mode", mode)))
        log("应用授权已更新：uid=$uid，决定=$mode")
    }

    private fun log(message: String) {
        // The centralized modular Log Center is the single user-facing log surface.
        host.logger.i("PermissionService", message)
    }

    private suspend fun action(title: String, block: suspend () -> Unit) {
        gate.withLock { runAction(title, block) }
    }

    private suspend fun runAction(title: String, block: suspend () -> Unit) {
        mutableState.value = mutableState.value.copy(busy = true, message = title)
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log(title + "失败：" + (error.message ?: error.javaClass.simpleName))
            mutableState.value = mutableState.value.copy(message = title + "失败，请查看日志中心")
            throw error
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
        }
    }

    override suspend fun pair(port: Int, code: String) = action("配对") {
        require(Build.VERSION.SDK_INT >= 30) { "无线调试配对需要 Android 11 或更高版本" }
        require(port in 1..65535) { "配对端口无效" }
        require(code.matches(Regex("[0-9]{6}"))) { "请输入六位配对码" }
        invoke("pair")
        AdbPairingClient("127.0.0.1", port, code, key).use {
            check(it.start()) { "配对未成功，请检查端口和配对码是否仍有效" }
        }
        log("配对成功。请填写无线调试主页上的连接端口，然后启动。")
        mutableState.value = mutableState.value.copy(message = "配对成功")
    }

    private fun serverFile(): File {
        val directory = File(host.dataDir, "server").apply { check(mkdirs() || isDirectory) }
        val apk = File(directory, "permission-server.apk")
        val staged = File.createTempFile("permission-server-", ".part", directory)
        ZipFile(host.runtimeEntryFile).use { zip ->
            val entry = requireNotNull(zip.getEntry("assets/permission-server.apk")) { "插件缺少权限服务端文件" }
            zip.getInputStream(entry).use { input -> staged.outputStream().use { input.copyTo(it) } }
        }
        check(staged.setReadOnly())
        check(staged.renameTo(apk)) { "无法准备权限服务端文件" }
        return apk
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun launchCommand(apk: String, log: String, permit: JSONObject): String {
        val server = "/system/bin/app_process /system/bin --nice-name=ail_permission_server " +
            "com.ai.limbs.permission.server.PermissionServer " +
            listOf(permit.getString("package_name"), permit.getInt("host_uid").toString(),
                permit.getInt("user_id").toString(), permit.getString("token")).joinToString(" ") { quote(it) }
        // Legacy ADB shell uses a PTY. Ignore HUP BEFORE forking: the parent shell can
        // exit and hang up the PTY before its background child has entered setsid().
        // Changing nohup/setsid in the child alone leaves that startup race open.
        // The inner shell runs after setsid, so this marker proves session detachment.
        val detached = "echo AIL_SERVER_SESSION_READY; exec " + server
        return "trap '' HUP; CLASSPATH=" + quote(apk) +
            " /system/bin/setsid /system/bin/sh -c " + quote(detached) +
            " > " + quote(log) + " 2>&1 < /dev/null &"
    }

    private fun recordDiagnostic(diagnostic: String, permit: JSONObject) {
        val redacted = diagnostic.trim().replace(permit.getString("token"), "[redacted]")
        log(if (redacted.isEmpty()) "服务端尚未写入启动阶段日志；请检查后台进程是否成功创建"
            else "服务端启动日志：\n" + redacted)
    }

    private fun shell(client: AdbClient, command: String): String {
        val output = StringBuilder()
        client.shellCommand(command) { bytes ->
            check(output.length + bytes.size <= 65536) { "启动诊断输出过长" }
            output.append(String(bytes, Charsets.UTF_8))
        }
        return output.toString()
    }

    override suspend fun startAdb(port: Int) = action("启动权限服务") {
        require(port in 1..65535) { "连接端口无效" }
        val permit = invoke("prepare")
        val directory = "/data/local/tmp/ail-permission-" + permit.getInt("host_uid")
        val remote = "$directory/server.apk"
        val serverLog = "$directory/server.log"
        var activated = false
        try {
            val file = serverFile()
            log("正在连接本机无线调试并传送权限服务端")
            AdbClient("127.0.0.1", port, key).use { adb ->
                adb.connect()
                val prepared = shell(adb, "mkdir -p " + quote(directory) +
                    " && chmod 700 " + quote(directory) + " && rm -f " + quote("$remote.part") + " && echo AIL_READY")
                check(prepared.contains("AIL_READY")) { "无法准备 ADB 服务端目录" }
                adb.push(file, "$remote.part")
                val localHash = file.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val bytes = ByteArray(65536)
                    while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                val remoteHash = shell(adb, "sha256sum " + quote("$remote.part")).trim().substringBefore(' ')
                check(localHash == remoteHash) { "服务端传送校验失败" }
                val ready = shell(adb, "chmod 444 " + quote("$remote.part") +
                    " && mv -f " + quote("$remote.part") + " " + quote(remote) + " && echo AIL_READY")
                check(ready.contains("AIL_READY")) { "无法安装服务端文件" }
                log("ADB 已连接，服务端文件校验通过；正在创建独立后台进程")
                val launchOutput = shell(adb, launchCommand(remote, serverLog, permit))
                if (launchOutput.isNotBlank()) recordDiagnostic(launchOutput, permit)
                activated = awaitConnection()
                recordDiagnostic(shell(adb, "tail -c 12000 " + quote(serverLog)), permit)
                check(activated) { "服务端未连接到基座，请查看日志中心" }
            }
            invoke("select", JSONObject().put("backend", "ai_limbs"))
            refreshStatus(initializeExternal = true)
            log("权限服务启动成功，已选择 AI Limbs 后端")
        } finally {
            if (!activated) withContext(NonCancellable) { invoke("stop") }
        }
    }

    override suspend fun startRoot() = action("以 root 启动权限服务") {
        val permit = invoke("prepare")
        var activated = false
        try {
            val apk = serverFile()
            val serverLog = File(host.dataDir, "root-server.log")
            val command = "umask 022; " + launchCommand(apk.absolutePath, serverLog.absolutePath, permit)
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true)
                .redirectOutput(File(host.cacheDir, "root-start.log")).start()
            check(process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); "root 启动超时" }
            check(process.exitValue() == 0) { "root 授权未成功" }
            activated = awaitConnection()
            recordDiagnostic(readLogTail(serverLog), permit)
            check(activated) { "root 服务端未连接" }
            invoke("select", JSONObject().put("backend", "ai_limbs"))
            refreshStatus(initializeExternal = true)
            log("root 权限服务启动成功")
        } finally { if (!activated) withContext(NonCancellable) { invoke("stop") } }
    }

    private fun readLogTail(file: File): String =
        java.io.RandomAccessFile(file, "r").use { input ->
            val count = minOf(input.length(), 12000L).toInt()
            input.seek(input.length() - count)
            val bytes = ByteArray(count)
            input.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }

    private suspend fun awaitConnection(): Boolean {
        repeat(30) {
            if (invoke("status").getBoolean("running")) return true
            delay(500)
        }
        return false
    }

    override suspend fun stop() = action("停止权限服务") {
        invoke("stop")
        refreshStatus(initializeExternal = true)
        log("权限服务已停止")
    }

    override suspend fun select(backend: String) = action("选择执行后端") {
        invoke("select", JSONObject().put("backend", backend))
        refreshStatus(initializeExternal = true)
        log(if (backend == "ai_limbs") "已选择 AI Limbs 权限服务" else "已选择外部 Shizuku / Sui")
    }

    suspend fun close() {
        host.logger.i("PermissionService", "Plugin unmounted; Host revokes the runtime")
    }

}
