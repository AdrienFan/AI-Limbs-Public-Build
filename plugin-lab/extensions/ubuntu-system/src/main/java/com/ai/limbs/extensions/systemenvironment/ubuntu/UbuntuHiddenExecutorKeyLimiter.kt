package com.ai.limbs.extensions.systemenvironment.ubuntu

/**
 * Bounds persistent hidden-shell identities inside the Ubuntu child.
 *
 * The child owns hidden executor lifecycle, so key normalization belongs here rather than in Base.
 */
internal object UbuntuHiddenExecutorKeyLimiter {
    private const val PRIMARY_SLOT = "ailimbs-hidden-primary"
    private const val SECONDARY_SLOT_PREFIX = "ailimbs-hidden-"
    private const val SECONDARY_SLOT_COUNT = 3

    fun normalize(requestedKey: String?): String {
        val requested = requestedKey.orEmpty().trim()
        return if (requested.isBlank() || requested == "default" || requested == "base") {
            PRIMARY_SLOT
        } else {
            val bucket = Math.floorMod(requested.hashCode(), SECONDARY_SLOT_COUNT)
            "$SECONDARY_SLOT_PREFIX$bucket"
        }
    }
}
