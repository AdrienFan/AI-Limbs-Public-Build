package com.ai.assistance.operit.integrations.ailimbs

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.BuildConfig
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Publishes the installed AI Limbs base identity for Ubuntu-side source tooling.
 *
 * The sole BUSINESS owner calls this after acquiring plugin_kernel ownership, so Legacy Host and
 * Resident Core never maintain competing runtime-version authorities.
 */
internal object AiLimbsRuntimeMetadataPublisher {
    private const val RELATIVE_DIR = "ai_limbs/runtime_owner"
    private const val FILE_NAME = "runtime_metadata.json"

    fun publish(context: Context): Boolean {
        val appContext = context.applicationContext
        val directory = File(appContext.filesDir, RELATIVE_DIR)
        if (!directory.exists() && !directory.mkdirs()) return false

        val target = File(directory, FILE_NAME)
        val existing =
            runCatching {
                if (target.isFile) JSONObject(target.readText()) else null
            }.getOrNull()
        if (
            existing?.optString("package_name") == BuildConfig.APPLICATION_ID &&
                existing.optString("version_name") == BuildConfig.VERSION_NAME &&
                existing.optLong("version_code", Long.MIN_VALUE) == BuildConfig.VERSION_CODE.toLong()
        ) {
            return true
        }

        val payload =
            JSONObject()
                .put("schema_version", 1)
                .put("package_name", BuildConfig.APPLICATION_ID)
                .put("version_name", BuildConfig.VERSION_NAME)
                .put("version_code", BuildConfig.VERSION_CODE)
                .put("updated_wall_ms", System.currentTimeMillis())
                .toString() + "\n"

        val atomicFile = AtomicFile(target)
        var stream = runCatching { atomicFile.startWrite() }.getOrNull() ?: return false
        return try {
            stream.write(payload.toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(stream)
            true
        } catch (_: Throwable) {
            atomicFile.failWrite(stream)
            false
        }
    }
}
