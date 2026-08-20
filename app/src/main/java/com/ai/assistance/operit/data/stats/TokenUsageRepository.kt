package com.ai.assistance.operit.data.stats

import android.content.Context
import androidx.room.withTransaction
import com.ai.assistance.operit.data.dao.TokenUsageDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.TokenStatsModelEntity
import com.ai.assistance.operit.data.model.TokenUsageIdentity
import com.ai.assistance.operit.data.model.TokenUsageRecordEntity
import com.ai.assistance.operit.data.model.TokenUsageRecordSource
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Room owner for token usage plus the one-time cumulative-counter import. */
class TokenUsageRepository private constructor(context: Context) {
    companion object {
        private const val TAG = "TokenUsageRepository"

        @Volatile
        private var instance: TokenUsageRepository? = null
        private val databaseAccessMutex = Mutex()

        fun getInstance(context: Context): TokenUsageRepository =
            instance ?: synchronized(this) {
                instance ?: TokenUsageRepository(context.applicationContext).also { instance = it }
            }

        /**
         * Prevent token-statistics operations from opening or using Room while a restore replaces
         * its database files. The initialization state must be reset before Room is closed.
         */
        suspend fun <T> withDatabaseAccess(block: suspend () -> T): T =
            databaseAccessMutex.withLock { block() }

        suspend fun <T> withDatabaseRestore(block: suspend () -> T): T =
            withDatabaseAccess {
                instance?.initializationComplete = false
                block()
            }
    }

    private val appContext = context.applicationContext
    private val legacyDataSource = ApiPreferences.getInstance(appContext)
    private val statsPreferences = TokenStatsPreferences(appContext)
    private var initializationComplete = false

    suspend fun ensureInitialized() = withDatabaseAccess {
        ensureInitializedLocked()
    }

    /** Resolves the DAO only after the restore barrier and initialization have completed. */
    internal suspend fun <T> withDao(block: suspend (TokenUsageDao) -> T): T =
        withDatabaseAccess {
            ensureInitializedLocked()
            block(AppDatabase.getDatabase(appContext).tokenUsageDao())
        }

    suspend fun record(record: TokenUsageRecordEntity) {
        withDao { dao -> dao.insertRecord(record) }
    }

    private suspend fun ensureInitializedLocked() {
        if (initializationComplete) return
        if (statsPreferences.importedAtMs() == null) {
            val snapshot = legacyDataSource.readTokenStatsMigrationSnapshot()
            val importedAtMs = System.currentTimeMillis()
            val activeDatabase = AppDatabase.getDatabase(appContext)
            val activeDao = activeDatabase.tokenUsageDao()
            activeDatabase.withTransaction {
                activeDao.insertRecords(snapshot.totals.map { total ->
                    TokenUsageRecordEntity(
                        importKey = TokenUsageIdentity(null, total.provider, total.model).encode(),
                        occurredAtMs = null,
                        source = TokenUsageRecordSource.REQUEST,
                        configId = null,
                        provider = total.provider,
                        model = total.model,
                        category = null,
                        status = null,
                        requestCount = total.requestCount,
                        uncachedInputTokens =
                            (total.inputTokens - total.cachedInputTokens).coerceAtLeast(0L),
                        cachedInputTokens = total.cachedInputTokens,
                        cacheWriteTokens = null,
                        totalInputTokens = total.inputTokens,
                        outputTokens = total.outputTokens,
                        reasoningTokens = null,
                        ttftMs = null,
                        durationMs = null,
                    )
                })
                snapshot.prices.forEach { price ->
                    val current =
                        activeDao.getStatsModel("", price.provider, price.model)
                            ?: TokenStatsModelEntity("", price.provider, price.model)
                    activeDao.upsertStatsModel(
                        current.copy(
                            billingMode = price.settings.billingMode?.name,
                            currency = price.settings.currency?.name,
                            inputPricePerMillion = price.settings.inputPricePerMillion,
                            cachedInputPricePerMillion = price.settings.cachedInputPricePerMillion,
                            cacheWritePricePerMillion = price.settings.cacheWritePricePerMillion,
                            outputPricePerMillion = price.settings.outputPricePerMillion,
                            pricePerRequest = price.settings.pricePerRequest,
                        )
                    )
                }
            }
            statsPreferences.completeMigration(
                importedAtMs = importedAtMs,
                releasedUsdToCnyRate = snapshot.usdToCnyRate,
            )
            AppLogger.i(
                TAG,
                "Imported ${snapshot.totals.size} cumulative totals and " +
                    "${snapshot.prices.size} price settings",
            )
        }
        legacyDataSource.clearMigratedTokenStatsData()
        initializationComplete = true
    }
}
