package com.ai.assistance.operit.core.tools.system.resident

import android.system.Os
import java.io.File
import java.io.IOException

/** Publishes the authoritative control endpoint for the current Core launch. */
internal object ResidentCoreEndpoint {
    private const val FILE_NAME = "active.socket"

    fun fallbackName(launchId: String): String {
        val suffix = launchId.filter { it.isLetterOrDigit() }.take(16)
        require(suffix.isNotBlank()) { "Invalid Core launch ID" }
        return ResidentCoreWire.socketName() + "_g_" + suffix
    }

    fun candidates(directory: File): List<String> {
        val file = File(directory, FILE_NAME)
        if (file.isFile) {
            if (file.length() !in 1L..128L) throw IOException("Invalid Core endpoint file size")
            val active = file.readText().trim()
            if (!isValid(active)) throw IOException("Invalid published Core endpoint")
            // Once published, this is the only endpoint for the current owner. A stale fixed
            // listener can still accept connections without serving them and exhaust readiness.
            return listOf(active)
        }
        if (File(directory, "launch.request").isFile) {
            // Core takes its lease before initializing and publishing the listener. Report that
            // startup window immediately; status() will retry without connecting to an old socket.
            throw IOException("Core launch is waiting for control endpoint publication")
        }
        // Existing older Core builds without endpoint publication still use the fixed address.
        return listOf(ResidentCoreWire.socketName())
    }

    fun publish(directory: File, name: String) {
        require(isValid(name)) { "Invalid Core socket endpoint" }
        check(directory.mkdirs() || directory.isDirectory) { "Core state directory is unavailable" }
        val staged = File(directory, "$FILE_NAME.tmp")
        val target = File(directory, FILE_NAME)
        staged.writeText(name)
        // Atomic replacement keeps readers from observing a missing endpoint during publication.
        Os.rename(staged.absolutePath, target.absolutePath)
    }

    fun clear(directory: File) {
        runCatching { File(directory, FILE_NAME).delete() }
    }

    fun clearIfOwned(directory: File, name: String) {
        val target = File(directory, FILE_NAME)
        runCatching {
            if (target.isFile && target.readText().trim() == name) target.delete()
        }
    }

    private fun isValid(name: String): Boolean {
        val primary = ResidentCoreWire.socketName()
        return name == primary ||
            (name.startsWith(primary + "_g_") && name.length <= 96 &&
                name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' })
    }
}
