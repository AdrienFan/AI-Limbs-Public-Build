package com.ai.assistance.operit.core.tools.system.resident

import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.UUID

/**
 * Minimal app-UID resident process launched through the selected AI Limbs permission backend.
 *
 * It deliberately has no Android Service, WakeLock, AlarmManager or foreground-service dependency.
 * The validation heartbeat records both elapsedRealtime and uptimeMillis so device suspend can be
 * distinguished from a process-only scheduling gap.
 */
object AiLimbsResidentMain {
    private const val HEARTBEAT_INTERVAL_MS = 60_000L
    private const val HEARTBEAT_MAX_BYTES = 128L * 1024L

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Resident state directory is required" }

        val stateDir = File(args[0])
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

        writeAtomic(
            metaFile,
            buildString {
                appendLine("pid=$pid")
                appendLine("uid=$uid")
                appendLine("session_id=$sessionId")
                appendLine("process_name=ail_resident")
                appendLine("started_wall_ms=$startedWallMs")
                appendLine("started_elapsed_ms=$startedElapsedMs")
                appendLine("started_uptime_ms=$startedUptimeMs")
                appendLine("heartbeat_interval_ms=$HEARTBEAT_INTERVAL_MS")
            }
        )

        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching {
                    val current = readKeyValues(metaFile)["pid"]?.toIntOrNull()
                    if (current == pid) metaFile.delete()
                }
            }
        )

        println("AIL_RESIDENT_READY pid=$pid uid=$uid session=$sessionId")

        var seq = 0L
        while (true) {
            seq += 1
            appendHeartbeat(
                heartbeatFile,
                "session=$sessionId seq=$seq wall_ms=${System.currentTimeMillis()} " +
                    "elapsed_ms=${SystemClock.elapsedRealtime()} uptime_ms=${SystemClock.uptimeMillis()} " +
                    "pid=$pid uid=$uid"
            )
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
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
        ) { "Could not publish resident state" }
    }

    private fun readKeyValues(file: File): Map<String, String> =
        runCatching {
            file.readLines().mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
}
