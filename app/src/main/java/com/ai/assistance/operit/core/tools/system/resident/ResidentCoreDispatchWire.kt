package com.ai.assistance.operit.core.tools.system.resident

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import org.json.JSONObject

/**
 * Dedicated, bounded IPC for the Resident Core policy/Dispatcher plane.
 *
 * This is deliberately separate from [ResidentCoreWire]: lifecycle/status/stop must stay responsive
 * while a tool call is running. Every request is same-UID, session-bound and still passes through
 * the normal AI Limbs Interaction Cycle, Policy Engine and Dispatcher inside Resident Core.
 */
internal object ResidentCoreDispatchWire {
    const val VERSION = 1
    const val TIMEOUT_MS = 180_000
    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    fun socketName(): String = "ai_limbs_core_dispatch_" + Process.myUid()

    fun read(socket: LocalSocket): JSONObject {
        val input = DataInputStream(socket.inputStream)
        val length = input.readInt()
        require(length in 1..MAX_FRAME_BYTES) { "Invalid Resident Dispatcher frame size: $length" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    fun write(socket: LocalSocket, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME_BYTES) {
            "Resident Dispatcher frame exceeds ${MAX_FRAME_BYTES} bytes"
        }
        DataOutputStream(socket.outputStream).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    /** Client-side request. Unlike ResidentCoreWire, structured dispatch failures are returned. */
    fun request(coreSessionId: String, operation: String, payload: JSONObject = JSONObject()): JSONObject {
        require(coreSessionId.length in 1..64) { "Invalid Resident Core session id" }
        require(operation.length in 1..64) { "Invalid Resident Dispatcher operation" }
        val requestId = UUID.randomUUID().toString()
        LocalSocket().use { socket ->
            // Android 16 requires the socket to be created/connected before SO_TIMEOUT is applied.
            socket.connect(LocalSocketAddress(socketName(), LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = TIMEOUT_MS
            val peer = socket.peerCredentials
            check(peer.uid == Process.myUid()) { "Resident Dispatcher peer UID mismatch" }
            write(
                socket,
                JSONObject()
                    .put("protocol", VERSION)
                    .put("request_id", requestId)
                    .put("session_id", coreSessionId)
                    .put("operation", operation)
                    .put("payload", payload)
            )
            val response = read(socket)
            check(response.getInt("protocol") == VERSION) { "Resident Dispatcher protocol mismatch" }
            check(response.getString("request_id") == requestId) { "Resident Dispatcher request mismatch" }
            check(response.getString("session_id") == coreSessionId) { "Resident Dispatcher session changed" }
            check(response.getInt("core_pid") == peer.pid) { "Resident Dispatcher peer PID mismatch" }
            check(response.getInt("core_uid") == peer.uid) { "Resident Dispatcher peer identity mismatch" }
            return response
        }
    }
}
