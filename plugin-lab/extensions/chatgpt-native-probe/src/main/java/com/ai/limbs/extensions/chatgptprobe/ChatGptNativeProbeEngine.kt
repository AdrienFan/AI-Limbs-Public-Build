package com.ai.limbs.extensions.chatgptprobe

import android.os.Build
import com.ai.limbs.plugin.runtime.ChildExtensionHost
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class NativeProbeResult(
    val success: Boolean,
    val phase: String,
    val detail: String,
    val exitCode: Int? = null,
    val output: String = "",
    val binarySha256: String? = null,
    val executable: Boolean = false,
    val durationMs: Long = 0L
) {
    fun toJson(host: ChildExtensionHost): JSONObject = JSONObject()
        .put("success", success)
        .put("phase", phase)
        .put("detail", detail)
        .put("exit_code", exitCode ?: JSONObject.NULL)
        .put("output", output)
        .put("binary_sha256", binarySha256 ?: JSONObject.NULL)
        .put("executable", executable)
        .put("duration_ms", durationMs)
        .put("android_sdk", Build.VERSION.SDK_INT)
        .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        .put("host_native_runtime_api", host.nativeRuntime.apiVersion)
        .put(
            "host_native_executable_ids",
            JSONArray(host.nativeRuntime.availableExecutableIds().sorted())
        )
        .put("tunnel_client_release", ChatGptNativeProbeEngine.TUNNEL_CLIENT_RELEASE)
}

internal class ChatGptNativeProbeEngine(
    private val host: ChildExtensionHost
) {
    private val mutex = Mutex()
    private val mutableLastResult = MutableStateFlow<NativeProbeResult?>(null)

    val lastResult: StateFlow<NativeProbeResult?> = mutableLastResult

    suspend fun runProbe(): NativeProbeResult = mutex.withLock {
        val started = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
                    "This probe bundles only the official Linux ARM64 tunnel-client; device ABIs=${Build.SUPPORTED_ABIS.joinToString()}"
                }
                val binary = prepareBinary()
                val sha256 = sha256(binary)
                val executable = binary.canExecute()
                require(executable) {
                    "Extracted tunnel-client is not executable: ${binary.absolutePath}"
                }

                val process = ProcessBuilder(binary.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .start()

                val completed = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    runCatching { process.waitFor(2, TimeUnit.SECONDS) }
                    NativeProbeResult(
                        success = false,
                        phase = "TIMEOUT",
                        detail = "tunnel-client --version did not exit within ${PROBE_TIMEOUT_SECONDS}s",
                        output = runCatching {
                            process.inputStream.bufferedReader().readText().trim()
                        }.getOrDefault(""),
                        binarySha256 = sha256,
                        executable = executable,
                        durationMs = System.currentTimeMillis() - started
                    )
                } else {
                    val output = process.inputStream.bufferedReader().readText().trim()
                    val exitCode = process.exitValue()
                    NativeProbeResult(
                        success = exitCode == 0,
                        phase = if (exitCode == 0) "EXEC_OK" else "EXEC_FAILED",
                        detail = if (exitCode == 0) {
                            "Android Host executed tunnel-client directly without Ubuntu."
                        } else {
                            "tunnel-client exited with code $exitCode"
                        },
                        exitCode = exitCode,
                        output = output,
                        binarySha256 = sha256,
                        executable = executable,
                        durationMs = System.currentTimeMillis() - started
                    )
                }
            }.getOrElse { error ->
                NativeProbeResult(
                    success = false,
                    phase = "EXCEPTION",
                    detail = "${error::class.java.simpleName}: ${error.message ?: "unknown error"}",
                    executable = false,
                    durationMs = System.currentTimeMillis() - started
                )
            }
        }

        mutableLastResult.value = result
        host.logger.i(TAG, "Native probe finished: ${result.phase} ${result.detail}")
        result
    }

    fun statusJson(): JSONObject {
        val current = mutableLastResult.value
        return JSONObject()
            .put("has_result", current != null)
            .put("result", current?.toJson(host) ?: JSONObject.NULL)
    }

    private fun prepareBinary(): File {
        val root = File(host.cacheDir, "chatgpt-native-probe/$TUNNEL_CLIENT_RELEASE")
        require(root.exists() || root.mkdirs()) {
            "Could not create probe cache directory: ${root.absolutePath}"
        }

        val target = File(root, "tunnel-client")
        if (!target.isFile || target.length() == 0L) {
            val temp = File(root, ".tunnel-client.tmp")
            ZipFile(host.runtimeEntryFile).use { apk ->
                val entry = apk.getEntry(ASSET_ENTRY)
                    ?: error("Bundled tunnel-client asset is missing: $ASSET_ENTRY")
                apk.getInputStream(entry).use { input ->
                    temp.outputStream().use(input::copyTo)
                }
            }

            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }

        target.setReadable(true, true)
        target.setWritable(true, true)
        target.setExecutable(true, true)
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TUNNEL_CLIENT_RELEASE = "v0.0.15"
        private const val ASSET_ENTRY = "assets/tunnel-client-linux-arm64"
        private const val PROBE_TIMEOUT_SECONDS = 10L
        private const val TAG = "ChatGptNativeProbe"
    }
}
