package com.ai.limbs.extensions.systemenvironment.ubuntu

import com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data.UbuntuNetworkState
import org.json.JSONObject

internal fun UbuntuNetworkState.toJson(): JSONObject = JSONObject()
    .put("follow_host", followHost)
    .put("active_follow_host", activeFollowHost ?: JSONObject.NULL)
    .put("restart_required", restartRequired)

internal fun readUbuntuNetworkState(value: JSONObject): UbuntuNetworkState = UbuntuNetworkState(
    followHost = value.getBoolean("follow_host"),
    activeFollowHost = if (value.isNull("active_follow_host")) null else value.getBoolean("active_follow_host")
)
