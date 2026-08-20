package com.ai.assistance.operit.data.model

data class TokenUsageIdentity(
    val configId: String?,
    val provider: String,
    val model: String,
) {
    fun encode(): String = if (configId == null) {
        listOf(UNSCOPED_PREFIX, provider, model).joinToString(SEPARATOR.toString())
    } else {
        listOf(CONFIG_PREFIX, configId, provider, model).joinToString(SEPARATOR.toString())
    }

    companion object {
        private const val CONFIG_PREFIX = "config"
        private const val UNSCOPED_PREFIX = "unscoped"
        private const val SEPARATOR = '\u001f'

        fun decode(value: String): TokenUsageIdentity {
            val parts = value.split(SEPARATOR)
            return when {
                parts.size == 3 && parts[0] == UNSCOPED_PREFIX ->
                    TokenUsageIdentity(null, parts[1], parts[2])
                parts.size == 4 && parts[0] == CONFIG_PREFIX ->
                    TokenUsageIdentity(parts[1], parts[2], parts[3])
                else -> throw IllegalArgumentException("invalid token usage identity")
            }
        }
    }
}
