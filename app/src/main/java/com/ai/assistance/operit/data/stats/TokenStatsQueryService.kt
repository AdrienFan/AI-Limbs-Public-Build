package com.ai.assistance.operit.data.stats

import android.content.Context
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.dao.TokenUsageBreakdownRow
import com.ai.assistance.operit.data.dao.TokenUsageActivityDayRow
import com.ai.assistance.operit.data.dao.TokenUsageModelAggregateRow
import com.ai.assistance.operit.data.model.TokenStatsModelEntity
import java.time.LocalDate
import java.time.ZoneId

/** SQL-backed statistics queries. Only aggregate rows leave Room. */
object TokenStatsQueryService {
    suspend fun lifetimeOverview(
        context: Context,
        params: TokenStatsQueryParams,
    ): TokenStatsLifetimeOverview {
        val repository = TokenUsageRepository.getInstance(context)
        return repository.withDao { dao ->
            val requestRows = dao.aggregateRequestModelsForLifetime(
                providerModels = params.providerModels.queryValues(),
                allModels = params.providerModels == null,
                categories = params.categories.namesForQuery(),
                allCategories = params.categories == null,
                statuses = params.statuses.namesForQuery(),
                allStatuses = params.statuses == null,
            )
            val modelSettings = dao.getAllStatsModels()
            val prices = modelSettings.toPriceSnapshot()
            TokenStatsLifetimeOverview(
                totals = combineTotals(requestRows.map { it.toTotals(prices, params) }, params),
                displayModels = buildDisplayModels(requestRows, prices, params),
            )
        }
    }

    suspend fun rangeData(
        context: Context,
        range: TokenStatsTimeRange,
        params: TokenStatsQueryParams,
        zone: ZoneId,
    ): TokenStatsRangeData {
        val repository = TokenUsageRepository.getInstance(context)
        return repository.withDao { dao ->
            val modelSettings = dao.getAllStatsModels()
            val prices = modelSettings.toPriceSnapshot()
            val modelRows = dao.aggregateModelsInRange(
                startMs = range.startMs,
                endMs = range.endMs,
                providerModels = params.providerModels.queryValues(),
                allModels = params.providerModels == null,
                categories = params.categories.namesForQuery(),
                allCategories = params.categories == null,
                statuses = params.statuses.namesForQuery(),
                allStatuses = params.statuses == null,
            )
            val displayModels = buildDisplayModels(modelRows, prices, params)
            val summary = combineTotals(displayModels.map(TokenStatsDisplayModelBreakdown::totals), params)
            val granularity = TokenStatsTimeRanges.granularityFor(range)
            val starts = TokenStatsTimeRanges.bucketStarts(range, granularity, zone)
            val buckets = starts.mapIndexed { index, bucketStart ->
                val bucketEnd = minOf(
                    range.endMs,
                    TokenStatsTimeRanges.bucketEndMs(starts, index, granularity, zone),
                )
                val bucketRows = dao.aggregateModelsInRange(
                    startMs = maxOf(range.startMs, bucketStart),
                    endMs = bucketEnd,
                    providerModels = params.providerModels.queryValues(),
                    allModels = params.providerModels == null,
                    categories = params.categories.namesForQuery(),
                    allCategories = params.categories == null,
                    statuses = params.statuses.namesForQuery(),
                    allStatuses = params.statuses == null,
                )
                val models = buildDisplayModels(bucketRows, prices, params)
                TokenStatsTrendBucket(
                    bucketStartMs = bucketStart,
                    bucketEndMs = bucketEnd,
                    totals = combineTotals(models.map(TokenStatsDisplayModelBreakdown::totals), params),
                    byModel = models.associate { it.displayModelId to it.totals.toModelBucket() },
                    performance = performanceOf(bucketRows),
                )
            }
            val categoryRows = dao.aggregateCategoriesInRange(
                startMs = range.startMs,
                endMs = range.endMs,
                providerModels = params.providerModels.queryValues(),
                allModels = params.providerModels == null,
                categories = params.categories.namesForQuery(),
                allCategories = params.categories == null,
                statuses = params.statuses.namesForQuery(),
                allStatuses = params.statuses == null,
            )
            val statusRows = dao.aggregateStatusesInRange(
                startMs = range.startMs,
                endMs = range.endMs,
                providerModels = params.providerModels.queryValues(),
                allModels = params.providerModels == null,
                categories = params.categories.namesForQuery(),
                allCategories = params.categories == null,
                statuses = params.statuses.namesForQuery(),
                allStatuses = params.statuses == null,
            )
            TokenStatsRangeData(
                range = range,
                granularity = granularity,
                eventCount = summary.totalTokens.totalEventCount,
                summary = summary,
                performance = performanceOf(modelRows),
                buckets = buckets,
                displayModels = displayModels,
                categories = categoryRows.groupBy(TokenUsageBreakdownRow::key).map { (key, rows) ->
                    TokenStatsCategoryBreakdown(
                        TokenStatCategory.fromName(key),
                        combineTotals(rows.map { it.asModelRow().toTotals(prices, params) }, params),
                    )
                },
                statuses = statusRows.groupBy(TokenUsageBreakdownRow::key).map { (key, rows) ->
                    TokenStatsStatusBreakdown(
                        TokenStatStatus.fromName(key),
                        combineTotals(rows.map { it.asModelRow().toTotals(prices, params) }, params),
                    )
                },
            )
        }
    }

    internal suspend fun activitySnapshot(
        context: Context,
        range: TokenStatsTimeRange,
        params: TokenStatsQueryParams,
        zone: ZoneId,
    ): TokenActivitySnapshot {
        val repository = TokenUsageRepository.getInstance(context)
        return repository.withDao { dao ->
            val days = dao.getActivityDaysInRange(
                startMs = range.startMs,
                endMs = range.endMs,
                providerModels = params.providerModels.queryValues(),
                allModels = params.providerModels == null,
                categories = params.categories.namesForQuery(),
                allCategories = params.categories == null,
                statuses = params.statuses.namesForQuery(),
                allStatuses = params.statuses == null,
            )
            TokenActivitySnapshot(
                zone = zone,
                dayTotals =
                    days.groupBy(TokenUsageActivityDayRow::localDate).mapValues { (_, rows) ->
                        rows.fold(0L) { total, row -> TokenCostCalculator.saturatedAdd(total, row.tokens) }
                    }.mapKeys { (date, _) -> LocalDate.parse(date) },
            )
        }
    }

    private fun buildDisplayModels(
        rows: List<TokenUsageModelAggregateRow>,
        prices: TokenPriceSettingsSnapshot,
        params: TokenStatsQueryParams,
    ): List<TokenStatsDisplayModelBreakdown> =
        rows.groupBy { row -> displayModelIdFor(row.model) }
            .map { (displayModelId, groupRows) ->
                val identities = groupRows.map { row ->
                    TokenStatsIdentityBreakdown(
                        configId = row.configId,
                        provider = row.provider,
                        model = row.model,
                        totals = row.toTotals(prices, params),
                    )
                }
                TokenStatsDisplayModelBreakdown(
                    displayModelId = displayModelId,
                    displayName = groupRows.first().model,
                    normalizedModel = groupRows.first().model.trim().lowercase(),
                    totals = combineTotals(identities.map(TokenStatsIdentityBreakdown::totals), params),
                    identities = identities,
                    providerModels = groupRows.map(TokenUsageModelAggregateRow::providerModel).distinct(),
                )
            }
            .sortedByDescending { it.totals.totalTokens.knownSum }

    private fun TokenUsageModelAggregateRow.toTotals(
        prices: TokenPriceSettingsSnapshot,
        params: TokenStatsQueryParams,
    ): TokenStatsTotals {
        val pricing = TokenPriceResolver.resolve(providerModel, prices.settingFor(providerModel, configId))
        val input = component(uncachedInputTokens, uncachedInputKnown, usageRows)
        val cached = component(cachedInputTokens, cachedInputKnown, usageRows)
        val cacheWrite = component(cacheWriteTokens, cacheWriteKnown, usageRows)
        val totalInput =
            if (totalInputKnown > 0L) {
                component(totalInputTokens, totalInputKnown, usageRows)
            } else {
                combineComponents(listOf(input, cached, cacheWrite), usageRows)
            }
        val output = component(outputTokens, outputKnown, usageRows)
        val reasoning = component(reasoningTokens, reasoningKnown, usageRows)
        val totalTokens = combineComponents(listOf(totalInput, output), usageRows)
        return TokenStatsTotals(
            requests = requests,
            requestCountUnknownContributionCount =
                (usageRows - requestCountKnown).coerceAtLeast(0L),
            uncachedInput = input,
            cachedInput = cached,
            cacheWrite = cacheWrite,
            totalInput = totalInput,
            output = output,
            reasoning = reasoning,
            totalTokens = totalTokens,
            cost = TokenCostCalculator.currentCost(this, pricing, params.targetCurrency, params.manualRate),
        )
    }

    private fun TokenUsageBreakdownRow.asModelRow() = TokenUsageModelAggregateRow(
        provider = provider,
        model = model,
        configId = configId,
        requests = requests,
        requestCountKnown = requestCountKnown,
        usageRows = usageRows,
        uncachedInputTokens = uncachedInputTokens,
        uncachedInputKnown = uncachedInputKnown,
        cachedInputTokens = cachedInputTokens,
        cachedInputKnown = cachedInputKnown,
        cacheWriteTokens = cacheWriteTokens,
        cacheWriteKnown = cacheWriteKnown,
        totalInputTokens = totalInputTokens,
        totalInputKnown = totalInputKnown,
        outputTokens = outputTokens,
        outputKnown = outputKnown,
        reasoningTokens = reasoningTokens,
        reasoningKnown = reasoningKnown,
        ttftTotalMs = ttftTotalMs,
        ttftSamples = ttftSamples,
        durationTotalMs = durationTotalMs,
        durationSamples = durationSamples,
    )

    private fun combineTotals(
        values: List<TokenStatsTotals>,
        params: TokenStatsQueryParams,
    ): TokenStatsTotals {
        if (values.isEmpty()) return emptyTotals(params)
        return TokenStatsTotals(
            requests = values.sumLong(TokenStatsTotals::requests),
            requestCountUnknownContributionCount =
                values.sumLong(TokenStatsTotals::requestCountUnknownContributionCount),
            uncachedInput = values.combineComponents(TokenStatsTotals::uncachedInput),
            cachedInput = values.combineComponents(TokenStatsTotals::cachedInput),
            cacheWrite = values.combineComponents(TokenStatsTotals::cacheWrite),
            totalInput = values.combineComponents(TokenStatsTotals::totalInput),
            output = values.combineComponents(TokenStatsTotals::output),
            reasoning = values.combineComponents(TokenStatsTotals::reasoning),
            totalTokens = values.combineComponents(TokenStatsTotals::totalTokens),
            cost = TokenStatsCostSummary(
                currency = params.targetCurrency,
                knownAmount = values.sumOf { it.cost.knownAmount },
                unknownContributionCount = values.sumLong { it.cost.unknownContributionCount },
                totalContributionCount = values.sumLong { it.cost.totalContributionCount },
                rateUsed = params.manualRate,
                originalCurrencyAmounts = values
                    .flatMap { it.cost.originalCurrencyAmounts.entries }
                    .groupBy({ it.key }, { it.value })
                    .mapValues { (_, amounts) -> amounts.sum() },
            ),
        )
    }

    private fun performanceOf(rows: List<TokenUsageModelAggregateRow>): TokenStatsPerformance {
        val usageRows = rows.sumLong(TokenUsageModelAggregateRow::usageRows)
        val ttftSamples = rows.sumLong(TokenUsageModelAggregateRow::ttftSamples)
        val ttftTotal = rows.sumLong(TokenUsageModelAggregateRow::ttftTotalMs)
        val durationSamples = rows.sumLong(TokenUsageModelAggregateRow::durationSamples)
        val durationTotal = rows.sumLong(TokenUsageModelAggregateRow::durationTotalMs)
        return TokenStatsPerformance(
            ttft = duration(ttftTotal, ttftSamples, usageRows),
            generationDuration = duration(durationTotal, durationSamples, usageRows),
        )
    }

    private fun TokenStatsTotals.toModelBucket() = TokenStatsModelBucket(
        requests,
        requestCountUnknownContributionCount,
        uncachedInput.knownSum,
        cachedInput.knownSum,
        cacheWrite.knownSum,
        output.knownSum,
        reasoning.knownSum,
        totalTokens.knownSum,
        totalTokens.unknownEventCount,
        maxOf(
            uncachedInput.unknownEventCount,
            cachedInput.unknownEventCount,
            output.unknownEventCount,
        ),
        cost,
    )

    private fun emptyTotals(params: TokenStatsQueryParams): TokenStatsTotals {
        val empty = component(0L, 0L, 0L)
        return TokenStatsTotals(
            0L,
            0L,
            empty,
            empty,
            empty,
            empty,
            empty,
            empty,
            empty,
            TokenStatsCostSummary(
                params.targetCurrency,
                0.0,
                0L,
                0L,
                params.manualRate,
                emptyMap(),
            ),
        )
    }

    private fun component(sum: Long, known: Long, total: Long) =
        TokenStatsTokenAggregate(sum, known, (total - known).coerceAtLeast(0L), total)

    private fun combineComponents(
        components: List<TokenStatsTokenAggregate>,
        contributionCount: Long,
    ): TokenStatsTokenAggregate {
        val known = components.minOfOrNull(TokenStatsTokenAggregate::knownEventCount) ?: 0L
        return TokenStatsTokenAggregate(
            knownSum = components.sumLong(TokenStatsTokenAggregate::knownSum),
            knownEventCount = known,
            unknownEventCount = (contributionCount - known).coerceAtLeast(0L),
            totalEventCount = contributionCount,
        )
    }

    private fun List<TokenStatsTotals>.combineComponents(
        selector: (TokenStatsTotals) -> TokenStatsTokenAggregate,
    ): TokenStatsTokenAggregate {
        val values = map(selector)
        return TokenStatsTokenAggregate(
            knownSum = values.sumLong(TokenStatsTokenAggregate::knownSum),
            knownEventCount = values.sumLong(TokenStatsTokenAggregate::knownEventCount),
            unknownEventCount = values.sumLong(TokenStatsTokenAggregate::unknownEventCount),
            totalEventCount = values.sumLong(TokenStatsTokenAggregate::totalEventCount),
        )
    }

    private fun duration(totalMs: Long, samples: Long, contributionCount: Long) =
        TokenStatsDurationAggregate(
            knownCount = samples,
            unknownCount = (contributionCount - samples).coerceAtLeast(0L),
            totalMs = totalMs,
            averageMs = if (samples > 0L) totalMs.toDouble() / samples else 0.0,
        )

    private fun <T> Iterable<T>.sumLong(selector: (T) -> Long): Long =
        fold(0L) { sum, item -> TokenCostCalculator.saturatedAdd(sum, selector(item)) }

    private fun Set<String>?.queryValues(): List<String> =
        if (this == null || isEmpty()) listOf("__none__") else toList()

    private fun <T : Enum<T>> Set<T>?.namesForQuery(): List<String> =
        if (this == null || isEmpty()) listOf("__none__") else map { it.name }

    private fun displayModelIdFor(model: String): String = "model:${model.trim().lowercase()}"

    private fun List<TokenStatsModelEntity>.toPriceSnapshot(): TokenPriceSettingsSnapshot {
        val priceRows = filter(TokenStatsModelEntity::hasPriceSetting)
        return TokenPriceSettingsSnapshot(
            providerModels = priceRows
                .filter { it.configId.isEmpty() }
                .associate { row -> "${row.provider}:${row.model}" to row.toModelPriceSettings() },
            configs = priceRows
                .filter { it.configId.isNotEmpty() }
                .associate { row ->
                    tokenPriceConfigKey("${row.provider}:${row.model}", row.configId) to
                        row.toModelPriceSettings()
                },
        )
    }
}
