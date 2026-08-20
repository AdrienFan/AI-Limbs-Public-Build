package com.ai.assistance.operit.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A token usage fact. Imported cumulative counters have no occurrence time. */
@Entity(
    tableName = "token_usage_records",
    indices = [
        Index(value = ["occurredAtMs"]),
        Index(value = ["provider", "model", "configId", "occurredAtMs"]),
        Index(value = ["source", "occurredAtMs"]),
        Index(value = ["category", "status", "occurredAtMs"]),
        Index(value = ["importKey"], unique = true),
    ],
)
data class TokenUsageRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Stable only for one-time imported totals; normal request and conversation rows use null. */
    val importKey: String? = null,
    val occurredAtMs: Long?,
    val source: String,
    val configId: String?,
    val provider: String,
    val model: String,
    val category: String?,
    val status: String?,
    /** Null means a conversation record proves usage but not the exact provider-call count. */
    val requestCount: Long?,
    val uncachedInputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val totalInputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val ttftMs: Long? = null,
    val durationMs: Long? = null,
) {
    val providerModel: String
        get() = "$provider:$model"
}

object TokenUsageRecordSource {
    const val REQUEST = "REQUEST"
    const val CONVERSATION = "CONVERSATION"
}
