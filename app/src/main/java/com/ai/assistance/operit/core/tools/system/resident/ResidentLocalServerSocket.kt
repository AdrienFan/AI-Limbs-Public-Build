package com.ai.assistance.operit.core.tools.system.resident

import android.net.LocalServerSocket
import android.os.Build
import android.system.Os
import android.system.OsConstants

/** Opens a local listener that cannot leak through exec() on supported Android releases. */
internal object ResidentLocalServerSocket {
    fun bind(name: String): LocalServerSocket {
        val server = LocalServerSocket(name)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val current = Os.fcntlInt(server.fileDescriptor, OsConstants.F_GETFD, 0)
                if ((current and OsConstants.FD_CLOEXEC) == 0) {
                    Os.fcntlInt(
                        server.fileDescriptor,
                        OsConstants.F_SETFD,
                        current or OsConstants.FD_CLOEXEC
                    )
                }
                val verified = Os.fcntlInt(server.fileDescriptor, OsConstants.F_GETFD, 0)
                check((verified and OsConstants.FD_CLOEXEC) != 0) {
                    "Local server socket is not close-on-exec: $name"
                }
            }
            return server
        } catch (error: Throwable) {
            runCatching { server.close() }
            throw error
        }
    }
}
