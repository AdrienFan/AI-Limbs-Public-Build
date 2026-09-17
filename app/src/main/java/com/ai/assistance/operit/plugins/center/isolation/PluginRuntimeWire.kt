package com.ai.assistance.operit.plugins.center.isolation

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import org.json.JSONObject

/**
 * Private control plane for the isolated plugin runtime process.
 *
 * This socket is deliberately not a generic capability transport. It only owns plugin-runtime
 * lifecycle/control messages. Business capability execution gets a separate attested RPC surface
 * once android_inprocess payloads are migrated out of Resident Core.
 */
internal object PluginRuntimeWire {
    const val VERSION = 1
    const val TIMEOUT_MS = 3_000
    const val BUSINESS_TIMEOUT_MS = 180_000
    private const val MAX_FRAME_BYTES = 1024 * 1024

    fun socketName(): String = "ai_limbs_plugin_runtime_" + Process.myUid()

    fun read(socket: LocalSocket): JSONObject {
        val input = DataInputStream(socket.inputStream)
        val length = input.readInt()
        require(length in 1..MAX_FRAME_BYTES)  {
            "Invalid plugin runtime frame size: $length (max=$MAX_FRAME_BYTES)"
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    fun write(socket: LocalSocket, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME_BYTES) {
            "Plugin runtime frame exceeds limit: ${bytes.size} bytes (max=$MAX_FRAME_BYTES)"
        }
        DataOutputStream(socket.outputStream).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    fun request(operation: String, sessionId: String? = null, payload: JSONObject = JSONObject(), timeoutMs: Int = TIMEOUT_MS): JSONObject {
        val requestId = UUID.randomUUID().toString()
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(socketName(), LocalSocketAddress.Namespace.ABSTRACT))
            // Android 16: set soTimeout only after connect. Keep this ordering aligned with ResidentCoreWire.
            socket.soTimeout = timeoutMs
            val peer = socket.peerCredentials
            check(peer.uid == Process.myUid()) { "Plugin runtime peer UID mismatch" }
            write(
                socket,
                JSONObject()
                    .put("protocol", VERSION)
                    .put("request_id", requestId)
                    .put("operation", operation)
                    .put("session_id", sessionId ?: JSONObject.NULL)
                    .put("payload", JSONObject(payload.toString()))
            )
            val response = read(socket)
            check(response.getInt("protocol") == VERSION) { "Plugin runtime protocol mismatch" }
            check(response.getString("request_id") == requestId) { "Plugin runtime request mismatch" }
            if (sessionId != null) {
                check(response.getString("session_id") == sessionId) { "Plugin runtime session changed" }
            }
            check(response.getBoolean("success")) { response.getString("error") }
            val result = response.getJSONObject("result")
            check(result.getInt("pid") == peer.pid) { "Plugin runtime peer PID mismatch" }
            check(result.getInt("uid") == peer.uid) { "Plugin runtime identity mismatch" }
            return result
        }
    }
}
