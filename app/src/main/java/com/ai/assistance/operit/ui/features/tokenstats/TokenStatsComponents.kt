package com.ai.assistance.operit.ui.features.tokenstats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.stats.TokenPriceResolver
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.data.stats.TokenStatStatus
import com.ai.assistance.operit.data.stats.TokenCostCalculator
import com.ai.assistance.operit.data.stats.TokenStatsDisplayModelBreakdown
import com.ai.assistance.operit.data.stats.TokenStatsDurationAggregate
import com.ai.assistance.operit.data.stats.TokenStatsLifetimeOverview
import com.ai.assistance.operit.data.stats.TokenStatsPriceDraft
import com.ai.assistance.operit.data.stats.TokenStatsPriceScope
import com.ai.assistance.operit.data.stats.TokenStatsPriceSetting
import com.ai.assistance.operit.data.stats.TokenStatsRangeData
import com.ai.assistance.operit.data.stats.TokenStatsTimeRange
import com.ai.assistance.operit.data.stats.TokenStatsTokenAggregate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Shared spatial scale for the token statistics page. */
internal object TokenStatsSpacing {
    val page = 16.dp
    val section = 16.dp
    val card = 16.dp
    val content = 8.dp
}

// ==== 通用格式 ====

/** 金额：符号 + 4 位小数（图表/明细统一）。 */
internal fun formatMoney(amount: Double, currency: PricingCurrency): String =
    "${currency.symbol}${String.format(Locale.US, "%.4f", amount)}"

/** 累计总览的费用使用紧凑的两位小数，保证三项指标可在一行展示。 */
private fun formatLifetimeMoney(amount: Double, currency: PricingCurrency): String =
    "${currency.symbol}${String.format(Locale.US, "%.2f", amount)}"

internal fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

@Composable
internal fun formatRequestCount(value: Long, unknownContributionCount: Long): String =
    if (unknownContributionCount > 0L) {
        stringResource(R.string.token_stats_request_count_minimum, formatCount(value))
    } else {
        formatCount(value)
    }

@Composable
internal fun formatRequestCountLabel(value: Long, unknownContributionCount: Long): String =
    if (unknownContributionCount > 0L) {
        stringResource(R.string.token_stats_request_count_label_minimum, formatCount(value))
    } else {
        stringResource(R.string.settings_request_count_label, value)
    }

@Composable
internal fun formatCompactRequestCountLabel(value: Long, unknownContributionCount: Long): String =
    if (unknownContributionCount > 0L) {
        stringResource(R.string.token_stats_request_count_compact_minimum, formatCompactCount(value))
    } else {
        stringResource(R.string.token_stats_request_count_compact, formatCompactCount(value))
    }

/** Statistics cards follow the application surface and content colors. */
@Composable
internal fun TokenStatsWhiteCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        content = content,
    )
}

/** Page-level headings stay visually separate from labels inside cards. */
@Composable
internal fun TokenStatsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

// ==== 生命周期累计总览（不受筛选） ====

@Composable
internal fun TokenStatsLifetimeCard(
    overview: TokenStatsLifetimeOverview,
    currency: PricingCurrency,
) {
    val colors = LocalTokenStatsColors.current
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = contentColor,
        ),
    ) {
        Column(modifier = Modifier.padding(TokenStatsSpacing.card)) {
            val totals = overview.totals
            val unknownCostContributions = totals.cost.unknownContributionCount
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BigNumber(
                    label = stringResource(R.string.settings_total_requests),
                    value =
                        formatRequestCount(
                            totals.requests,
                            totals.requestCountUnknownContributionCount,
                        ),
                    color = contentColor,
                )
                BigNumber(
                    label = stringResource(R.string.token_stats_tokens_total),
                    value = formatCompactCount(knownTokenSum(totals)),
                    color = contentColor,
                )
                BigNumber(
                    label = stringResource(R.string.settings_total_cost),
                    value =
                        formatLifetimeMoney(
                            totals.cost.knownAmount,
                            currency,
                        ),
                    color = contentColor,
                    alignEnd = true,
                )
            }

            if (unknownCostContributions > 0L) {
                UnknownHint(
                    text = stringResource(
                        R.string.token_stats_unknown_cost,
                        unknownCostContributions,
                    ),
                    color = colors.unknownHint,
                )
            }
            Spacer(Modifier.height(12.dp))

            TokenComponentLines(totals = totals, textColor = contentColor)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BigNumber(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    alignEnd: Boolean = false,
) {
    Column(
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        modifier = Modifier.weight(1f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color.copy(alpha = 0.8f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
internal fun EstimatedBadge(text: String, textColor: androidx.compose.ui.graphics.Color) {
    val colors = LocalTokenStatsColors.current
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colors.estimatedBadgeContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun UnknownHint(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun TokenComponentLines(
    totals: com.ai.assistance.operit.data.stats.TokenStatsTotals,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.content)) {
        TokenLine(
            label = stringResource(R.string.token_stats_token_uncached),
            aggregate = totals.uncachedInput,
            textColor = textColor,
        )
        TokenLine(
            label = stringResource(R.string.token_stats_token_cached),
            aggregate = totals.cachedInput,
            textColor = textColor,
        )
        TokenLine(
            label = stringResource(R.string.token_stats_token_cache_write),
            aggregate = totals.cacheWrite,
            textColor = textColor,
        )
        TokenLine(
            label = stringResource(R.string.token_stats_token_output),
            aggregate = totals.output,
            textColor = textColor,
        )
        TokenLine(
            label = stringResource(R.string.token_stats_token_reasoning),
            aggregate = totals.reasoning,
            textColor = textColor,
        )
    }
}

@Composable
private fun TokenLine(
    label: String,
    aggregate: TokenStatsTokenAggregate,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = textColor.copy(alpha = 0.85f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (aggregate.unknownEventCount > 0L) {
                Text(
                    text = stringResource(
                        R.string.token_stats_unknown_part_suffix,
                        aggregate.unknownEventCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalTokenStatsColors.current.unknownHint,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = formatCompactCount(aggregate.knownSum),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = textColor,
            )
        }
    }
}

internal fun knownTokenSum(
    totals: com.ai.assistance.operit.data.stats.TokenStatsTotals,
): Long = totals.totalTokens.knownSum

internal fun saturatedTokenSum(vararg values: Long): Long =
    values.fold(0L, TokenCostCalculator::saturatedAdd)

// ==== 生命周期模型累计 ====

@Composable
internal fun TokenStatsLifetimeModelsSection(
    models: List<TokenStatsDisplayModelBreakdown>,
    currency: PricingCurrency,
) {
    val sortedModels = models.sortedByDescending { knownTokenSum(it.totals) }
    var showAllModels by rememberSaveable { mutableStateOf(false) }
    val visibleModels =
        if (showAllModels) {
            sortedModels
        } else {
            sortedModels.take(LIFETIME_MODELS_COLLAPSED_COUNT)
        }
    val totalTokens = sortedModels.fold(0L) { total, model ->
        TokenCostCalculator.saturatedAdd(total, knownTokenSum(model.totals))
    }

    Column(verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.content)) {
        TokenStatsSectionHeader(
            title = stringResource(R.string.token_stats_lifetime_models),
        ) {
            Text(
                text = stringResource(R.string.token_stats_model_count, sortedModels.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TokenStatsWhiteCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(TokenStatsSpacing.card),
                verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.content),
            ) {
                if (totalTokens > 0L) {
                    TokenStatsModelDistributionPie(
                        models = sortedModels,
                        totalTokens = totalTokens,
                    )
                }

                visibleModels.forEachIndexed { index, model ->
                    TokenStatsLifetimeModelRow(
                        model = model,
                        totalTokens = totalTokens,
                        color = LocalTokenStatsColors.current.modelPalette[
                            index % LocalTokenStatsColors.current.modelPalette.size
                        ],
                        currency = currency,
                    )
                }

                if (sortedModels.size > LIFETIME_MODELS_COLLAPSED_COUNT) {
                    TextButton(
                        onClick = { showAllModels = !showAllModels },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(
                            text =
                                if (showAllModels) {
                                    stringResource(R.string.token_stats_model_collapse)
                                } else {
                                    stringResource(
                                        R.string.token_stats_model_show_all,
                                        sortedModels.size,
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

private const val LIFETIME_MODELS_COLLAPSED_COUNT = 5

@Composable
private fun TokenStatsModelDistributionPie(
    models: List<TokenStatsDisplayModelBreakdown>,
    totalTokens: Long,
) {
    val palette = LocalTokenStatsColors.current.modelPalette
    val centerColor = MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Canvas(modifier = Modifier.size(124.dp)) {
            var startAngle = -90f
            models.forEachIndexed { index, model ->
                val sweepAngle = knownTokenSum(model.totals).toFloat() / totalTokens * 360f
                if (sweepAngle > 0f) {
                    drawArc(
                        color = palette[index % palette.size],
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = true,
                    )
                    startAngle += sweepAngle
                }
            }
            drawCircle(
                color = centerColor,
                radius = size.minDimension * 0.22f,
            )
        }
    }
}

@Composable
private fun TokenStatsLifetimeModelRow(
    model: TokenStatsDisplayModelBreakdown,
    totalTokens: Long,
    color: Color,
    currency: PricingCurrency,
) {
    val tokens = knownTokenSum(model.totals)
    val percentage =
        if (totalTokens > 0L) {
            (tokens.toDouble() / totalTokens * 100).roundToInt()
        } else {
            0
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = color)
        }
        Spacer(Modifier.width(TokenStatsSpacing.content))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.token_stats_lifetime_model_value,
                    formatCompactCount(tokens),
                    percentage,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatMoney(model.totals.cost.knownAmount, currency),
            style = MaterialTheme.typography.bodySmall,
            color = LocalTokenStatsColors.current.chartAccent,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ==== 筛选栏 ====

/** 当前日期范围内活动、图表和模型明细共用的查询条件。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TokenStatsFilterBar(
    selectedModels: Set<String>,
    availableModels: List<TokenStatsDisplayModelBreakdown>,
    knownModelNames: Map<String, String>,
    selectedCategories: Set<TokenStatCategory>?,
    selectedStatuses: Set<TokenStatStatus>?,
    onToggleModel: (String) -> Unit,
    onSelectAllModels: () -> Unit,
    onToggleCategory: (TokenStatCategory) -> Unit,
    onClearAllCategories: () -> Unit,
    onToggleStatus: (TokenStatStatus) -> Unit,
    onClearAllStatuses: () -> Unit,
) {
    TokenStatsWhiteCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(TokenStatsSpacing.card),
            verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.content),
        ) {
            Text(
                text = stringResource(R.string.token_stats_filters),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 用两列呈现，让每个条件都能完整表达自身含义。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TokenStatsSpacing.content),
            ) {
                ModelFilterDropdown(
                    selectedModels,
                    availableModels,
                    knownModelNames,
                    onToggleModel,
                    onSelectAllModels,
                    Modifier.weight(1f),
                )
                CategoryFilterDropdown(
                    selectedCategories,
                    onToggleCategory,
                    onClearAllCategories,
                    Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TokenStatsSpacing.content),
            ) {
                StatusFilterDropdown(
                    selectedStatuses,
                    onToggleStatus,
                    onClearAllStatuses,
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

internal fun formatDateRangeLabel(range: TokenStatsTimeRange, zone: ZoneId): String {
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    val start = java.time.Instant.ofEpochMilli(range.startMs).atZone(zone).toLocalDate()
    val end = java.time.Instant.ofEpochMilli(range.endMs - 1L).atZone(zone).toLocalDate()
    return if (start == end) start.format(formatter) else "${start.format(formatter)} - ${end.format(formatter)}"
}

internal fun formatCompactDateRangeLabel(range: TokenStatsTimeRange, zone: ZoneId): String {
    val formatter = DateTimeFormatter.ofPattern("M/d", Locale.getDefault())
    val start = java.time.Instant.ofEpochMilli(range.startMs).atZone(zone).toLocalDate()
    val end = java.time.Instant.ofEpochMilli(range.endMs - 1L).atZone(zone).toLocalDate()
    return if (start == end) start.format(formatter) else "${start.format(formatter)}-${end.format(formatter)}"
}

@Composable
internal fun TokenStatsCurrencyDropdown(
    selected: PricingCurrency,
    onSelect: (PricingCurrency) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterDropdown(
        label = selected.code,
        modifier = modifier,
    ) { dismiss ->
        PricingCurrency.entries.forEach { currency ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = currency.code,
                        fontWeight = if (currency == selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                onClick = {
                    dismiss()
                    onSelect(currency)
                },
            )
        }
    }
}

@Composable
private fun ModelFilterDropdown(
    selectedModels: Set<String>,
    availableModels: List<TokenStatsDisplayModelBreakdown>,
    knownModelNames: Map<String, String>,
    onToggleModel: (String) -> Unit,
    onSelectAllModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 可选项 = 当前范围可用模型 + 已被选中但被筛选出当前结果的模型（P1-5）
    val options: List<Pair<String, String>> = remember(availableModels, selectedModels, knownModelNames) {
        val byId = availableModels.associateBy { it.displayModelId }
        buildList {
            availableModels.forEach { add(it.displayModelId to it.displayName) }
            selectedModels.forEach { id ->
                if (id !in byId) add(id to (knownModelNames[id] ?: id))
            }
        }
    }
    FilterDropdown(
        modifier = modifier,
        label = if (selectedModels.isEmpty()) {
            stringResource(
                R.string.token_stats_filter_model_label,
                stringResource(R.string.token_stats_filter_all_models),
            )
        } else {
            stringResource(
                R.string.token_stats_filter_model_label,
                stringResource(R.string.token_stats_filter_models_count, selectedModels.size),
            )
        },
    ) { dismiss ->
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.token_stats_filter_all_models),
                    fontWeight = FontWeight.Bold,
                )
            },
            onClick = {
                onSelectAllModels()
                dismiss()
            },
        )
        options.forEach { (modelId, displayName) ->
            val checked = selectedModels.isEmpty() || modelId in selectedModels
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggleModel(modelId) },
                        )
                        Text(
                            displayName,
                            modifier = Modifier.padding(start = 4.dp),
                            maxLines = 1,
                        )
                    }
                },
                onClick = { onToggleModel(modelId) },
            )
        }
    }
}

@Composable
private fun CategoryFilterDropdown(
    selected: Set<TokenStatCategory>?,
    onToggle: (TokenStatCategory) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterDropdown(
        modifier = modifier,
        label = if (selected == null) {
            stringResource(
                R.string.token_stats_filter_category_label,
                stringResource(R.string.token_stats_filter_all_categories),
            )
        } else {
            stringResource(
                R.string.token_stats_filter_category_label,
                stringResource(R.string.token_stats_filter_categories_count, selected.size),
            )
        },
    ) { dismiss ->
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.token_stats_filter_all_categories),
                    fontWeight = FontWeight.Bold,
                )
            },
            onClick = {
                if (selected != null) onClearAll()
                dismiss()
            },
        )
        TokenStatCategory.entries.forEach { category ->
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selected?.contains(category) == true,
                            onCheckedChange = { onToggle(category) },
                        )
                        Text(
                            stringResource(category.labelRes()),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                },
                onClick = { onToggle(category) },
            )
        }
    }
}

@Composable
private fun StatusFilterDropdown(
    selected: Set<TokenStatStatus>?,
    onToggle: (TokenStatStatus) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterDropdown(
        modifier = modifier,
        label = if (selected == null) {
            stringResource(
                R.string.token_stats_filter_status_label,
                stringResource(R.string.token_stats_filter_all_statuses),
            )
        } else {
            stringResource(
                R.string.token_stats_filter_status_label,
                stringResource(R.string.token_stats_filter_statuses_count, selected.size),
            )
        },
    ) { dismiss ->
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.token_stats_filter_all_statuses),
                    fontWeight = FontWeight.Bold,
                )
            },
            onClick = {
                if (selected != null) onClearAll()
                dismiss()
            },
        )
        TokenStatStatus.entries.forEach { status ->
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selected?.contains(status) == true,
                            onCheckedChange = { onToggle(status) },
                        )
                        Text(
                            stringResource(status.labelRes()),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                },
                onClick = { onToggle(status) },
            )
        }
    }
}

@Composable
private fun FilterDropdown(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

internal fun TokenStatCategory.labelRes(): Int =
    when (this) {
        TokenStatCategory.CHAT -> R.string.token_stats_category_chat
        TokenStatCategory.SUBAGENT -> R.string.token_stats_category_subagent
        TokenStatCategory.SUMMARY -> R.string.token_stats_category_summary
        TokenStatCategory.TITLE -> R.string.token_stats_category_title
        TokenStatCategory.MEMORY -> R.string.token_stats_category_memory
        TokenStatCategory.CHARACTER_GENERATION -> R.string.token_stats_category_character
        TokenStatCategory.CONNECTION_TEST -> R.string.token_stats_category_connection_test
        TokenStatCategory.OTHER -> R.string.token_stats_category_other
    }

internal fun TokenStatStatus.labelRes(): Int =
    when (this) {
        TokenStatStatus.COMPLETED -> R.string.token_stats_status_completed
        TokenStatStatus.CANCELLED -> R.string.token_stats_status_cancelled
        TokenStatStatus.TIMEOUT -> R.string.token_stats_status_timeout
        TokenStatStatus.FAILED -> R.string.token_stats_status_failed
    }

// ==== 图表卡片 ====

@Composable
internal fun TokenStatsChartCard(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    onSummaryClick: (() -> Unit)? = null,
    headerExtra: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = LocalTokenStatsColors.current
    TokenStatsWhiteCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(TokenStatsSpacing.card)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.chartAccent,
                    modifier = if (onSummaryClick != null) {
                        Modifier.clickable(onClick = onSummaryClick)
                    } else {
                        Modifier
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            headerExtra()
            content()
        }
    }
}

// ==== 配置详情 ====

@Composable
internal fun TokenStatsConfigurationCardsSection(
    configurations: List<com.ai.assistance.operit.data.stats.TokenStatsIdentityBreakdown>,
    currency: PricingCurrency,
    configurationNames: Map<String, String>,
    priceSettings: List<TokenStatsPriceSetting>,
    onEditPrice: (TokenStatsPriceSetting?, TokenStatsPriceDraft, String?) -> Unit,
) {
    TokenStatsWhiteCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            configurations
                .sortedByDescending { it.totals.totalTokens.knownSum }
                .forEachIndexed { index, identity ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    val configurationName =
                        identity.configId?.let { configId ->
                            configurationNames[configId]
                                ?: stringResource(R.string.token_stats_config_deleted)
                        } ?: stringResource(R.string.token_stats_legacy_configuration)
                    TokenStatsConfigurationRow(
                        identity = identity,
                        configurationName = configurationName,
                        currency = currency,
                        priceSettings = priceSettings,
                        onEditPrice = onEditPrice,
                    )
                }
        }
    }
}

@Composable
private fun TokenStatsConfigurationRow(
    identity: com.ai.assistance.operit.data.stats.TokenStatsIdentityBreakdown,
    configurationName: String,
    currency: PricingCurrency,
    priceSettings: List<TokenStatsPriceSetting>,
    onEditPrice: (TokenStatsPriceSetting?, TokenStatsPriceDraft, String?) -> Unit,
) {
    val colors = LocalTokenStatsColors.current
    var expanded by remember(identity.configId, identity.provider, identity.model) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = TokenStatsSpacing.card, vertical = 10.dp),
    ) {
        val totals = identity.totals
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = configurationName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${identity.provider} · ${identity.model}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatMoney(totals.cost.knownAmount, currency),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = colors.chartAccent,
                    maxLines = 1,
                )
                Text(
                    text = formatCompactRequestCountLabel(
                        totals.requests,
                        totals.requestCountUnknownContributionCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(R.string.token_stats_model_expand),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            FlowRow(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${stringResource(R.string.token_stats_token_uncached)} ${formatCompactCount(totals.uncachedInput.knownSum)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${stringResource(R.string.token_stats_token_cached)} ${formatCompactCount(totals.cachedInput.knownSum)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${stringResource(R.string.token_stats_token_output)} ${formatCompactCount(totals.output.knownSum)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (totals.uncachedInput.unknownEventCount > 0L ||
                    totals.cachedInput.unknownEventCount > 0L ||
                    totals.output.unknownEventCount > 0L
                ) {
                    Text(
                        text = stringResource(
                            R.string.token_stats_unknown_parts,
                            totals.uncachedInput.unknownEventCount +
                                totals.cachedInput.unknownEventCount +
                                totals.output.unknownEventCount,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.unknownHint,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(
                    onClick = {
                        val scope =
                            if (identity.configId.isNullOrEmpty()) {
                                TokenStatsPriceScope.PROVIDER_MODEL
                            } else {
                                TokenStatsPriceScope.CONFIG
                            }
                        val providerModel = "${identity.provider}:${identity.model}"
                        val existing =
                            priceSettings.firstOrNull {
                                it.scope == scope &&
                                    it.providerModel.equals(providerModel, ignoreCase = true) &&
                                    (scope == TokenStatsPriceScope.PROVIDER_MODEL ||
                                        it.configId == identity.configId)
                            }
                        onEditPrice(
                            existing,
                            priceDraftForConfiguration(identity, priceSettings),
                            identity.configId?.let { configurationName },
                        )
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.token_stats_pricing_edit),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (totals.cost.unknownContributionCount > 0L) {
                Text(
                    text = stringResource(R.string.token_stats_unknown_cost, totals.cost.unknownContributionCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.unknownHint,
                )
            }
        }
    }
}

// ==== 汇率与币种设置卡 ====

@Composable
internal fun TokenStatsRateCard(
    manualRate: Double,
    rateIsEstimated: Boolean,
    onSaveRate: (Double) -> Boolean,
) {
    val colors = LocalTokenStatsColors.current
    var rateInput by remember { mutableStateOf(formatRateInput(manualRate)) }
    // 汇率外部变化（如从 DataStore 重新加载）时同步输入框
    LaunchedEffect(manualRate) {
        rateInput = formatRateInput(manualRate)
    }

    TokenStatsWhiteCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(TokenStatsSpacing.card),
            verticalArrangement = Arrangement.spacedBy(TokenStatsSpacing.content),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_exchange_rate_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (rateIsEstimated) {
                    EstimatedBadge(
                        text = stringResource(R.string.token_stats_rate_default_badge),
                        textColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_exchange_rate_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = rateInput,
                    onValueChange = { rateInput = it },
                    label = { Text(stringResource(R.string.settings_usd_to_cny_rate_label)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val parsed = rateInput.toDoubleOrNull()
                        if (parsed == null || !onSaveRate(parsed)) {
                            // 非法输入保持原值并提示（Toast 由调用方统一处理）
                            rateInput = formatRateInput(manualRate)
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_save))
                }
            }

            if (rateIsEstimated) {
                Text(
                    text = stringResource(R.string.token_stats_rate_default_hint, manualRate),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.unknownHint,
                )
            }
        }
    }
}

private fun priceDraftForConfiguration(
    identity: com.ai.assistance.operit.data.stats.TokenStatsIdentityBreakdown,
    priceSettings: List<TokenStatsPriceSetting>,
): TokenStatsPriceDraft {
    val providerModel = "${identity.provider}:${identity.model}"
    val providerSettings =
        priceSettings.firstOrNull {
            it.scope == TokenStatsPriceScope.PROVIDER_MODEL &&
                it.providerModel.equals(providerModel, ignoreCase = true)
        }?.toModelPriceSettings()
    val configurationSettings =
        identity.configId?.let { configId ->
            priceSettings.firstOrNull {
                it.scope == TokenStatsPriceScope.CONFIG &&
                    it.providerModel.equals(providerModel, ignoreCase = true) &&
                    it.configId == configId
            }
        }?.toModelPriceSettings()
    val resolved =
        TokenPriceResolver.resolve(
            providerModel,
            mergePriceSettings(providerSettings, configurationSettings),
        )
    return TokenStatsPriceDraft(
        scope =
            if (identity.configId.isNullOrEmpty()) {
                TokenStatsPriceScope.PROVIDER_MODEL
            } else {
                TokenStatsPriceScope.CONFIG
            },
        provider = identity.provider,
        model = identity.model,
        configId = identity.configId,
        billingMode = resolved.billingMode,
        currency = resolved.currency,
        inputPricePerMillion = resolved.inputPricePerMillion,
        cachedInputPricePerMillion = resolved.cachedInputPricePerMillion,
        cacheWritePricePerMillion = resolved.cacheWritePricePerMillion,
        outputPricePerMillion = resolved.outputPricePerMillion,
        pricePerRequest = resolved.pricePerRequest,
    )
}

private fun mergePriceSettings(
    provider: com.ai.assistance.operit.data.stats.ModelPriceSettings?,
    configuration: com.ai.assistance.operit.data.stats.ModelPriceSettings?,
) =
    com.ai.assistance.operit.data.stats.ModelPriceSettings(
        billingMode = configuration?.billingMode ?: provider?.billingMode,
        currency = configuration?.currency ?: provider?.currency,
        inputPricePerMillion = configuration?.inputPricePerMillion ?: provider?.inputPricePerMillion,
        cachedInputPricePerMillion =
            configuration?.cachedInputPricePerMillion ?: provider?.cachedInputPricePerMillion,
        cacheWritePricePerMillion =
            configuration?.cacheWritePricePerMillion ?: provider?.cacheWritePricePerMillion,
        outputPricePerMillion = configuration?.outputPricePerMillion ?: provider?.outputPricePerMillion,
        pricePerRequest = configuration?.pricePerRequest ?: provider?.pricePerRequest,
    )

private fun TokenStatsPriceSetting.toModelPriceSettings() =
    com.ai.assistance.operit.data.stats.ModelPriceSettings(
        billingMode = billingMode,
        currency = currency,
        inputPricePerMillion = inputPricePerMillion,
        cachedInputPricePerMillion = cachedInputPricePerMillion,
        cacheWritePricePerMillion = cacheWritePricePerMillion,
        outputPricePerMillion = outputPricePerMillion,
        pricePerRequest = pricePerRequest,
    )

private fun formatRateInput(rate: Double): String =
    String.format(Locale.US, "%.4f", rate).trimEnd('0').trimEnd('.')

/** 性能聚合的平均值格式化（无有效样本显示“无数据”而非 0）。 */
@Composable
internal fun durationSummaryText(aggregate: TokenStatsDurationAggregate): String {
    if (!aggregate.hasData) return stringResource(R.string.token_stats_perf_no_data)
    val avg = formatDuration(aggregate.averageMs)
    return stringResource(R.string.token_stats_perf_avg, avg)
}
