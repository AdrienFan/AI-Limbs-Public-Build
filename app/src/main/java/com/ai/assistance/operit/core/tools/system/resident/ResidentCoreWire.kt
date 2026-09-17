package com.ai.assistance.operit.core.tools.system.resident

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import org.json.JSONObject

/** Private, bounded diagnostic protocol. It never dispatches plugin or shell commands. */
internal object ResidentCoreWire {
    const val VERSION = 1
    const val TIMEOUT_MS = 3_000
    // Resident status now includes bounded runtime diagnostics for Kernel/Bridge/plugin/Ubuntu ownership.
    // The original 8 KiB ceiling was sized for the pre-migration bootstrap skeleton and can make a
    // healthy Core look unreachable once BUSINESS state is attached. Keep this control plane bounded
    // while leaving ample headroom; it remains far below UI_PROXY (4 MiB) and Dispatcher (8 MiB).
    private const val MAX_FRAME_BYTES = 256 * 1024

    fun socketName(): String = "ai_limbs_core_" + Process.myUid()

    fun read(socket: LocalSocket): JSONObject {
        val input = DataInputStream(socket.inputStream)
        val length = input.readInt()
        require(length in 1..MAX_FRAME_BYTES) { "Invalid core frame size: $length (max=$MAX_FRAME_BYTES)" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    fun write(socket: LocalSocket, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME_BYTES) { "Core frame exceeds limit: ${bytes.size} bytes (max=$MAX_FRAME_BYTES)" }
        DataOutputStream(socket.outputStream).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    fun request(operation: String, sessionId: String? = null, socketNames: List<String> = listOf(socketName())): JSONObject {
        var lastError: IOException? = null
        for (name in socketNames.distinct()) {
            try { return requestAt(name, operation, sessionId) }
            catch (error: IOException) { lastError = error }
        }
        throw checkNotNull(lastError) { "No Core socket endpoint was attempted" }
    }

    private fun requestAt(name: String, operation: String, sessionId: String? = null): JSONObject {
        val requestId = UUID.randomUUID().toString()
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = TIMEOUT_MS
            val peer = socket.peerCredentials
            check(peer.uid == Process.myUid()) { "Core peer UID mismatch" }
            write(socket, JSONObject()
                .put("protocol", VERSION)
                .put("request_id", requestId)
                .put("operation", operation)
                .put("session_id", sessionId ?: JSONObject.NULL))
            val response = read(socket)
            check(response.getInt("protocol") == VERSION) { "Core protocol mismatch" }
            check(response.getString("request_id") == requestId) { "Core request mismatch" }
            if (sessionId != null) {
                check(response.getString("session_id") == sessionId) { "Core session changed" }
            }
            check(response.getBoolean("success")) { response.getString("error") }
            val result = response.getJSONObject("result")
            check(result.getInt("pid") == peer.pid) { "Core peer PID mismatch" }
            check(result.getInt("uid") == peer.uid) { "Core identity mismatch" }
            return result
        }
    }
}
