package com.ai.assistance.operit.core.tools.system.resident

import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.UUID

/**
 * AI Limbs lock-screen resident guardian.
 *
 * The Resident process is born outside the normal AMS app cgroup. It does not fake a wake lock
 * through `cmd power`; instead the normal AI Limbs foreground service owns a real Android
 * PARTIAL_WAKE_LOCK. Resident continuously verifies that host lease and immediately revives the
 * host when the process or lease disappears.
 */
object AiLimbsResidentMain {
    private const val PROTOCOL_VERSION = 3
    private const val HEARTBEAT_INTERVAL_MS = 60_000L
    private const val HOST_TOUCH_HEALTHY_INTERVAL_MS = 60_000L
    private const val HOST_TOUCH_RETRY_INTERVAL_MS = 1_000L
    private const val LOOP_TICK_MS = 1_000L
    private const val HEARTBEAT_MAX_BYTES = 128L * 1024L
    private const val ACTION_RESIDENT_KEEPALIVE =
        "com.ai.assistance.operit.action.RESIDENT_KEEPALIVE"
    private const val HOST_SERVICE_CLASS =
        "com.ai.assistance.operit.api.chat.AIForegroundService"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { "Resident state directory and package name are required" }

        val stateDir = File(args[0])
        val packageName = args[1]
        check(stateDir.mkdirs() || stateDir.isDirectory) {
            "Could not create resident state directory: ${stateDir.absolutePath}"
        }

        val pid = Process.myPid()
        val uid = Process.myUid()
        val sessionId = UUID.randomUUID().toString()
        val startedWallMs = System.currentTimeMillis()
        val startedElapsedMs = SystemClock.elapsedRealtime()
        val startedUptimeMs = SystemClock.uptimeMillis()
        val metaFile = File(stateDir, "resident.meta")
        val heartbeatFile = File(stateDir, "heartbeat.log")
        val guardianFile = File(stateDir, "guardian.state")
        val hostWakeLockFile = File(stateDir, "host_wake_lock.state")
        val stopRequestFile = File(stateDir, "stop.request")

        stopRequestFile.delete()
        writeAtomic(
            metaFile,
            buildString {
                appendLine("protocol_version=$PROTOCOL_VERSION")
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

        var hostWakeHeld = false
        var hostWakePid: Int? = null
        var hostWakeDetail = "not_reported"
        var hostTouchSeq = 0L
        var lastHostTouchWallMs = 0L
        var lastHostTouchExit = -1
        var lastHostTouchOk = false
        var lastHostTouchDetail = "not_started"

        fun refreshHostWakeState(): Boolean {
            val probe = probeHostWakeLock(hostWakeLockFile, packageName)
            hostWakeHeld = probe.held
            hostWakePid = probe.pid
            hostWakeDetail = probe.detail
            return probe.held
        }

        fun publishGuardianState(state: String) {
            writeAtomic(
                guardianFile,
                buildString {
                    appendLine("state=$state")
                    appendLine("wake_lock=${if (hostWakeHeld) "held" else "not_held"}")
                    appendLine("wake_lock_backend=host_service_power_manager")
                    appendLine("wake_lock_detail=${sanitize(hostWakeDetail)}")
                    appendLine("host_pid=${hostWakePid ?: -1}")
                    appendLine("host_guardian=active")
                    appendLine("host_touch_seq=$hostTouchSeq")
                    appendLine("last_host_touch_wall_ms=$lastHostTouchWallMs")
                    appendLine("last_host_touch_exit=$lastHostTouchExit")
                    appendLine("last_host_touch_ok=$lastHostTouchOk")
                    appendLine("last_host_touch_detail=${sanitize(lastHostTouchDetail)}")
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

        println("AIL_RESIDENT_READY pid=$pid uid=$uid session=$sessionId protocol=$PROTOCOL_VERSION")

        try {
            var heartbeatSeq = 0L
            var nextHeartbeatElapsed = SystemClock.elapsedRealtime()
            var nextHostTouchElapsed = 0L

            while (!stopRequestFile.exists()) {
                val nowElapsed = SystemClock.elapsedRealtime()
                val hostHealthy = refreshHostWakeState()

                if (!hostHealthy || nowElapsed >= nextHostTouchElapsed) {
                    hostTouchSeq += 1
                    val result = touchHost(packageName)
                    lastHostTouchWallMs = System.currentTimeMillis()
                    lastHostTouchExit = result.exitCode
                    lastHostTouchOk = result.ok
                    lastHostTouchDetail = result.detail

                    val wakeHealthyAfterTouch = refreshHostWakeState()
                    publishGuardianState(
                        if (result.ok && wakeHealthyAfterTouch) "running" else "degraded"
                    )
                    nextHostTouchElapsed =
                        nowElapsed +
                            if (result.ok && wakeHealthyAfterTouch) {
                                HOST_TOUCH_HEALTHY_INTERVAL_MS
                            } else {
                                HOST_TOUCH_RETRY_INTERVAL_MS
                            }
                }

                if (nowElapsed >= nextHeartbeatElapsed) {
                    heartbeatSeq += 1
                    appendHeartbeat(
                        heartbeatFile,
                        "session=$sessionId seq=$heartbeatSeq wall_ms=${System.currentTimeMillis()} " +
                            "elapsed_ms=${SystemClock.elapsedRealtime()} uptime_ms=${SystemClock.uptimeMillis()} " +
                            "pid=$pid uid=$uid wake_lock=$hostWakeHeld host_pid=${hostWakePid ?: -1} " +
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
            refreshHostWakeState()
            publishGuardianState("stopped")
            stopRequestFile.delete()
            val current = readKeyValues(metaFile)["pid"]?.toIntOrNull()
            if (current == pid) metaFile.delete()
        }
    }

    private fun touchHost(packageName: String): CommandResult {
        val component = "$packageName/$HOST_SERVICE_CLASS"
        return runCommand(
            listOf(
                "/system/bin/am",
                "startservice",
                "--user",
                "0",
                "-a",
                ACTION_RESIDENT_KEEPALIVE,
                "-n",
                component
            )
        )
    }

    private fun probeHostWakeLock(file: File, packageName: String): HostWakeProbe {
        val state = readKeyValues(file)
        val declaredHeld = state["held"] == "true"
        val pid = state["pid"]?.toIntOrNull()

        if (!declaredHeld || pid == null || pid <= 0) {
            return HostWakeProbe(false, pid, "host lease not held")
        }

        val procDir = File("/proc/$pid")
        if (!procDir.exists()) {
            return HostWakeProbe(false, pid, "host pid is gone")
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
            return HostWakeProbe(false, pid, "host pid uid mismatch: $statusUid")
        }

        val cmdline =
            runCatching {
                File(procDir, "cmdline").readText().replace('\u0000', ' ').trim()
            }.getOrNull()
        if (!cmdline.isNullOrBlank() && packageName !in cmdline) {
            return HostWakeProbe(false, pid, "host pid command mismatch")
        }

        val detail =
            buildString {
                append("host PowerManager lease held")
                state["tag"]?.takeIf { it.isNotBlank() }?.let { append(" tag=$it") }
                state["reason"]?.takeIf { it.isNotBlank() }?.let { append(" reason=$it") }
            }
        return HostWakeProbe(true, pid, detail)
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

    private data class HostWakeProbe(
        val held: Boolean,
        val pid: Int?,
        val detail: String
    )

    private data class CommandResult(
        val ok: Boolean,
        val exitCode: Int,
        val detail: String
    )
}
