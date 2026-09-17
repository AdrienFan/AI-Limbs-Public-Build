package com.ai.assistance.operit.core.tools.system.resident

import java.io.File

/** Publishes the current Core control socket while preserving the legacy fixed endpoint. */
internal object ResidentCoreEndpoint {
    private const val FILE_NAME = "active.socket"

    fun fallbackName(launchId: String): String {
        val suffix = launchId.filter { it.isLetterOrDigit() }.take(16)
        require(suffix.isNotBlank()) { "Invalid Core launch ID" }
        return ResidentCoreWire.socketName() + "_g_" + suffix
    }

    fun candidates(directory: File): List<String> {
        val active = runCatching {
            val file = File(directory, FILE_NAME)
            if (!file.isFile || file.length() !in 1L..128L) null
            else file.readText().trim().takeIf(::isValid)
        }.getOrNull()
        return listOfNotNull(active, ResidentCoreWire.socketName()).distinct()
    }

    fun publish(directory: File, name: String) {
        require(isValid(name)) { "Invalid Core socket endpoint" }
        check(directory.mkdirs() || directory.isDirectory) { "Core state directory is unavailable" }
        val staged = File(directory, "$FILE_NAME.tmp")
        val target = File(directory, FILE_NAME)
        staged.writeText(name)
        if (target.exists()) check(target.delete()) { "Cannot replace Core socket endpoint" }
        check(staged.renameTo(target)) { "Cannot publish Core socket endpoint" }
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
