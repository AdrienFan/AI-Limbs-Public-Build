package com.ailimbs.freecessprobe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ProbeRuntime {
    private val _state = MutableStateFlow(ProbeState())
    val state: StateFlow<ProbeState> = _state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    fun update(transform: (ProbeState) -> ProbeState) {
        _state.value = transform(_state.value)
    }

    fun addLog(line: String) {
        _logs.value = (_logs.value + line).takeLast(200)
    }

    fun clearLogs() {
        _logs.value = emptyList()
        ProbeLog.clearFile()
    }
}
