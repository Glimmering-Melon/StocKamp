package com.stockamp.data.chart

import com.stockamp.data.local.ChartDataDao
import com.stockamp.data.model.ChartDataEntity
import com.stockamp.data.model.PriceDataPoint
import com.stockamp.data.model.Timeframe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_CACHE_BYTES = 50L * 1024 * 1024 // 50 MB

@Singleton
class LocalDataSource @Inject constructor(
    private val dao: ChartDataDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns cached data if it exists and is still fresh, null otherwise. */
    suspend fun getCachedData(symbol: String, timeframe: Timeframe): List<PriceDataPoint>? {
        val entity = dao.getChartData(symbol, timeframe.apiValue) ?: return null
        val ttlMs = timeframe.cacheDuration.toMillis()
        val age = System.currentTimeMillis() - entity.cacheTimestamp
        if (age > ttlMs) return null
        return runCatching { json.decodeFromString<List<PriceDataPoint>>(entity.dataJson) }.getOrNull()
    }

    /** Saves data to cache, evicting LRU entries if storage exceeds 50 MB. */
    suspend fun saveData(symbol: String, timeframe: Timeframe, data: List<PriceDataPoint>) {
        val encoded = json.encodeToString(data)
        val entity = ChartDataEntity(
            symbol = symbol,
            timeframe = timeframe.apiValue,
            dataJson = encoded,
            cacheTimestamp = System.currentTimeMillis(),
            dataSize = encoded.length
        )
        // Evict LRU entries until under limit
        while ((dao.getTotalCacheSize() ?: 0L) + entity.dataSize > MAX_CACHE_BYTES) {
            dao.deleteOldestEntry()
        }
        dao.insertChartData(entity)
    }

    suspend fun clearCache(symbol: String) = dao.deleteChartData(symbol)
}
