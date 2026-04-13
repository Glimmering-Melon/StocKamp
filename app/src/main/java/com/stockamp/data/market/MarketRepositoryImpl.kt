package com.stockamp.data.market

import android.util.Log
import com.stockamp.data.local.StockSymbolCacheDao
import com.stockamp.data.model.LatestCloseResult
import com.stockamp.data.model.PriceDataPoint
import com.stockamp.data.model.StockSymbolCacheEntity
import com.stockamp.data.model.StockSymbolInfo
import com.stockamp.data.supabase.SupabaseClient
import javax.inject.Inject
import javax.inject.Singleton

private const val CACHE_TTL_MS = 30 * 60 * 1000L

@Singleton
class MarketRepositoryImpl @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val stockSymbolCacheDao: StockSymbolCacheDao
) : MarketRepository {

    // Req 4.2: return OHLCV sorted ascending by timestamp
    override suspend fun getOhlcv(symbol: String, timeframe: String): Result<List<PriceDataPoint>> {
        return supabaseClient.fetchStockPrices(symbol, timeframe).map { points ->
            points.sortedBy { it.timestamp }
        }
    }

    // Req 4.3 / 4.4: cache with 30-min TTL; fall back to stale cache on failure
    override suspend fun getAvailableSymbols(): Result<List<StockSymbolInfo>> {
        val cached = stockSymbolCacheDao.getAll()
        val now = System.currentTimeMillis()
        Log.d("MarketRepo", "getAvailableSymbols: cached=${cached.size}, TTL check=${if (cached.isNotEmpty()) (now - cached.minOf { it.cachedAt }) else -1}ms")

        if (cached.isNotEmpty() && (now - cached.minOf { it.cachedAt }) < CACHE_TTL_MS) {
            Log.d("MarketRepo", "Returning from cache: ${cached.size} symbols")
            return Result.success(cached.map { it.toStockSymbolInfo() })
        }

        Log.d("MarketRepo", "Cache miss/expired, fetching from Supabase...")
        val remote = supabaseClient.fetchStockSymbols()
        Log.d("MarketRepo", "Remote result: isSuccess=${remote.isSuccess}, size=${remote.getOrNull()?.size}, error=${remote.exceptionOrNull()?.message}")
        return if (remote.isSuccess) {
            val symbols = remote.getOrThrow()
            val entities = symbols.map { it.toEntity(now) }
            stockSymbolCacheDao.deleteAll()
            stockSymbolCacheDao.insertAll(entities)
            Result.success(symbols)
        } else {
            // Req 4.4: fall back to stale cache if available
            if (cached.isNotEmpty()) {
                Result.success(cached.map { it.toStockSymbolInfo() })
            } else {
                Result.failure(remote.exceptionOrNull() ?: Exception("Failed to fetch symbols"))
            }
        }
    }

    override suspend fun getLatestClose(symbol: String): Result<LatestCloseResult?> {
        return supabaseClient.fetchLatestClose(symbol)
    }

    override suspend fun prefetchSymbols() {
        getAvailableSymbols()
    }
}

// ---- Mapping helpers ----

private fun StockSymbolCacheEntity.toStockSymbolInfo() = StockSymbolInfo(
    symbol = symbol,
    name = name,
    exchange = exchange,
    sector = sector
)

private fun StockSymbolInfo.toEntity(cachedAt: Long) = StockSymbolCacheEntity(
    symbol = symbol,
    name = name,
    exchange = exchange,
    sector = sector,
    cachedAt = cachedAt
)
