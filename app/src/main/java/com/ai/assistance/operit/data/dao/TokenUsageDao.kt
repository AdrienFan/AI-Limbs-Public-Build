package com.ai.assistance.operit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.assistance.operit.data.model.TokenStatsModelEntity
import com.ai.assistance.operit.data.model.TokenUsageRecordEntity

data class TokenUsageModelAggregateRow(
    val provider: String,
    val model: String,
    val configId: String?,
    /** Exact for request and released-total rows; a lower bound when conversation rows contribute. */
    val requests: Long,
    val requestCountKnown: Long,
    val usageRows: Long,
    val uncachedInputTokens: Long,
    val uncachedInputKnown: Long,
    val cachedInputTokens: Long,
    val cachedInputKnown: Long,
    val cacheWriteTokens: Long,
    val cacheWriteKnown: Long,
    val totalInputTokens: Long,
    val totalInputKnown: Long,
    val outputTokens: Long,
    val outputKnown: Long,
    val reasoningTokens: Long,
    val reasoningKnown: Long,
    val ttftTotalMs: Long,
    val ttftSamples: Long,
    val durationTotalMs: Long,
    val durationSamples: Long,
) {
    val providerModel: String
        get() = "$provider:$model"
}

data class TokenUsageBreakdownRow(
    val key: String,
    val provider: String,
    val model: String,
    val configId: String?,
    val requests: Long,
    val requestCountKnown: Long,
    val usageRows: Long,
    val uncachedInputTokens: Long,
    val uncachedInputKnown: Long,
    val cachedInputTokens: Long,
    val cachedInputKnown: Long,
    val cacheWriteTokens: Long,
    val cacheWriteKnown: Long,
    val totalInputTokens: Long,
    val totalInputKnown: Long,
    val outputTokens: Long,
    val outputKnown: Long,
    val reasoningTokens: Long,
    val reasoningKnown: Long,
    val ttftTotalMs: Long,
    val ttftSamples: Long,
    val durationTotalMs: Long,
    val durationSamples: Long,
) {
    val providerModel: String
        get() = "$provider:$model"
}

data class TokenUsageIdentityRow(
    val configId: String?,
    val provider: String,
    val model: String,
)

data class TokenUsageActivityDayRow(
    val localDate: String,
    val configId: String?,
    val provider: String,
    val model: String,
    val tokens: Long,
)

@Dao
abstract class TokenUsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertRecord(record: TokenUsageRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertRecords(records: List<TokenUsageRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertStatsModel(model: TokenStatsModelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertStatsModels(models: List<TokenStatsModelEntity>)

    @Query(
        """
        SELECT * FROM token_stats_models
        WHERE configId = :configId AND provider = :provider AND model = :model
        """
    )
    abstract suspend fun getStatsModel(
        configId: String,
        provider: String,
        model: String,
    ): TokenStatsModelEntity?

    @Query("SELECT * FROM token_stats_models ORDER BY provider, model, configId")
    abstract suspend fun getAllStatsModels(): List<TokenStatsModelEntity>

    @Query(
        """
        UPDATE token_stats_models
        SET billingMode = NULL,
            currency = NULL,
            inputPricePerMillion = NULL,
            cachedInputPricePerMillion = NULL,
            cacheWritePricePerMillion = NULL,
            outputPricePerMillion = NULL,
            pricePerRequest = NULL
        WHERE configId = :configId AND provider = :provider AND model = :model
        """
    )
    abstract suspend fun clearPricing(configId: String, provider: String, model: String): Int

    @Query(
        """
        DELETE FROM token_stats_models
        WHERE billingMode IS NULL
            AND currency IS NULL
            AND inputPricePerMillion IS NULL
            AND cachedInputPricePerMillion IS NULL
            AND cacheWritePricePerMillion IS NULL
            AND outputPricePerMillion IS NULL
            AND pricePerRequest IS NULL
        """
    )
    abstract suspend fun deleteEmptyStatsModels(): Int

    @Query(
        """
        SELECT
            provider AS provider,
            model AS model,
            configId AS configId,
            COALESCE(SUM(COALESCE(requestCount, 1)), 0) AS requests,
            COUNT(requestCount) AS requestCountKnown,
            COUNT(*) AS usageRows,
            COALESCE(SUM(uncachedInputTokens), 0) AS uncachedInputTokens,
            COUNT(uncachedInputTokens) AS uncachedInputKnown,
            COALESCE(SUM(cachedInputTokens), 0) AS cachedInputTokens,
            COUNT(cachedInputTokens) AS cachedInputKnown,
            COALESCE(SUM(cacheWriteTokens), 0) AS cacheWriteTokens,
            COUNT(cacheWriteTokens) AS cacheWriteKnown,
            COALESCE(SUM(totalInputTokens), 0) AS totalInputTokens,
            COUNT(totalInputTokens) AS totalInputKnown,
            COALESCE(SUM(outputTokens), 0) AS outputTokens,
            COUNT(outputTokens) AS outputKnown,
            COALESCE(SUM(reasoningTokens), 0) AS reasoningTokens,
            COUNT(reasoningTokens) AS reasoningKnown,
            COALESCE(SUM(ttftMs), 0) AS ttftTotalMs,
            COUNT(ttftMs) AS ttftSamples,
            COALESCE(SUM(durationMs), 0) AS durationTotalMs,
            COUNT(durationMs) AS durationSamples
        FROM token_usage_records
        WHERE source = 'REQUEST'
            AND (:allModels OR (provider || ':' || model) IN (:providerModels))
            AND (:allCategories OR category IN (:categories))
            AND (:allStatuses OR status IN (:statuses))
        GROUP BY provider, model, configId
        ORDER BY provider, model, configId
        """
    )
    abstract suspend fun aggregateRequestModelsForLifetime(
        providerModels: List<String>,
        allModels: Boolean,
        categories: List<String>,
        allCategories: Boolean,
        statuses: List<String>,
        allStatuses: Boolean,
    ): List<TokenUsageModelAggregateRow>

    @Query(
        """
        SELECT
            provider AS provider,
            model AS model,
            configId AS configId,
            COALESCE(SUM(COALESCE(requestCount, 1)), 0) AS requests,
            COUNT(requestCount) AS requestCountKnown,
            COUNT(*) AS usageRows,
            COALESCE(SUM(uncachedInputTokens), 0) AS uncachedInputTokens,
            COUNT(uncachedInputTokens) AS uncachedInputKnown,
            COALESCE(SUM(cachedInputTokens), 0) AS cachedInputTokens,
            COUNT(cachedInputTokens) AS cachedInputKnown,
            COALESCE(SUM(cacheWriteTokens), 0) AS cacheWriteTokens,
            COUNT(cacheWriteTokens) AS cacheWriteKnown,
            COALESCE(SUM(totalInputTokens), 0) AS totalInputTokens,
            COUNT(totalInputTokens) AS totalInputKnown,
            COALESCE(SUM(outputTokens), 0) AS outputTokens,
            COUNT(outputTokens) AS outputKnown,
            COALESCE(SUM(reasoningTokens), 0) AS reasoningTokens,
            COUNT(reasoningTokens) AS reasoningKnown,
            COALESCE(SUM(ttftMs), 0) AS ttftTotalMs,
            COUNT(ttftMs) AS ttftSamples,
            COALESCE(SUM(durationMs), 0) AS durationTotalMs,
            COUNT(durationMs) AS durationSamples
        FROM token_usage_records
        WHERE source IN ('REQUEST', 'CONVERSATION')
            AND occurredAtMs >= :startMs AND occurredAtMs < :endMs
            AND (:allModels OR (provider || ':' || model) IN (:providerModels))
            AND (:allCategories OR category IN (:categories))
            AND (:allStatuses OR status IN (:statuses))
        GROUP BY provider, model, configId
        ORDER BY provider, model, configId
        """
    )
    abstract suspend fun aggregateModelsInRange(
        startMs: Long,
        endMs: Long,
        providerModels: List<String>,
        allModels: Boolean,
        categories: List<String>,
        allCategories: Boolean,
        statuses: List<String>,
        allStatuses: Boolean,
    ): List<TokenUsageModelAggregateRow>

    @Query(
        """
        SELECT
            category AS `key`,
            provider AS provider,
            model AS model,
            configId AS configId,
            COALESCE(SUM(COALESCE(requestCount, 1)), 0) AS requests,
            COUNT(requestCount) AS requestCountKnown,
            COUNT(*) AS usageRows,
            COALESCE(SUM(uncachedInputTokens), 0) AS uncachedInputTokens,
            COUNT(uncachedInputTokens) AS uncachedInputKnown,
            COALESCE(SUM(cachedInputTokens), 0) AS cachedInputTokens,
            COUNT(cachedInputTokens) AS cachedInputKnown,
            COALESCE(SUM(cacheWriteTokens), 0) AS cacheWriteTokens,
            COUNT(cacheWriteTokens) AS cacheWriteKnown,
            COALESCE(SUM(totalInputTokens), 0) AS totalInputTokens,
            COUNT(totalInputTokens) AS totalInputKnown,
            COALESCE(SUM(outputTokens), 0) AS outputTokens,
            COUNT(outputTokens) AS outputKnown,
            COALESCE(SUM(reasoningTokens), 0) AS reasoningTokens,
            COUNT(reasoningTokens) AS reasoningKnown,
            COALESCE(SUM(ttftMs), 0) AS ttftTotalMs,
            COUNT(ttftMs) AS ttftSamples,
            COALESCE(SUM(durationMs), 0) AS durationTotalMs,
            COUNT(durationMs) AS durationSamples
        FROM token_usage_records
        WHERE source IN ('REQUEST', 'CONVERSATION')
            AND occurredAtMs >= :startMs AND occurredAtMs < :endMs
            AND (:allModels OR (provider || ':' || model) IN (:providerModels))
            AND (:allCategories OR category IN (:categories))
            AND (:allStatuses OR status IN (:statuses))
        GROUP BY category, provider, model, configId
        ORDER BY category, provider, model, configId
        """
    )
    abstract suspend fun aggregateCategoriesInRange(
        startMs: Long,
        endMs: Long,
        providerModels: List<String>,
        allModels: Boolean,
        categories: List<String>,
        allCategories: Boolean,
        statuses: List<String>,
        allStatuses: Boolean,
    ): List<TokenUsageBreakdownRow>

    @Query(
        """
        SELECT
            status AS `key`,
            provider AS provider,
            model AS model,
            configId AS configId,
            COALESCE(SUM(COALESCE(requestCount, 1)), 0) AS requests,
            COUNT(requestCount) AS requestCountKnown,
            COUNT(*) AS usageRows,
            COALESCE(SUM(uncachedInputTokens), 0) AS uncachedInputTokens,
            COUNT(uncachedInputTokens) AS uncachedInputKnown,
            COALESCE(SUM(cachedInputTokens), 0) AS cachedInputTokens,
            COUNT(cachedInputTokens) AS cachedInputKnown,
            COALESCE(SUM(cacheWriteTokens), 0) AS cacheWriteTokens,
            COUNT(cacheWriteTokens) AS cacheWriteKnown,
            COALESCE(SUM(totalInputTokens), 0) AS totalInputTokens,
            COUNT(totalInputTokens) AS totalInputKnown,
            COALESCE(SUM(outputTokens), 0) AS outputTokens,
            COUNT(outputTokens) AS outputKnown,
            COALESCE(SUM(reasoningTokens), 0) AS reasoningTokens,
            COUNT(reasoningTokens) AS reasoningKnown,
            COALESCE(SUM(ttftMs), 0) AS ttftTotalMs,
            COUNT(ttftMs) AS ttftSamples,
            COALESCE(SUM(durationMs), 0) AS durationTotalMs,
            COUNT(durationMs) AS durationSamples
        FROM token_usage_records
        WHERE source IN ('REQUEST', 'CONVERSATION')
            AND occurredAtMs >= :startMs AND occurredAtMs < :endMs
            AND (:allModels OR (provider || ':' || model) IN (:providerModels))
            AND (:allCategories OR category IN (:categories))
            AND (:allStatuses OR status IN (:statuses))
        GROUP BY status, provider, model, configId
        ORDER BY status, provider, model, configId
        """
    )
    abstract suspend fun aggregateStatusesInRange(
        startMs: Long,
        endMs: Long,
        providerModels: List<String>,
        allModels: Boolean,
        categories: List<String>,
        allCategories: Boolean,
        statuses: List<String>,
        allStatuses: Boolean,
    ): List<TokenUsageBreakdownRow>

    @Query(
        """
        SELECT configId, provider, model
        FROM token_usage_records
        GROUP BY configId, provider, model
        ORDER BY provider, model, configId
        """
    )
    abstract suspend fun getObservedIdentities(): List<TokenUsageIdentityRow>

    @Query(
        """
        SELECT DISTINCT provider || ':' || model
        FROM token_usage_records
        ORDER BY 1
        """
    )
    abstract suspend fun getObservedProviderModels(): List<String>

    @Query(
        """
        SELECT
            strftime('%Y-%m-%d', occurredAtMs / 1000, 'unixepoch', 'localtime') AS localDate,
            configId AS configId,
            provider AS provider,
            model AS model,
            COALESCE(SUM(
                COALESCE(
                    totalInputTokens,
                    CASE
                        WHEN uncachedInputTokens IS NOT NULL
                            AND cachedInputTokens IS NOT NULL
                            AND cacheWriteTokens IS NOT NULL
                        THEN uncachedInputTokens + cachedInputTokens + cacheWriteTokens
                    END,
                    0
                ) + COALESCE(outputTokens, 0)
            ), 0) AS tokens
        FROM token_usage_records
        WHERE source IN ('REQUEST', 'CONVERSATION')
            AND occurredAtMs >= :startMs AND occurredAtMs < :endMs
            AND (:allModels OR (provider || ':' || model) IN (:providerModels))
            AND (:allCategories OR category IN (:categories))
            AND (:allStatuses OR status IN (:statuses))
        GROUP BY localDate, configId, provider, model
        ORDER BY localDate, provider, model, configId
        """
    )
    abstract suspend fun getActivityDaysInRange(
        startMs: Long,
        endMs: Long,
        providerModels: List<String>,
        allModels: Boolean,
        categories: List<String>,
        allCategories: Boolean,
        statuses: List<String>,
        allStatuses: Boolean,
    ): List<TokenUsageActivityDayRow>

}
