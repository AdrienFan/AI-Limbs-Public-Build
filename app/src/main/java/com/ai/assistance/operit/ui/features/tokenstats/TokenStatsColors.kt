package com.ai.assistance.operit.ui.features.tokenstats

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Token statistics uses the application color scheme directly. The semantic palette
 * keeps charts distinguishable without introducing a page-specific visual theme.
 */
data class TokenStatsColors(
    val uncachedInput: Color,
    val cachedInput: Color,
    val cacheWrite: Color,
    val output: Color,
    val reasoning: Color,
    val chartAccent: Color,
    val chartGrid: Color,
    val chartLabel: Color,
    val tooltipContainer: Color,
    val tooltipContent: Color,
    val modelPalette: List<Color>,
    val unknownHint: Color,
    val estimatedBadgeContainer: Color,
)

@Composable
fun tokenStatsColors(): TokenStatsColors {
    val scheme = MaterialTheme.colorScheme
    return TokenStatsColors(
        uncachedInput = scheme.primary,
        cachedInput = scheme.primaryContainer,
        cacheWrite = scheme.secondary,
        output = scheme.tertiary,
        reasoning = scheme.tertiaryContainer,
        chartAccent = scheme.primary,
        chartGrid = scheme.outlineVariant,
        chartLabel = scheme.onSurfaceVariant,
        tooltipContainer = scheme.surfaceVariant,
        tooltipContent = scheme.onSurfaceVariant,
        modelPalette = listOf(
            scheme.primary,
            scheme.secondary,
            scheme.tertiary,
            scheme.primaryContainer,
            scheme.secondaryContainer,
            scheme.tertiaryContainer,
        ),
        unknownHint = scheme.error,
        estimatedBadgeContainer = scheme.tertiaryContainer,
    )
}

val LocalTokenStatsColors = staticCompositionLocalOf<TokenStatsColors> {
    error("TokenStatsColors not provided")
}

@Composable
fun TokenStatsColorsProvider(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTokenStatsColors provides tokenStatsColors(), content = content)
}
