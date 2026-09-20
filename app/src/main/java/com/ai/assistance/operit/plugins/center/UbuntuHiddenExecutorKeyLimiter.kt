package com.ai.assistance.operit.plugins.center

import org.json.JSONObject

/**
 * Host-side circuit breaker for persistent Ubuntu hidden executors.
 *
 * The Ubuntu child intentionally keeps one shell per executor_key so repeated commands can share
 * login state. An unbounded key space therefore becomes an unbounded set of proot/bash children,
 * all of which Android counts as phantom processes. Keep the public key semantics deterministic
 * while bounding the number of child shells the Host can request.
 */
internal object UbuntuHiddenExecutorKeyLimiter {
    private const val CAPABILITY_ID = "plugin.ubuntu.command"
    private const val PRIMARY_SLOT = "ailimbs-hidden-primary"
    private const val SECONDARY_SLOT_PREFIX = "ailimbs-hidden-"
    private const val SECONDARY_SLOT_COUNT = 3

    fun normalize(capabilityId: String, parameters: JSONObject): JSONObject {
        val copy = JSONObject(parameters.toString())
        if (capabilityId.trim().lowercase() != CAPABILITY_ID) return copy

        val requested = copy.optString("executor_key").trim()
        val slot =
            if (requested.isBlank() || requested == "default" || requested == "base") {
                PRIMARY_SLOT
            } else {
                val bucket = Math.floorMod(requested.hashCode(), SECONDARY_SLOT_COUNT)
                "$SECONDARY_SLOT_PREFIX$bucket"
            }
        copy.put("executor_key", slot)
        return copy
    }
}
