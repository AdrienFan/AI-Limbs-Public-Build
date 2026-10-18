package com.ai.limbs.extensions.systemenvironment.ubuntu.runtime.terminal.data

/** Saved intent is independent of the policy latched for this runtime's processes. */
data class UbuntuNetworkState(
    val followHost: Boolean = false,
    val activeFollowHost: Boolean? = null
) {
    val restartRequired: Boolean get() = activeFollowHost != null && activeFollowHost != followHost
    val selectedFollowHost: Boolean get() = activeFollowHost ?: followHost
}
