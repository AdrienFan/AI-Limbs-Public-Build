package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.PricingCurrency

data class TokenStatsQueryParams(
    val targetCurrency: PricingCurrency = PricingCurrency.CNY,
    val manualRate: Double = TokenCostCurrency.DEFAULT_USD_TO_CNY_RATE,
    val providerModels: Set<String>? = null,
    val categories: Set<TokenStatCategory>? = null,
    val statuses: Set<TokenStatStatus>? = null,
)

data class TokenStatsCostSummary(
    val currency: PricingCurrency,
    val knownAmount: Double,
    val unknownContributionCount: Long,
    val totalContributionCount: Long,
    val rateUsed: Double,
    val originalCurrencyAmounts: Map<PricingCurrency, Double>,
)

data class TokenStatsTokenAggregate(
    val knownSum: Long,
    val knownEventCount: Long,
    val unknownEventCount: Long,
    val totalEventCount: Long,
) {
    val isFullyKnown: Boolean get() = unknownEventCount == 0L
}

data class TokenStatsDurationAggregate(
    val knownCount: Long,
    val unknownCount: Long,
    val totalMs: Long,
    val averageMs: Double,
) {
    val hasData: Boolean get() = knownCount > 0L
}

data class TokenStatsPerformance(
    val ttft: TokenStatsDurationAggregate,
    val generationDuration: TokenStatsDurationAggregate,
)

data class TokenStatsTotals(
    val requests: Long,
    val requestCountUnknownContributionCount: Long,
    val uncachedInput: TokenStatsTokenAggregate,
    val cachedInput: TokenStatsTokenAggregate,
    val cacheWrite: TokenStatsTokenAggregate,
    val totalInput: TokenStatsTokenAggregate,
    val output: TokenStatsTokenAggregate,
    val reasoning: TokenStatsTokenAggregate,
    val totalTokens: TokenStatsTokenAggregate,
    val cost: TokenStatsCostSummary,
)

data class TokenStatsLifetimeOverview(
    val totals: TokenStatsTotals,
    val displayModels: List<TokenStatsDisplayModelBreakdown>,
)

data class TokenStatsTrendBucket(
    val bucketStartMs: Long,
    val bucketEndMs: Long,
    val totals: TokenStatsTotals,
    val byModel: Map<String, TokenStatsModelBucket>,
    val performance: TokenStatsPerformance,
)

data class TokenStatsModelBucket(
    val requests: Long,
    val requestCountUnknownContributionCount: Long,
    val uncachedInput: Long,
    val cachedInput: Long,
    val cacheWrite: Long,
    val output: Long,
    val reasoning: Long,
    val totalTokens: Long,
    val totalTokensUnknownEventCount: Long,
    val unknownTokenEventCount: Long,
    val cost: TokenStatsCostSummary,
)

data class TokenStatsIdentityBreakdown(
    val configId: String?,
    val provider: String,
    val model: String,
    val totals: TokenStatsTotals,
)

data class TokenStatsDisplayModelBreakdown(
    val displayModelId: String,
    val displayName: String,
    val normalizedModel: String,
    val totals: TokenStatsTotals,
    val identities: List<TokenStatsIdentityBreakdown>,
    val providerModels: List<String>,
)

data class TokenStatsCategoryBreakdown(
    val category: TokenStatCategory,
    val totals: TokenStatsTotals,
)

data class TokenStatsStatusBreakdown(
    val status: TokenStatStatus,
    val totals: TokenStatsTotals,
)

data class TokenStatsRangeData(
    val range: TokenStatsTimeRange,
    val granularity: TokenStatsGranularity,
    val eventCount: Long,
    val summary: TokenStatsTotals,
    val performance: TokenStatsPerformance,
    val buckets: List<TokenStatsTrendBucket>,
    val displayModels: List<TokenStatsDisplayModelBreakdown>,
    val categories: List<TokenStatsCategoryBreakdown>,
    val statuses: List<TokenStatsStatusBreakdown>,
)
