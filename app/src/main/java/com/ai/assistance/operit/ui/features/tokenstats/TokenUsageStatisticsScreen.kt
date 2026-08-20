package com.ai.assistance.operit.ui.features.tokenstats

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.stats.TokenStatsDisplayModelBreakdown
import com.ai.assistance.operit.data.stats.TokenStatsIdentityBreakdown
import com.ai.assistance.operit.data.stats.TokenStatsPriceDraft
import com.ai.assistance.operit.data.stats.TokenStatsRangeData
import com.ai.assistance.operit.ui.components.CustomScaffold
import java.time.ZoneId

/** 性能卡指标切换。 */
internal enum class PerfMetric { TTFT, GENERATION }
private enum class ChartDetailMetric { COST, REQUESTS, TOKENS }

/**
 * Token 统计完整页面（阶段 4）。
 * 沿用 Operit 设置入口与页面框架（Settings → Token使用统计），
 * 升级旧累计页面为账本统计：生命周期总览 + 时间/模型/分类/状态筛选 +
 * 四张图表卡 + 配置详情 + 汇率设置。
 */
@Composable
fun TokenUsageStatisticsScreen(
    onBackPressed: () -> Unit,
) {
    val context = LocalContext.current
    // P1-3：VM 由路由级 ViewModelStore 管理（AppContent 为该 route 提供
    // LocalViewModelStoreOwner，键 = screenKey）——配置变化保留实例，
    // 路由出栈/替换/清栈时 store.clear() 触发 onCleared，viewModelScope
    // 取消；Factory 只持有 applicationContext。
    val viewModel: TokenUsageStatisticsViewModel =
        viewModel(factory = TokenUsageStatisticsViewModel.Factory(context))
    val state by viewModel.state.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()

    // 瞬态 UI 状态：可存 rememberSaveable 的在配置变化后保留（P1-3）；
    // 筛选已在 VM state 中，天然跨配置变化保留。
    var showDateRange by rememberSaveable { mutableStateOf(false) }
    var perfMetric by rememberSaveable { mutableStateOf(PerfMetric.TTFT) }

    LaunchedEffect(actionMessage) {
        actionMessage?.let { message ->
            Toast.makeText(context, message.text, Toast.LENGTH_SHORT).show()
            viewModel.consumeActionMessage()
        }
    }
    LaunchedEffect(Unit) { viewModel.loadForEntry() }

    TokenStatsColorsProvider {
        CustomScaffold { paddingValues ->
            val content: @Composable () -> Unit = {
                when {
                    state.loading && (state.range == null || state.lifetime == null) -> {
                        LoadingState()
                    }
                    state.errorMessage != null && state.range == null -> {
                        ErrorState(
                            message = state.errorMessage.orEmpty(),
                            onRetry = viewModel::load,
                        )
                    }
                    else -> {
                        TokenStatsPageContent(
                            state = state,
                            viewModel = viewModel,
                            zone = viewModel.zone,
                            perfMetric = perfMetric,
                            onTogglePerfMetric = { perfMetric = it },
                            onSelectDateRange = { showDateRange = true },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                content()
            }
        }
    }

    if (showDateRange) {
        TokenStatsDateRangeDialog(
            zone = viewModel.zone,
            maxRangeDays = TokenUsageStatisticsViewModel.MAX_CUSTOM_RANGE_DAYS,
            initialRange = state.currentRange,
            onConfirm = { start, end -> viewModel.setCustomRange(start, end) },
            onDismiss = { showDateRange = false },
        )
    }

}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.token_stats_retry))
            }
        }
    }
}

@Composable
private fun TokenStatsPageContent(
    state: TokenStatsUiState,
    viewModel: TokenUsageStatisticsViewModel,
    zone: ZoneId,
    perfMetric: PerfMetric,
    onTogglePerfMetric: (PerfMetric) -> Unit,
    onSelectDateRange: () -> Unit,
) {
    val lifetime = state.lifetime ?: return
    val hasAnyData =
        lifetime.totals.requests > 0L || lifetime.totals.totalTokens.totalEventCount > 0L
    val context = LocalContext.current
    val range = state.range

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(TokenStatsSpacing.page),
        verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.section),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.content)) {
                TokenStatsSectionHeader(
                    title = stringResource(R.string.token_stats_lifetime_total),
                ) {
                    TokenStatsCurrencyDropdown(
                        selected = state.targetCurrency,
                        onSelect = viewModel::setTargetCurrency,
                        modifier = Modifier.width(88.dp),
                    )
                }
                TokenStatsLifetimeCard(
                    overview = lifetime,
                    currency = state.targetCurrency,
                )
            }
        }

        if (lifetime.displayModels.isNotEmpty()) {
            item {
                TokenStatsLifetimeModelsSection(
                    models = lifetime.displayModels,
                    currency = state.targetCurrency,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.section)) {
                TokenStatsSectionHeader(title = stringResource(R.string.token_stats_range_analysis))
                TokenStatsFilterBar(
                    selectedModels = state.selectedModels,
                    availableModels = state.availableDisplayModels,
                    knownModelNames = state.knownModelNames,
                    selectedCategories = state.selectedCategories,
                    selectedStatuses = state.selectedStatuses,
                    onToggleModel = viewModel::toggleModel,
                    onSelectAllModels = viewModel::selectAllModels,
                    onToggleCategory = viewModel::toggleCategory,
                    onClearAllCategories = viewModel::clearCategories,
                    onToggleStatus = viewModel::toggleStatus,
                    onClearAllStatuses = viewModel::clearStatuses,
                )
                TokenActivitySection(
                    state = state.activity,
                    dateRange = state.currentRange,
                    zone = zone,
                    onSelectMode = viewModel::setActivityViewMode,
                    onSelectDateRange = onSelectDateRange,
                )
                when {
                    range == null -> NoDataCard(text = stringResource(R.string.token_stats_no_data_in_range))
                    !hasAnyData -> EmptyStateCard()
                    range.eventCount == 0L -> {
                        NoDataCard(text = stringResource(R.string.token_stats_no_data_in_range))
                    }
                }
            }
        }

        if (range != null && hasAnyData && range.eventCount > 0L) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.content)) {
                    TokenStatsSectionHeader(title = stringResource(R.string.token_stats_trends))
                    TokenStatsChartsSection(
                        range = range,
                        currency = state.targetCurrency,
                        zone = zone,
                        perfMetric = perfMetric,
                        onTogglePerfMetric = onTogglePerfMetric,
                    )
                }
            }

            item {
                TokenStatsModelDetailsSection(
                    title = stringResource(R.string.settings_model_details),
                    models = range.displayModels,
                    currency = state.targetCurrency,
                    configurationNames = state.configurationNames,
                    priceSettings = state.priceSettings,
                    onSavePrice = viewModel::savePrice,
                    onDeletePrice = viewModel::deletePrice,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.content)) {
                TokenStatsSectionHeader(title = stringResource(R.string.token_stats_settings))
                val rateInvalidText = stringResource(R.string.token_stats_rate_invalid)
                TokenStatsRateCard(
                    manualRate = state.manualRate,
                    rateIsEstimated = state.rateIsEstimated,
                    onSaveRate = { rate ->
                        val ok = viewModel.setManualRate(rate)
                        if (!ok) {
                            Toast.makeText(context, rateInvalidText, Toast.LENGTH_SHORT).show()
                        }
                        ok
                    },
                )
            }
        }

        item {
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun TokenStatsModelDetailsSection(
    title: String,
    models: List<TokenStatsDisplayModelBreakdown>,
    currency: com.ai.assistance.operit.data.collects.PricingCurrency,
    configurationNames: Map<String, String>,
    priceSettings: List<com.ai.assistance.operit.data.stats.TokenStatsPriceSetting>,
    onSavePrice: (TokenStatsPriceDraft) -> Unit,
    onDeletePrice: (com.ai.assistance.operit.data.stats.TokenStatsPriceSetting) -> Unit,
    subtitle: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.content)) {
        TokenStatsSectionHeader(title = title) {
            Text(
                text = stringResource(
                    R.string.token_stats_configuration_count,
                    models.sumOf { it.identities.size },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        var priceEditor by remember { mutableStateOf<PriceEditorTarget?>(null) }
        TokenStatsConfigurationCardsSection(
            configurations = models.flatMap(TokenStatsDisplayModelBreakdown::identities),
            currency = currency,
            configurationNames = configurationNames,
            priceSettings = priceSettings,
            onEditPrice = { existing, draft, configurationName ->
                priceEditor = PriceEditorTarget(existing, draft, configurationName)
            },
        )
        priceEditor?.let { target ->
            PriceSettingsDialog(
                existing = target.existing,
                initialDraft = target.draft,
                configurationName = target.configurationName,
                onSave = onSavePrice,
                onDelete = target.existing?.let { setting -> { onDeletePrice(setting) } },
                onDismiss = { priceEditor = null },
            )
        }
    }
}

private data class PriceEditorTarget(
    val existing: com.ai.assistance.operit.data.stats.TokenStatsPriceSetting?,
    val draft: TokenStatsPriceDraft,
    val configurationName: String?,
)

@Composable
private fun EmptyStateCard() {
    TokenStatsWhiteCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Analytics,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.token_stats_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.token_stats_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun NoDataCard(text: String) {
    TokenStatsWhiteCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

// ==== 四张图表卡（手机纵向；宽屏 2x2） ====

@Composable
private fun TokenStatsChartsSection(
    range: TokenStatsRangeData,
    currency: com.ai.assistance.operit.data.collects.PricingCurrency,
    zone: ZoneId,
    perfMetric: PerfMetric,
    onTogglePerfMetric: (PerfMetric) -> Unit,
) {
    var detailMetric by rememberSaveable { mutableStateOf<ChartDetailMetric?>(null) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val wide = maxWidth > 700.dp
        if (wide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TokenStatsSpacing.section),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.section),
                ) {
                    CostChartCard(range = range, currency = currency, zone = zone) {
                        detailMetric = ChartDetailMetric.COST
                    }
                    TokenChartCard(range = range, currency = currency, zone = zone) {
                        detailMetric = ChartDetailMetric.TOKENS
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.section),
                ) {
                    RequestChartCard(range = range, currency = currency, zone = zone) {
                        detailMetric = ChartDetailMetric.REQUESTS
                    }
                    PerformanceChartCard(
                        range = range,
                        zone = zone,
                        perfMetric = perfMetric,
                        onTogglePerfMetric = onTogglePerfMetric,
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.section)) {
                CostChartCard(range = range, currency = currency, zone = zone) {
                    detailMetric = ChartDetailMetric.COST
                }
                RequestChartCard(range = range, currency = currency, zone = zone) {
                    detailMetric = ChartDetailMetric.REQUESTS
                }
                TokenChartCard(range = range, currency = currency, zone = zone) {
                    detailMetric = ChartDetailMetric.TOKENS
                }
                PerformanceChartCard(
                    range = range,
                    zone = zone,
                    perfMetric = perfMetric,
                    onTogglePerfMetric = onTogglePerfMetric,
                )
            }
        }
    }

    detailMetric?.let { metric ->
        TokenStatsChartDetailDialog(
            metric = metric,
            range = range,
            currency = currency,
            onDismiss = { detailMetric = null },
        )
    }
}

@Composable
private fun TokenStatsChartDetailDialog(
    metric: ChartDetailMetric,
    range: TokenStatsRangeData,
    currency: com.ai.assistance.operit.data.collects.PricingCurrency,
    onDismiss: () -> Unit,
) {
    val title = stringResource(
        when (metric) {
            ChartDetailMetric.COST -> R.string.token_stats_detail_cost
            ChartDetailMetric.REQUESTS -> R.string.token_stats_detail_requests
            ChartDetailMetric.TOKENS -> R.string.token_stats_detail_tokens
        }
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                when (metric) {
                    ChartDetailMetric.COST -> range.displayModels.forEach { model ->
                        if (model.totals.cost.knownAmount > 0.0) {
                            TokenStatsDetailRow(model.displayName, formatMoney(model.totals.cost.knownAmount, currency))
                        }
                    }
                    ChartDetailMetric.REQUESTS -> range.displayModels.forEach { model ->
                        if (model.totals.requests > 0L) {
                            TokenStatsDetailRow(
                                model.displayName,
                                formatRequestCount(
                                    model.totals.requests,
                                    model.totals.requestCountUnknownContributionCount,
                                ),
                            )
                        }
                    }
                    ChartDetailMetric.TOKENS -> {
                        // canonical 总 Token 为权威合计；缓存/非缓存/输出仍是诊断分量
                        TokenStatsDetailRow(
                            stringResource(R.string.token_stats_tokens_total),
                            formatCount(range.summary.totalTokens.knownSum),
                        )
                        TokenStatsDetailRow(
                            stringResource(R.string.token_stats_token_cached),
                            formatCount(range.summary.cachedInput.knownSum),
                        )
                        TokenStatsDetailRow(
                            stringResource(R.string.token_stats_token_uncached),
                            formatCount(range.summary.uncachedInput.knownSum),
                        )
                        TokenStatsDetailRow(
                            stringResource(R.string.token_stats_token_output),
                            formatCount(range.summary.output.knownSum),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.token_stats_detail_close))
            }
        },
    )
}

@Composable
private fun TokenStatsDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = LocalTokenStatsColors.current.chartAccent,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CostChartCard(
    range: TokenStatsRangeData,
    currency: com.ai.assistance.operit.data.collects.PricingCurrency,
    zone: ZoneId,
    onSummaryClick: () -> Unit,
) {
    val colors = LocalTokenStatsColors.current
    val models = range.displayModels
    val colorFor: (String) -> androidx.compose.ui.graphics.Color = { modelId ->
        val index = models.indexOfFirst { it.displayModelId == modelId }
        colors.modelPalette[index.coerceAtLeast(0) % colors.modelPalette.size]
    }
    // 预取模板：chart 回调是非 Composable lambda，不能在回调内解析资源
    val unknownCostTemplate = stringResource(R.string.token_stats_unknown_cost)
    val chartTitle = stringResource(R.string.token_stats_chart_cost)

    TokenStatsChartCard(
        title = chartTitle,
        summary = formatMoney(range.summary.cost.knownAmount, currency),
        onSummaryClick = onSummaryClick,
    ) {
        if (range.summary.cost.unknownContributionCount > 0L) {
            RangeUnknownHint(
                stringResource(R.string.token_stats_unknown_cost, range.summary.cost.unknownContributionCount)
            )
        }
        TokenStatsStackedBarChart(
            buckets = range.buckets,
            granularity = range.granularity,
            zone = zone,
            formatValue = { formatMoney(it, currency) },
            emptyText = stringResource(R.string.token_stats_no_data_in_range),
            chartLabel = chartTitle,
            stackSelector = { bucket ->
                models.mapNotNull { model ->
                    val cost = bucket.byModel[model.displayModelId]?.cost ?: return@mapNotNull null
                    if (cost.knownAmount <= 0.0) null else cost.knownAmount to colorFor(model.displayModelId)
                }
            },
            stackLabels = { bucket ->
                models.mapNotNull { model ->
                    val cost = bucket.byModel[model.displayModelId]?.cost ?: return@mapNotNull null
                    if (cost.knownAmount <= 0.0) null else model.displayName
                }
            },
            unknownNote = { bucket ->
                val unknown = bucket.totals.cost.unknownContributionCount
                if (unknown > 0L) String.format(unknownCostTemplate, unknown) else null
            },
            legendItems = models.take(8).map { it.displayName to colorFor(it.displayModelId) },
        )
    }
}

@Composable
private fun RequestChartCard(
    range: TokenStatsRangeData,
    currency: com.ai.assistance.operit.data.collects.PricingCurrency,
    zone: ZoneId,
    onSummaryClick: () -> Unit,
) {
    val chartTitle = stringResource(R.string.token_stats_chart_requests)
    val unknownRequestTemplate = stringResource(R.string.token_stats_request_count_unknown)
    TokenStatsChartCard(
        title = chartTitle,
        summary = formatRequestCount(
            range.summary.requests,
            range.summary.requestCountUnknownContributionCount,
        ),
        onSummaryClick = onSummaryClick,
    ) {
        TokenStatsLineChart(
            buckets = range.buckets,
            granularity = range.granularity,
            zone = zone,
            formatValue = { formatCount(it.toLong()) },
            emptyText = stringResource(R.string.token_stats_no_data_in_range),
            chartLabel = chartTitle,
            valueSelector = { it.totals.requests.toDouble() },
            unknownNote = { bucket ->
                val unknown = bucket.totals.requestCountUnknownContributionCount
                if (unknown > 0L) String.format(unknownRequestTemplate, unknown) else null
            },
        )
    }
}

@Composable
private fun TokenChartCard(
    range: TokenStatsRangeData,
    currency: com.ai.assistance.operit.data.collects.PricingCurrency,
    zone: ZoneId,
    onSummaryClick: () -> Unit,
) {
    val colors = LocalTokenStatsColors.current
    // 预取模板：chart 回调是非 Composable lambda，不能在回调内解析资源
    val outputLabel = stringResource(R.string.token_stats_token_output)
    val cachedLabel = stringResource(R.string.token_stats_token_cached)
    val uncachedLabel = stringResource(R.string.token_stats_token_uncached)
    val unknownPartsTemplate = stringResource(R.string.token_stats_unknown_parts)
    val chartTitle = stringResource(R.string.token_stats_chart_tokens)

    val totalUnknown = range.summary.totalTokens.unknownEventCount

    TokenStatsChartCard(
        title = chartTitle,
        // Canonical total tokens come from the same SQL records as the headline and details.
        summary = formatCompactCount(range.summary.totalTokens.knownSum),
        onSummaryClick = onSummaryClick,
    ) {
        if (totalUnknown > 0L) {
            RangeUnknownHint(stringResource(R.string.token_stats_unknown_parts, totalUnknown))
        }
        TokenStatsStackedBarChart(
            buckets = range.buckets,
            granularity = range.granularity,
            zone = zone,
            formatValue = { formatCompactCount(it.toLong()) },
            emptyText = stringResource(R.string.token_stats_no_data_in_range),
            chartLabel = chartTitle,
            stackSelector = { bucket ->
                listOf(
                    bucket.totals.output.knownSum.toDouble() to colors.output,
                    bucket.totals.uncachedInput.knownSum.toDouble() to colors.uncachedInput,
                    bucket.totals.cachedInput.knownSum.toDouble() to colors.cachedInput,
                )
            },
            stackLabels = {
                listOf(outputLabel, uncachedLabel, cachedLabel)
            },
            // 堆叠分量是诊断明细（可能因 provider 口径不完全等于总量），
            // tooltip/无障碍合计必须用 canonical 总 Token
            stackTotalSelector = { bucket -> bucket.totals.totalTokens.knownSum.toDouble() },
            unknownNote = { bucket ->
                val unknown = bucket.totals.totalTokens.unknownEventCount
                if (unknown > 0L) String.format(unknownPartsTemplate, unknown) else null
            },
            legendItems = listOf(
                uncachedLabel to colors.uncachedInput,
                cachedLabel to colors.cachedInput,
                outputLabel to colors.output,
            ),
        )
    }
}

@Composable
private fun PerformanceChartCard(
    range: TokenStatsRangeData,
    zone: ZoneId,
    perfMetric: PerfMetric,
    onTogglePerfMetric: (PerfMetric) -> Unit,
) {
    val colors = LocalTokenStatsColors.current
    val aggregate =
        if (perfMetric == PerfMetric.TTFT) range.performance.ttft
        else range.performance.generationDuration
    // 预取模板：chart 回调是非 Composable lambda，不能在回调内解析资源
    val perfNoDataText = stringResource(R.string.token_stats_perf_no_data)
    val durationUnknownTemplate = stringResource(R.string.token_stats_duration_unknown)
    val chartTitle = stringResource(R.string.token_stats_chart_performance)

    TokenStatsChartCard(
        title = chartTitle,
        summary = durationSummaryText(aggregate),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TokenStatsSpacing.content)) {
            FilterChip(
                selected = perfMetric == PerfMetric.TTFT,
                onClick = { onTogglePerfMetric(PerfMetric.TTFT) },
                label = { Text(stringResource(R.string.token_stats_perf_ttft)) },
            )
            FilterChip(
                selected = perfMetric == PerfMetric.GENERATION,
                onClick = { onTogglePerfMetric(PerfMetric.GENERATION) },
                label = { Text(stringResource(R.string.token_stats_perf_generation)) },
            )
        }
        Spacer(Modifier.height(TokenStatsSpacing.content))
        TokenStatsLineChart(
            buckets = range.buckets,
            granularity = range.granularity,
            zone = zone,
            formatValue = { formatDuration(it) },
            emptyText = perfNoDataText,
            chartLabel = chartTitle,
            valueSelector = { bucket ->
                val agg =
                    if (perfMetric == PerfMetric.TTFT) bucket.performance.ttft
                    else bucket.performance.generationDuration
                if (agg.hasData) agg.averageMs else null
            },
            unknownNote = { bucket ->
                val agg =
                    if (perfMetric == PerfMetric.TTFT) bucket.performance.ttft
                    else bucket.performance.generationDuration
                when {
                    !agg.hasData -> perfNoDataText
                    agg.unknownCount > 0L -> String.format(durationUnknownTemplate, agg.unknownCount)
                    else -> null
                }
            },
        )
    }
}

@Composable
private fun RangeUnknownHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalTokenStatsColors.current.unknownHint,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}
