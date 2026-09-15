package com.ai.assistance.operit.core.tools.system.resident

import android.os.IBinder
import android.os.Parcel
import org.json.JSONObject

/** Private extension of the AI Limbs permission server, separate from the Shizuku API. */
internal object ResidentPermissionWire {
    private const val TRANSACTION = 0x41494c
    private const val DESCRIPTOR = "ai_limbs.permission.resident.v1"
    private const val VERSION = 1

    fun describe(server: IBinder): JSONObject = exchange(server, 0)

    fun prepare(server: IBinder, token: String, session: String, lifetime: IBinder): JSONObject =
        exchange(server, 3, token, session, lifetime)

    fun claim(server: IBinder, token: String, session: String, lifetime: IBinder): JSONObject =
        exchange(server, 1, token, session, lifetime)

    fun release(
        server: IBinder, token: String, session: String, lifetime: IBinder,
        returnToHost: Boolean
    ): JSONObject = exchange(server, 2, token, session, lifetime, returnToHost)

    private fun exchange(
        server: IBinder, operation: Int, token: String? = null, session: String? = null,
        lifetime: IBinder? = null, returnToHost: Boolean = false
    ): JSONObject {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeInt(VERSION)
            request.writeInt(operation)
            if (operation != 0) {
                request.writeString(requireNotNull(token))
                request.writeString(requireNotNull(session))
                request.writeStrongBinder(requireNotNull(lifetime))
                if (operation == 2) request.writeInt(if (returnToHost) 1 else 0)
            }
            check(server.transact(TRANSACTION, request, reply, 0)) {
                "Permission server does not support Resident ownership protocol 1"
            }
            reply.readException()
            val result = JSONObject(requireNotNull(reply.readString()))
            check(result.getInt("protocol") == VERSION) { "Permission ownership protocol mismatch" }
            return result
        } finally {
            request.recycle()
            reply.recycle()
        }
    }
}
