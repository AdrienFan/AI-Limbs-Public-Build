package com.ailimbs.freecessprobe

data class ProbeState(
    val phase: String = "STOPPED",
    val detail: String = "未启动",
    val deviceId: String? = null,
    val userCode: String? = null,
    val verificationUri: String? = null,
    val socketSinceMs: Long? = null,
    val lastHeartbeatAtMs: Long? = null,
    val wakeLockHeld: Boolean = false,
    val lastSuspendDeltaMs: Long? = null,
    val activeFgsTypesHex: String = "0x0"
)
