package com.ai.limbs.extensions.chatgptprobe

import android.os.Build
import com.ai.limbs.plugin.runtime.ChildExtensionHost
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
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
    val binaryPath: String? = null,
    val exitCode: Int? = null,
    val output: String = "",
    val binarySha256: String? = null,
    val fileExists: Boolean = false,
    val fileSize: Long = 0L,
    val executableBefore: Boolean = false,
    val chmodReturned: Boolean = false,
    val executableAfter: Boolean = false,
    val candidateCount: Int = 0,
    val durationMs: Long = 0L
) {
    fun toJson(host: ChildExtensionHost): JSONObject = JSONObject()
        .put("success", success)
        .put("phase", phase)
        .put("detail", detail)
        .put("source_kind", "APK_NATIVE_LIBRARY_EXTRACTION")
        .put("binary_path", binaryPath ?: JSONObject.NULL)
        .put("exit_code", exitCode ?: JSONObject.NULL)
        .put("output", output)
        .put("binary_sha256", binarySha256 ?: JSONObject.NULL)
        .put("file_exists", fileExists)
        .put("file_size", fileSize)
        .put("executable_before", executableBefore)
        .put("chmod_returned", chmodReturned)
        .put("executable_after", executableAfter)
        .put("candidate_count", candidateCount)
        .put("duration_ms", durationMs)
        .put("android_sdk", Build.VERSION.SDK_INT)
        .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        .put("host_native_runtime_api", host.nativeRuntime.apiVersion)
        .put("host_native_executable_ids", JSONArray(host.nativeRuntime.availableExecutableIds().sorted()))
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
        var binary: File? = null
        var sha256: String? = null
        var executableBefore = false
        var chmodReturned = false
        var executableAfter = false
        var candidateCount = 0

        val result = withContext(Dispatchers.IO) {
            try {
                require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
                    "This probe bundles only the official Linux ARM64 tunnel-client; device ABIs=${Build.SUPPORTED_ABIS.joinToString()}"
                }

                val located = locateNativeBinary()
                binary = located.file
                candidateCount = located.candidateCount
                val target = located.file
                sha256 = sha256(target)
                executableBefore = target.canExecute()
                chmodReturned = target.setExecutable(true, false)
                executableAfter = target.canExecute()

                val process = ProcessBuilder(target.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .start()

                val completed = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    runCatching { process.waitFor(2, TimeUnit.SECONDS) }
                    NativeProbeResult(
                        success = false,
                        phase = "TIMEOUT",
                        detail = "Native-extracted tunnel-client --version did not exit within ${PROBE_TIMEOUT_SECONDS}s",
                        binaryPath = target.absolutePath,
                        output = runCatching {
                            process.inputStream.bufferedReader().readText().trim()
                        }.getOrDefault(""),
                        binarySha256 = sha256,
                        fileExists = target.isFile,
                        fileSize = target.length(),
                        executableBefore = executableBefore,
                        chmodReturned = chmodReturned,
                        executableAfter = executableAfter,
                        candidateCount = candidateCount,
                        durationMs = System.currentTimeMillis() - started
                    )
                } else {
                    val output = process.inputStream.bufferedReader().readText().trim()
                    val exitCode = process.exitValue()
                    NativeProbeResult(
                        success = exitCode == 0,
                        phase = if (exitCode == 0) "NATIVE_EXEC_OK" else "NATIVE_EXEC_FAILED",
                        detail = if (exitCode == 0) {
                            "Android Host executed tunnel-client from Child Runtime native extraction without Ubuntu."
                        } else {
                            "Native-extracted tunnel-client exited with code $exitCode"
                        },
                        binaryPath = target.absolutePath,
                        exitCode = exitCode,
                        output = output,
                        binarySha256 = sha256,
                        fileExists = target.isFile,
                        fileSize = target.length(),
                        executableBefore = executableBefore,
                        chmodReturned = chmodReturned,
                        executableAfter = executableAfter,
                        candidateCount = candidateCount,
                        durationMs = System.currentTimeMillis() - started
                    )
                }
            } catch (error: Throwable) {
                val target = binary
                NativeProbeResult(
                    success = false,
                    phase = "NATIVE_EXEC_EXCEPTION",
                    detail = "${error::class.java.simpleName}: ${error.message ?: "unknown error"}",
                    binaryPath = target?.absolutePath,
                    binarySha256 = sha256,
                    fileExists = target?.isFile == true,
                    fileSize = target?.takeIf { it.isFile }?.length() ?: 0L,
                    executableBefore = executableBefore,
                    chmodReturned = chmodReturned,
                    executableAfter = executableAfter,
                    candidateCount = candidateCount,
                    durationMs = System.currentTimeMillis() - started
                )
            }
        }

        mutableLastResult.value = result
        host.logger.i(TAG, "Native payload probe finished: ${result.phase} ${result.detail}")
        result
    }

    fun statusJson(): JSONObject {
        val current = mutableLastResult.value
        return JSONObject()
            .put("has_result", current != null)
            .put("result", current?.toJson(host) ?: JSONObject.NULL)
    }

    private data class LocatedNativeBinary(
        val file: File,
        val candidateCount: Int
    )

    private fun locateNativeBinary(): LocatedNativeBinary {
        val runtimeRoot = File(host.cacheDir, "runtime").canonicalFile
        require(runtimeRoot.isDirectory) {
            "Child Runtime native root is missing: ${runtimeRoot.absolutePath}"
        }

        val prefix = runtimeRoot.path + File.separator
        val candidates = runtimeRoot.walkTopDown()
            .filter { candidate ->
                candidate.isFile &&
                    candidate.name == NATIVE_PAYLOAD_NAME &&
                    runCatching {
                        candidate.canonicalFile.path.startsWith(prefix)
                    }.getOrDefault(false)
            }
            .map { it.canonicalFile }
            .toList()

        require(candidates.isNotEmpty()) {
            "Child Runtime did not extract $NATIVE_PAYLOAD_NAME from lib/arm64-v8a"
        }

        val selected = candidates.maxByOrNull { it.lastModified() }
            ?: error("No native payload candidate available")

        return LocatedNativeBinary(
            file = selected,
            candidateCount = candidates.size
        )
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
        private const val NATIVE_PAYLOAD_NAME = "libtunnel_client.so"
        private const val PROBE_TIMEOUT_SECONDS = 10L
        private const val TAG = "ChatGptNativeProbe"
    }
}
