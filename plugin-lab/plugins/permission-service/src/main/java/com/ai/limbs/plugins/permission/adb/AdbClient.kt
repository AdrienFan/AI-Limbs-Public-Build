// Adapted from RikkaApps/Shizuku (Apache-2.0); see THIRD_PARTY_NOTICES.md.
package com.ai.limbs.plugins.permission.adb

import android.util.Log
import com.ai.limbs.plugins.permission.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import com.ai.limbs.plugins.permission.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import com.ai.limbs.plugins.permission.adb.AdbProtocol.ADB_AUTH_TOKEN
import com.ai.limbs.plugins.permission.adb.AdbProtocol.A_AUTH
import com.ai.limbs.plugins.permission.adb.AdbProtocol.A_CLSE
import com.ai.limbs.plugins.permission.adb.AdbProtocol.A_CNXN
import com.ai.limbs.plugins.permission.adb.AdbProtocol.A_MAXDATA
import com.ai.limbs.plugins.permission.adb.AdbProtocol.A_OKAY
import com.ai.limbs.plugins.permission.adb.AdbProtocol.A_OPEN
import com.ai.limbs.plugins.permission.adb.AdbProtocol.A_STLS
import com.ai.limbs.plugins.permission.adb.AdbProtocol.A_STLS_VERSION
import com.ai.limbs.plugins.permission.adb.AdbProtocol.A_VERSION
import com.ai.limbs.plugins.permission.adb.AdbProtocol.A_WRTE
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"

class AdbClient(private val host: String, private val port: Int, private val key: AdbKey) : Closeable {

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false

    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    fun connect() {
        val targetPort = port
        socket = Socket().apply { connect(java.net.InetSocketAddress(host, targetPort), 10000); soTimeout = 15000 }
        socket.tcpNoDelay = true
        plainInputStream = DataInputStream(socket.getInputStream())
        plainOutputStream = DataOutputStream(socket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()
        if (message.command == A_STLS) {
            if (!(android.os.Build.VERSION.SDK_INT >= 29)) {
                error("Connect to adb with TLS is not supported before Android 9")
            }
            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = key.sslContext
            tlsSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
            tlsSocket.soTimeout = 15000
            tlsSocket.startHandshake()
            Log.d(TAG, "Handshake succeeded.")

            tlsInputStream = DataInputStream(tlsSocket.inputStream)
            tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
            useTls = true

            message = read()
        } else if (message.command == A_AUTH) {
            if (message.command != A_AUTH || message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        if (message.command != A_CNXN) error("not A_CNXN")
    }

    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        val localId = 1
        write(A_OPEN, localId, 0, "shell:$command")

        var message = read()
        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    if (message.command == A_WRTE) {
                        if (message.data_length > 0) {
                            listener?.invoke(message.data!!)
                        }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        error("not A_WRTE or A_CLSE")
                    }
                }
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
            }
            else -> {
                error("not A_OKAY or A_CLSE")
            }
        }
    }

    /** ADB SYNC SEND. The path is chosen by our controller, never supplied by external callers. */
    fun push(file: java.io.File, remotePath: String) {
        val localId = 2
        write(A_OPEN, localId, 0, "sync:")
        val opened = read()
        check(opened.command == A_OKAY) { "ADB sync open failed" }
        val remoteId = opened.arg0
        fun send(bytes: ByteArray) {
            // 4 KiB works with both legacy and current ADB negotiated maximum payloads.
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(4096, bytes.size - offset)
                write(A_WRTE, localId, remoteId, bytes.copyOfRange(offset, offset + count))
                val ack = read()
                check(ack.command == A_OKAY && ack.arg0 == remoteId && ack.arg1 == localId) { "ADB sync write failed" }
                offset += count
            }
        }
        fun packet(id: String, bytes: ByteArray) {
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put(id.toByteArray(Charsets.US_ASCII)).putInt(bytes.size).array()
            send(header + bytes)
        }
        packet("SEND", (remotePath + ",33188").toByteArray())
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                packet("DATA", buffer.copyOf(count))
            }
        }
        // DONE's second word is modification time, not payload length.
        send(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put("DONE".toByteArray()).putInt((System.currentTimeMillis() / 1000).toInt()).array())
        val result = java.io.ByteArrayOutputStream()
        while (result.size() < 8) {
            val message = read()
            check(message.command == A_WRTE && message.arg0 == remoteId) { "ADB sync response missing" }
            result.write(requireNotNull(message.data))
            write(A_OKAY, localId, remoteId)
        }
        val response = result.toByteArray()
        check(String(response, 0, 4, Charsets.US_ASCII) == "OKAY") { "ADB file transfer rejected" }
        write(A_CLSE, localId, remoteId)
        val closed = read()
        check(closed.command == A_CLSE) { "ADB sync did not close" }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        inputStream.readFully(buffer.array(), 0, 24)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data: ByteArray?
        if (dataLength in 0..(1024 * 1024)) {
            data = ByteArray(dataLength)
            inputStream.readFully(data, 0, dataLength)
        } else {
            error("ADB frame length exceeds limit")
        }
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    override fun close() {
        try {
            plainInputStream.close()
        } catch (e: Throwable) {
        }
        try {
            plainOutputStream.close()
        } catch (e: Throwable) {
        }
        try {
            socket.close()
        } catch (e: Exception) {
        }

        if (useTls) {
            try {
                tlsInputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsOutputStream.close()
            } catch (e: Throwable) {
            }
            try {
                tlsSocket.close()
            } catch (e: Exception) {
            }
        }
    }
}