package com.ai.assistance.operit.data.stats

import android.content.Context
import com.ai.assistance.operit.data.collects.PricingCurrency

interface TokenStatsSettingsStore {
    suspend fun loadRateWithEstimate(): Pair<Double, Boolean>

    suspend fun saveRate(rate: Double)

    suspend fun loadTargetCurrency(): PricingCurrency

    suspend fun saveTargetCurrency(currency: PricingCurrency)

    suspend fun loadTimeRange(): TokenStatsTimeRange?

    suspend fun saveTimeRange(range: TokenStatsTimeRange?)
}

/** Statistics-only Preferences implementation; structured data remains in Room. */
class TokenStatsPreferencesStore(context: Context) : TokenStatsSettingsStore {
    private val appContext = context.applicationContext
    private val repository = TokenUsageRepository.getInstance(appContext)
    private val preferences = TokenStatsPreferences(appContext)

    private suspend fun initialize() {
        repository.ensureInitialized()
    }

    override suspend fun loadRateWithEstimate(): Pair<Double, Boolean> {
        initialize()
        return preferences.loadRateWithEstimate()
    }

    override suspend fun saveRate(rate: Double) {
        initialize()
        preferences.saveRate(rate)
    }

    override suspend fun loadTargetCurrency(): PricingCurrency {
        initialize()
        return preferences.loadTargetCurrency()
    }

    override suspend fun saveTargetCurrency(currency: PricingCurrency) {
        initialize()
        preferences.saveTargetCurrency(currency)
    }

    override suspend fun loadTimeRange(): TokenStatsTimeRange? {
        initialize()
        return preferences.loadTimeRange()
    }

    override suspend fun saveTimeRange(range: TokenStatsTimeRange?) {
        initialize()
        preferences.saveTimeRange(range)
    }
}
