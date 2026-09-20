package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import java.io.File
import java.io.FileOutputStream

internal object ResidentDesiredStateStore {
    private const val ON = "on"
    private const val OFF = "off"

    fun read(context: Context, fallback: Boolean): Boolean {
        val file = stateFile(context)
        if (!file.isFile) return fallback
        return when (runCatching { file.readText().trim() }.getOrNull()) {
            ON -> true
            OFF -> false
            else -> fallback
        }
    }

    fun ensureMigrated(context: Context, fallback: Boolean) {
        if (stateFile(context).isFile) return
        write(context, fallback)
    }

    fun write(context: Context, enabled: Boolean) {
        val file = stateFile(context)
        val directory = checkNotNull(file.parentFile)
        check(directory.mkdirs() || directory.isDirectory) { "Could not prepare Resident control directory" }
        val staged = File(directory, file.name + ".tmp")
        FileOutputStream(staged, false).use { output ->
            output.write((if (enabled) ON else OFF).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(staged.renameTo(file) || run {
            file.delete()
            staged.renameTo(file)
        }) { "Could not atomically persist Resident desired state" }
    }

    private fun stateFile(context: Context): File =
        File(context.applicationContext.filesDir, "ai_limbs/resident_control/desired_state")
}
