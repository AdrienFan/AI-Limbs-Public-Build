package com.ai.assistance.operit.integrations.ailimbs.providers.triggercmd

import android.content.SharedPreferences
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

internal class TriggerCmdTransportClient(
    private val preferences: SharedPreferences,
    private val listener: Listener
) {
    interface Listener {
        fun onStage(stage: String, detail: String)
        fun onComputerId(computerId: String)
        fun onSocketState(state: String)
        fun onCommand(params: String)
        fun onResult(result: String)
        fun onLog(message: String)
    }

    companion object {
        const val BASE_URL = "https://www.triggercmd.com"
        const val COMMAND_NAME = "AI Limbs Bridge"
        const val COMMAND_DESCRIPTION =
            "AI Limbs remote bridge transport. Parameters use the AIL_TRIGGER_BRIDGE protocol."

        private const val PREF_COMPUTER_ID = TriggerCmdBridgeStorage.KEY_COMPUTER_ID
        private const val PREF_COMPUTER_NAME = TriggerCmdBridgeStorage.KEY_COMPUTER_NAME
        private const val PREF_TOKEN_FINGERPRINT = TriggerCmdBridgeStorage.KEY_TOKEN_FINGERPRINT
        private const val SAILS_SDK_QUERY =
            "__sails_io_sdk_version=1.1.4&__sails_io_sdk_platform=android&__sails_io_sdk_language=kotlin"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeToken: String? = null

    @Volatile
    private var activeComputerId: String? = null

    @Volatile
    private var socket: Socket? = null

    val isRunning: Boolean
        get() = activeToken != null

    fun bindAndConnect(rawToken: String, rawComputerName: String) {
        val token = rawToken.trim()
        val computerName = rawComputerName.trim().ifBlank { "AI-Limbs-TRIGGERcmd" }
        require(token.isNotBlank()) { "TRIGGERcmd token is required" }
        activeToken = token
        listener.onStage("TOKEN", "正在验证 Token…")
        validateToken(token, computerName)
    }

    private fun validateToken(token: String, computerName: String) {
        val request = authorizedRequest(token, "$BASE_URL/api/command/list").get().build()
        executeJson(
            request = request,
            onSuccess = {
                listener.onStage("TOKEN", "Token 验证成功")
                resolveComputer(token, computerName)
            },
            onError = { fail("TOKEN", it) }
        )
    }

    private fun resolveComputer(token: String, computerName: String) {
        val fingerprint = fingerprint(token)
        val savedFingerprint = preferences.getString(PREF_TOKEN_FINGERPRINT, null)
        val savedComputerId = preferences.getString(PREF_COMPUTER_ID, null)

        if (savedFingerprint == fingerprint && !savedComputerId.isNullOrBlank()) {
            listener.onStage("COMPUTER", "检查已保存的 Computer…")
            checkComputer(token, computerName, savedComputerId, fingerprint)
        } else {
            createComputer(token, computerName, fingerprint)
        }
    }

    private fun checkComputer(
        token: String,
        computerName: String,
        computerId: String,
        fingerprint: String
    ) {
        val encoded = URLEncoder.encode(computerId, StandardCharsets.UTF_8.name())
        val url = "$BASE_URL/api/computer/list?computer_id=$encoded"
        val request = authorizedRequest(token, url).get().build()
        executeJson(
            request = request,
            onSuccess = { body ->
                val exists = body.optJSONArray("records")?.length()?.let { it > 0 } == true
                if (exists) {
                    useComputer(token, computerName, computerId, fingerprint)
                } else {
                    listener.onLog("已保存的 Computer 已不存在，重新注册。")
                    createComputer(token, computerName, fingerprint)
                }
            },
            onError = { fail("COMPUTER", it) }
        )
    }

    private fun createComputer(token: String, computerName: String, fingerprint: String) {
        listener.onStage("COMPUTER", "正在注册 Computer…")
        val body = FormBody.Builder().add("name", computerName).build()
        val request = authorizedRequest(token, "$BASE_URL/api/computer/save")
            .post(body)
            .build()
        executeJson(
            request = request,
            onSuccess = { json ->
                val computerId = json.optJSONObject("data")?.optString("id").orEmpty()
                if (computerId.isBlank()) {
                    fail("COMPUTER", "服务器未返回 Computer ID")
                } else {
                    useComputer(token, computerName, computerId, fingerprint)
                }
            },
            onError = { fail("COMPUTER", it) }
        )
    }

    private fun useComputer(
        token: String,
        computerName: String,
        computerId: String,
        fingerprint: String
    ) {
        activeComputerId = computerId
        preferences.edit()
            .putString(PREF_COMPUTER_ID, computerId)
            .putString(PREF_COMPUTER_NAME, computerName)
            .putString(PREF_TOKEN_FINGERPRINT, fingerprint)
            .apply()
        listener.onComputerId(computerId)
        listener.onStage("COMPUTER", "Computer 已就绪")
        ensureBridgeCommand(token, computerId)
    }

    private fun ensureBridgeCommand(token: String, computerId: String) {
        listener.onStage("COMMAND", "检查测试 Command…")
        val encoded = URLEncoder.encode(computerId, StandardCharsets.UTF_8.name())
        val url = "$BASE_URL/api/command/list?computer_id=$encoded"
        val request = authorizedRequest(token, url).get().build()
        executeJson(
            request = request,
            onSuccess = { json ->
                val records = json.optJSONArray("records")
                val exists = (0 until (records?.length() ?: 0)).any { index ->
                    records?.optJSONObject(index)?.optString("name") == COMMAND_NAME
                }
                if (exists) {
                    listener.onStage("COMMAND", "测试 Command 已注册")
                    connectSocket(token, computerId)
                } else {
                    registerBridgeCommand(token, computerId)
                }
            },
            onError = { fail("COMMAND", it) }
        )
    }

    private fun registerBridgeCommand(token: String, computerId: String) {
        listener.onStage("COMMAND", "正在注册测试 Command…")
        val body = FormBody.Builder()
            .add("name", COMMAND_NAME)
            .add("computer", computerId)
            .add("voice", "AI Limbs Bridge")
            .add("voiceReply", "{{result}}")
            .add("allowParams", "true")
            .add("mcpToolDescription", COMMAND_DESCRIPTION)
            .add("icon", "")
            .build()
        val request = authorizedRequest(token, "$BASE_URL/api/command/save")
            .post(body)
            .build()
        executeJson(
            request = request,
            onSuccess = {
                listener.onStage("COMMAND", "测试 Command 注册成功")
                connectSocket(token, computerId)
            },
            onError = { fail("COMMAND", it) }
        )
    }

    private fun connectSocket(token: String, computerId: String) {
        disconnectSocketOnly()
        listener.onSocketState("CONNECTING")
        listener.onStage("SOCKET", "正在连接 TRIGGERcmd Socket.IO…")
        val options = IO.Options().apply {
            forceNew = true
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 1_000
            reconnectionDelayMax = 5_000
            timeout = 12_000
            transports = arrayOf("websocket")
            query = SAILS_SDK_QUERY
        }
        val newSocket = IO.socket(BASE_URL, options)
        socket = newSocket

        newSocket.on(Socket.EVENT_CONNECT) {
            listener.onSocketState("CONNECTED")
            listener.onLog("Socket.IO connected; subscribing to Computer room.")
            subscribeRoom(newSocket, token, computerId)
        }
        newSocket.on(Socket.EVENT_MESSAGE) { args ->
            handleSocketMessage(args.firstOrNull(), token, computerId)
        }
        newSocket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            listener.onSocketState("CONNECT_ERROR")
            listener.onLog("Socket connect error (${args.size} event args)")
        }
        newSocket.on(Socket.EVENT_CONNECT_TIMEOUT) {
            listener.onSocketState("CONNECT_TIMEOUT")
        }
        newSocket.on(Socket.EVENT_DISCONNECT) { args ->
            listener.onSocketState("DISCONNECTED")
            listener.onLog("Socket disconnected (${args.size} event args)")
        }
        newSocket.on(Socket.EVENT_RECONNECT) {
            listener.onSocketState("RECONNECTED")
            listener.onLog("Socket.IO transport reconnected; waiting for connect event to subscribe once.")
        }
        newSocket.connect()
    }

    private fun subscribeRoom(socket: Socket, token: String, computerId: String) {
        listener.onStage("ROOM", "正在订阅 Computer room…")
        listener.onSocketState("SUBSCRIBING")

        val authorization = "Bearer $token"
        val headers = JSONObject().put("Authorization", authorization)
        val data = JSONObject().put("Authorization", authorization)
        val requestContext = JSONObject()
            .put("method", "get")
            .put("headers", headers)
            .put("data", data)
            .put("url", "/api/computer/subscribeToFunRoom?roomName=$computerId")

        val ack = object : Ack {
            override fun call(vararg args: Any?) {
                handleRoomSubscriptionAck(args)
            }
        }
        socket.emit("get", arrayOf<Any>(requestContext), ack)
    }

    private fun handleRoomSubscriptionAck(args: Array<out Any?>) {
        val raw = args.firstOrNull()
        val response = when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }

        if (response == null) {
            listener.onSocketState("SUBSCRIBE_ERROR")
            listener.onStage("ERROR", "Computer room 订阅返回了无法识别的 ACK")
            listener.onLog("Room subscription returned an unrecognized ACK (${args.size} values)")
            return
        }

        val statusCode = response.optInt("statusCode", -1)
        val errorStatus = response.optInt("status", -1)
        val errorCode = response.optString("code")
        val errorMessage = response.optString("message")

        if (statusCode in 200..299) {
            listener.onStage("READY", "Computer room 已确认订阅，等待远程 Ping")
            listener.onSocketState("ONLINE")
            listener.onLog("Room subscription confirmed: HTTP-like $statusCode")
            return
        }

        val statusText = when {
            errorStatus > 0 -> errorStatus.toString()
            statusCode > 0 -> statusCode.toString()
            else -> "unknown"
        }
        val detail = sanitizeForLog(
            listOf(errorCode, errorMessage)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { "服务器未返回成功状态" }
        )

        listener.onSocketState("SUBSCRIBE_ERROR")
        listener.onStage("ERROR", "Computer room 订阅失败 ($statusText)")
        listener.onLog("Room subscription rejected: $detail")
    }

    private fun handleSocketMessage(raw: Any?, token: String, computerId: String) {
        val event = when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        } ?: return

        val trigger = event.optString("trigger")
        if (trigger != COMMAND_NAME) {
            listener.onLog("Ignored trigger: $trigger")
            return
        }

        val commandId = event.optString("id")
        val params = event.optString("params")
        if (commandId.isBlank()) {
            listener.onLog("Bridge trigger arrived without command id; result cannot be correlated.")
            return
        }

        listener.onCommand(params)
        val result = when {
            params.trim().equals("ping", ignoreCase = true) -> "Pong"
            else -> JSONObject()
                .put("ok", false)
                .put("code", "BRIDGE_EXECUTOR_NOT_WIRED")
                .put("message", "TRIGGERcmd transport is online; structured AI Limbs execution is enabled in the next integration stage.")
                .toString()
        }
        sendResult(token, computerId, commandId, result)
    }

    private fun sendResult(
        token: String,
        computerId: String,
        commandId: String,
        result: String
    ) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("computer_id", computerId)
            .addFormDataPart("command_id", commandId)
            .addFormDataPart("result", result)
            .build()
        val request = authorizedRequest(token, "$BASE_URL/api/command/result")
            .post(body)
            .build()

        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                fail("RESULT", e.message ?: e.javaClass.simpleName)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        fail("RESULT", "HTTP ${it.code}")
                        return
                    }
                    listener.onResult(result)
                    listener.onStage("READY", "结果已回传；继续等待下一条请求")
                    listener.onLog("Result delivered for command id $commandId")
                }
            }
        })
    }

    fun disconnect() {
        disconnectSocketOnly()
        activeToken = null
        activeComputerId = null
        listener.onSocketState("STOPPED")
        listener.onStage("STOPPED", "已断开；Token 已从内存释放")
    }

    private fun disconnectSocketOnly() {
        socket?.disconnect()
        socket = null
    }

    private fun authorizedRequest(token: String, url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "AI-Limbs-TRIGGERcmd-Bridge/0.6.4.7.5")

    private fun executeJson(
        request: Request,
        onSuccess: (JSONObject) -> Unit,
        onError: (String) -> Unit
    ) {
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                onError(e.message ?: e.javaClass.simpleName)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        onError("HTTP ${it.code}")
                        return
                    }
                    val json = runCatching { JSONObject(raw) }.getOrElse { error ->
                        onError("Invalid JSON response: ${error.message}")
                        return
                    }
                    onSuccess(json)
                }
            }
        })
    }

    private fun fingerprint(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun sanitizeForLog(value: String): String {
        var sanitized = value.replace(Regex("(?i)Bearer\\s+[^\\s,;]+"), "Bearer [REDACTED]")
        activeToken?.takeIf { it.isNotBlank() }?.let { token ->
            sanitized = sanitized.replace(token, "[REDACTED]")
        }
        return sanitized.take(300)
    }


    private fun fail(stage: String, detail: String) {
        val safeDetail = sanitizeForLog(detail)
        listener.onStage("ERROR", "$stage: $safeDetail")
        listener.onLog("$stage failed: $safeDetail")
    }
}
