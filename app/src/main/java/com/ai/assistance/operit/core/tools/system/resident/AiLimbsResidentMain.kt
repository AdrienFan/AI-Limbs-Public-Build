package com.ai.assistance.operit.core.tools.system.resident

import android.os.Process
import android.os.SystemClock
import com.ai.assistance.operit.BuildConfig
import java.io.File
import java.util.UUID
import org.json.JSONObject

/**
 * AI Limbs lock-screen resident guardian.
 *
 * The Guardian process is only a liveness/restart helper. Continuous CPU/network ownership belongs
 * to the Resident Core session, never to the Host shell or to Guardian PID/oom metadata. Guardian
 * only verifies the Android Host shell identity and revives that UI/framework shell when needed.
 */
object AiLimbsResidentMain {
    private const val PROTOCOL_VERSION = 4
    private const val HEARTBEAT_INTERVAL_MS = 60_000L
    private const val HOST_TOUCH_HEALTHY_INTERVAL_MS = 60_000L
    private const val HOST_TOUCH_RETRY_INTERVAL_MS = 1_000L
    private const val LOOP_TICK_MS = 1_000L
    private const val HEARTBEAT_MAX_BYTES = 128L * 1024L
    private const val ACTION_RESIDENT_KEEPALIVE =
        "com.ai.assistance.operit.action.RESIDENT_KEEPALIVE"
    private const val ACTION_RESIDENT_CORE_RECOVERY =
        "com.ai.assistance.operit.action.RESIDENT_CORE_RECOVERY"
    private const val CORE_RECOVERY_RETRY_MS = 5_000L
    private const val HOST_SERVICE_CLASS =
        "com.ai.assistance.operit.api.chat.AIForegroundService"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 4) {
            "Resident state directory, package name, build code and source APK are required"
        }

        val stateDir = File(args[0])
        val packageName = args[1]
        val expectedBuildCode = args[2].toInt()
        val sourceApk = args[3]
        check(expectedBuildCode == BuildConfig.VERSION_CODE) {
            "Resident launch build does not match loaded code"
        }
        check(stateDir.mkdirs() || stateDir.isDirectory) {
            "Could not create resident state directory: ${stateDir.absolutePath}"
        }
        val guardianLease = ResidentRuntimeLease.acquire(stateDir, "guardian")

        val pid = Process.myPid()
        val uid = Process.myUid()
        val sessionId = UUID.randomUUID().toString()
        val startedWallMs = System.currentTimeMillis()
        val startedElapsedMs = SystemClock.elapsedRealtime()
        val startedUptimeMs = SystemClock.uptimeMillis()
        val metaFile = File(stateDir, "resident.meta")
        val heartbeatFile = File(stateDir, "heartbeat.log")
        val guardianFile = File(stateDir, "guardian.state")
        val hostShellStateFile = File(stateDir, "host_shell.state")
        val stopRequestFile = File(stateDir, "stop.request")

        stopRequestFile.delete()
        writeAtomic(
            metaFile,
            buildString {
                appendLine("protocol_version=$PROTOCOL_VERSION")
                appendLine("build_code=${BuildConfig.VERSION_CODE}")
                appendLine("source_apk=$sourceApk")
                appendLine("pid=$pid")
                appendLine("uid=$uid")
                appendLine("session_id=$sessionId")
                appendLine("process_name=ail_resident")
                appendLine("package_name=$packageName")
                appendLine("started_wall_ms=$startedWallMs")
                appendLine("started_elapsed_ms=$startedElapsedMs")
                appendLine("started_uptime_ms=$startedUptimeMs")
                appendLine("heartbeat_interval_ms=$HEARTBEAT_INTERVAL_MS")
                appendLine("host_touch_healthy_interval_ms=$HOST_TOUCH_HEALTHY_INTERVAL_MS")
                appendLine("host_touch_retry_interval_ms=$HOST_TOUCH_RETRY_INTERVAL_MS")
            }
        )

        var hostShellAlive = false
        var hostShellPid: Int? = null
        var hostShellDetail = "not_reported"
        var hostTouchSeq = 0L
        var lastHostTouchWallMs = 0L
        var lastHostTouchExit = -1
        var lastHostTouchOk = false
        var lastHostTouchDetail = "not_started"
        var lastCoreRecoveryPid: Int? = null
        var lastCoreRecoveryWallMs = 0L
        var lastCoreRecoveryExit = -1
        var lastCoreRecoveryOk = false
        var lastCoreRecoveryDetail = "not_requested"
        var nextCoreRecoveryElapsed = 0L

        fun refreshHostShellState(): Boolean {
            val probe = probeHostShell(hostShellStateFile, packageName)
            hostShellAlive = probe.alive
            hostShellPid = probe.pid
            hostShellDetail = probe.detail
            return probe.alive
        }

        fun publishGuardianState(state: String) {
            writeAtomic(
                guardianFile,
                buildString {
                    appendLine("state=$state")
                    appendLine("build_code=${BuildConfig.VERSION_CODE}")
                    appendLine("source_apk=${sanitize(sourceApk)}")
                    appendLine("host_shell_alive=$hostShellAlive")
                    appendLine("host_pid=${hostShellPid ?: -1}")
                    appendLine("host_shell_detail=${sanitize(hostShellDetail)}")
                    appendLine("host_guardian=active")
                    appendLine("host_touch_seq=$hostTouchSeq")
                    appendLine("last_host_touch_wall_ms=$lastHostTouchWallMs")
                    appendLine("last_host_touch_exit=$lastHostTouchExit")
                    appendLine("last_host_touch_ok=$lastHostTouchOk")
                    appendLine("last_host_touch_detail=${sanitize(lastHostTouchDetail)}")
                    appendLine("core_recovery_pid=${lastCoreRecoveryPid ?: -1}")
                    appendLine("last_core_recovery_wall_ms=$lastCoreRecoveryWallMs")
                    appendLine("last_core_recovery_exit=$lastCoreRecoveryExit")
                    appendLine("last_core_recovery_ok=$lastCoreRecoveryOk")
                    appendLine("last_core_recovery_detail=${sanitize(lastCoreRecoveryDetail)}")
                }
            )
        }

        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching {
                    val current = readKeyValues(metaFile)["pid"]?.toIntOrNull()
                    if (current == pid) metaFile.delete()
                }
            }
        )

        println(
            "AIL_RESIDENT_READY pid=$pid uid=$uid session=$sessionId protocol=$PROTOCOL_VERSION " +
                "build=${BuildConfig.VERSION_CODE}"
        )

        try {
            var heartbeatSeq = 0L
            var nextHeartbeatElapsed = SystemClock.elapsedRealtime()
            var nextHostTouchElapsed = 0L

            while (!stopRequestFile.exists()) {
                val nowElapsed = SystemClock.elapsedRealtime()
                val hostHealthy = refreshHostShellState()

                if (!hostHealthy || nowElapsed >= nextHostTouchElapsed) {
                    hostTouchSeq += 1
                    val result = touchHost(packageName)
                    lastHostTouchWallMs = System.currentTimeMillis()
                    lastHostTouchExit = result.exitCode
                    lastHostTouchOk = result.ok
                    lastHostTouchDetail = result.detail

                    val shellHealthyAfterTouch = refreshHostShellState()
                    publishGuardianState(
                        if (result.ok && shellHealthyAfterTouch) "running" else "degraded"
                    )
                    nextHostTouchElapsed =
                        nowElapsed +
                            if (result.ok && shellHealthyAfterTouch) {
                                HOST_TOUCH_HEALTHY_INTERVAL_MS
                            } else {
                                HOST_TOUCH_RETRY_INTERVAL_MS
                            }
                }

                val crashedCorePid = crashedOwnedCorePid(stateDir)
                if (crashedCorePid == null) {
                    lastCoreRecoveryPid = null
                    nextCoreRecoveryElapsed = 0L
                } else if (lastCoreRecoveryPid != crashedCorePid || nowElapsed >= nextCoreRecoveryElapsed) {
                    lastCoreRecoveryPid = crashedCorePid
                    val recovery = requestCoreRecovery(packageName)
                    lastCoreRecoveryWallMs = System.currentTimeMillis()
                    lastCoreRecoveryExit = recovery.exitCode
                    lastCoreRecoveryOk = recovery.ok
                    lastCoreRecoveryDetail = recovery.detail
                    publishGuardianState(if (recovery.ok) "recovering_core" else "degraded")
                    nextCoreRecoveryElapsed = nowElapsed + CORE_RECOVERY_RETRY_MS
                }

                if (nowElapsed >= nextHeartbeatElapsed) {
                    heartbeatSeq += 1
                    appendHeartbeat(
                        heartbeatFile,
                        "session=$sessionId seq=$heartbeatSeq wall_ms=${System.currentTimeMillis()} " +
                            "elapsed_ms=${SystemClock.elapsedRealtime()} uptime_ms=${SystemClock.uptimeMillis()} " +
                            "pid=$pid uid=$uid host_shell_alive=$hostShellAlive host_pid=${hostShellPid ?: -1} " +
                            "host_touch_ok=$lastHostTouchOk"
                    )
                    nextHeartbeatElapsed = nowElapsed + HEARTBEAT_INTERVAL_MS
                }

                try {
                    Thread.sleep(LOOP_TICK_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } finally {
            refreshHostShellState()
            publishGuardianState("stopped")
            stopRequestFile.delete()
            val current = readKeyValues(metaFile)["pid"]?.toIntOrNull()
            if (current == pid) metaFile.delete()
            guardianLease.close()
        }
    }

    private fun touchHost(packageName: String): CommandResult =
        sendHostAction(packageName, ACTION_RESIDENT_KEEPALIVE)

    private fun requestCoreRecovery(packageName: String): CommandResult =
        sendHostAction(packageName, ACTION_RESIDENT_CORE_RECOVERY)

    private fun sendHostAction(packageName: String, action: String): CommandResult {
        val component = "$packageName/$HOST_SERVICE_CLASS"
        return runCommand(
            listOf(
                "/system/bin/am",
                "startservice",
                "--user",
                "0",
                "-a",
                action,
                "-n",
                component
            )
        )
    }

    private fun crashedOwnedCorePid(stateDir: File): Int? {
        val fenceFile = File(stateDir.parentFile, "runtime_owner/business_takeover.json")
        val fence = runCatching {
            if (!fenceFile.isFile || fenceFile.length() !in 1L..4096L) null
            else JSONObject(fenceFile.readText())
        }.getOrNull() ?: return null
        if (fence.optString("state") != "owned") return null
        val corePid = fence.optInt("core_pid", -1)
        if (corePid <= 0 || ResidentProcessLiveness.exists(corePid)) return null
        return corePid
    }

    private fun probeHostShell(file: File, packageName: String): HostShellProbe {
        val state = readKeyValues(file)
        val declaredRunning = state["state"] == "running"
        val pid = state["pid"]?.toIntOrNull()

        if (!declaredRunning || pid == null || pid <= 0) {
            return HostShellProbe(false, pid, "host shell state is not running")
        }

        val procDir = File("/proc/$pid")
        if (!procDir.exists()) {
            return HostShellProbe(false, pid, "host shell pid is gone")
        }

        val statusUid =
            runCatching {
                File(procDir, "status")
                    .readLines()
                    .firstOrNull { it.startsWith("Uid:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.firstOrNull()
                    ?.toIntOrNull()
            }.getOrNull()
        if (statusUid != null && statusUid != Process.myUid()) {
            return HostShellProbe(false, pid, "host shell uid mismatch: $statusUid")
        }

        val cmdline =
            runCatching {
                File(procDir, "cmdline").readText().replace('\u0000', ' ').trim()
            }.getOrNull()
        if (!cmdline.isNullOrBlank() && packageName !in cmdline) {
            return HostShellProbe(false, pid, "host shell command mismatch")
        }

        val detail =
            buildString {
                append("host shell alive")
                state["role"]?.takeIf { it.isNotBlank() }?.let { append(" role=$it") }
                state["detail"]?.takeIf { it.isNotBlank() }?.let { append(" detail=$it") }
            }
        return HostShellProbe(true, pid, detail)
    }

    private fun runCommand(command: List<String>): CommandResult {
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim().take(1200)
            val exitCode = process.waitFor()
            val looksFailed =
                output.contains("Exception occurred", ignoreCase = true) ||
                    output.contains("SecurityException", ignoreCase = true) ||
                    output.lineSequence().any {
                        it.trimStart().startsWith("Error:", ignoreCase = true)
                    }
            CommandResult(
                ok = exitCode == 0 && !looksFailed,
                exitCode = exitCode,
                detail = output.ifBlank { "exit=$exitCode" }
            )
        } catch (error: Throwable) {
            CommandResult(false, -1, "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun appendHeartbeat(file: File, line: String) {
        if (file.length() >= HEARTBEAT_MAX_BYTES) {
            val previous = File(file.parentFile, "heartbeat.log.1")
            previous.delete()
            file.renameTo(previous)
        }
        file.appendText(line + "\n")
    }

    private fun writeAtomic(file: File, content: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(content)
        check(
            temp.renameTo(file) || run {
                file.delete()
                temp.renameTo(file)
            }
        ) { "Could not publish resident state: ${file.name}" }
    }

    private fun readKeyValues(file: File): Map<String, String> =
        runCatching {
            file.readLines().mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }.toMap()
        }.getOrDefault(emptyMap())

    private fun sanitize(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').take(1200)

    private data class HostShellProbe(
        val alive: Boolean,
        val pid: Int?,
        val detail: String
    )

    private data class CommandResult(
        val ok: Boolean,
        val exitCode: Int,
        val detail: String
    )
}
