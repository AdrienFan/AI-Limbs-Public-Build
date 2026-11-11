package com.ai.limbs.plugins.permission

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class PairingStep { PAIR, CONNECT }

internal data class PairingDraft(
    val pairPort: String = "",
    val pairCode: String = "",
    val connectPort: String = "",
    val step: PairingStep = PairingStep.PAIR,
    val working: Boolean = false,
    val feedback: String = "",
    val failed: Boolean = false
) {
    val canPair: Boolean get() = pairPort.toIntOrNull()?.let { it in 1..65535 } == true && pairCode.matches(Regex("[0-9]{6}"))
    val canStart: Boolean get() = connectPort.toIntOrNull()?.let { it in 1..65535 } == true
}

/** Presentation memory shared by the page and its overlay, never persisted or snapshotted. */
internal class PairingFormState {
    private val mutableDraft = MutableStateFlow(PairingDraft())
    val draft = mutableDraft.asStateFlow()

    fun pairPort(value: String) { edit { copy(pairPort = digits(value, 5)) } }
    fun pairCode(value: String) { edit { copy(pairCode = digits(value, 6)) } }
    fun connectPort(value: String) { edit { copy(connectPort = digits(value, 5)) } }
    fun step(value: PairingStep) { edit { copy(step = value) } }
    fun clearCode() { mutableDraft.update { it.copy(pairCode = "") } }

    private fun edit(block: PairingDraft.() -> PairingDraft) {
        mutableDraft.update { if (it.working) it else it.block() }
    }

    suspend fun pair(action: suspend (Int, String) -> Unit) {
        val input = draft.value
        check(!input.working) { "当前操作尚未完成" }
        require(input.canPair) { "请输入有效配对端口和六位配对码" }
        // Consume the code before suspension: a failed request must not leave a stale secret
        // in either visible surface, and a second tap cannot submit the same code again.
        mutableDraft.value = input.copy(pairCode = "", working = true, feedback = "正在配对…", failed = false)
        try {
            action(input.pairPort.toInt(), input.pairCode)
            mutableDraft.update { it.copy(step = PairingStep.CONNECT, feedback = "配对成功。请使用无线调试主页的连接端口启动服务。") }
        } catch (error: CancellationException) {
            mutableDraft.update { it.copy(feedback = "配对操作已取消", failed = true) }
            throw error
        } catch (error: Exception) {
            mutableDraft.update { it.copy(feedback = "配对失败：" + detail(error, input.pairCode), failed = true) }
            throw error
        } finally {
            mutableDraft.update { it.copy(working = false) }
        }
    }

    suspend fun start(action: suspend (Int) -> Unit) {
        val input = draft.value
        check(!input.working) { "当前操作尚未完成" }
        require(input.canStart) { "请输入有效连接端口" }
        mutableDraft.value = input.copy(pairCode = "", working = true, feedback = "正在启动权限服务…", failed = false)
        try {
            action(input.connectPort.toInt())
            mutableDraft.update { it.copy(feedback = "权限服务已连接，可以关闭浮窗。") }
        } catch (error: CancellationException) {
            mutableDraft.update { it.copy(feedback = "启动操作已取消", failed = true) }
            throw error
        } catch (error: Exception) {
            mutableDraft.update { it.copy(feedback = "启动失败：" + detail(error), failed = true) }
            throw error
        } finally {
            mutableDraft.update { it.copy(working = false) }
        }
    }

    private fun detail(error: Exception, secret: String = ""): String {
        val message = error.message ?: error.javaClass.simpleName
        return (if (secret.isEmpty()) message else message.replace(secret, "[已隐藏]")).take(500)
    }
    private fun digits(value: String, limit: Int) = value.filter { it in '0'..'9' }.take(limit)
}
