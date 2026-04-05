package com.stockamp.data.repository

import com.stockamp.data.local.StockDao
import com.stockamp.data.local.WatchlistDao
import com.stockamp.data.local.JournalDao
import com.stockamp.data.model.*
import com.stockamp.data.network.FinnhubApiService
import com.stockamp.data.sample.SampleData
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StockRepository @Inject constructor(
    private val stockDao: StockDao,
    private val finnhubApiService: FinnhubApiService
) {
    fun getAllStocks(): Flow<List<Stock>> = stockDao.getAllStocks()

    fun searchStocks(query: String): Flow<List<Stock>> = stockDao.searchStocks(query)

    fun getStocksBySector(sector: String): Flow<List<Stock>> = stockDao.getStocksBySector(sector)

    suspend fun getStockBySymbol(symbol: String): Stock? = stockDao.getStockBySymbol(symbol)

    fun getPriceHistory(symbol: String, days: Int = 30): List<StockPrice> {
        return SampleData.generatePriceHistory(symbol, days)
    }

    fun getSectors(): List<MarketSector> = SampleData.sectors

    suspend fun loadSampleData() {
        stockDao.insertStocks(SampleData.stocks)
    }

    /**
     * Refresh all stock prices from Finnhub API.
     * Fetches /quote for each stock and updates Room DB.
     * Includes delay between calls to respect rate limiting (60 calls/min).
     */
    suspend fun refreshStockPrices(): Result<Unit> = runCatching {
        val stocks = SampleData.stocks
        for (stock in stocks) {
            val quoteResult = finnhubApiService.getQuote(stock.symbol)
            quoteResult.onSuccess { quote ->
                if (quote.c > 0) { // Valid price
                    val updatedStock = stock.copy(
                        currentPrice = quote.c,
                        previousClose = quote.pc,
                        change = quote.d,
                        changePercent = quote.dp,
                        lastUpdated = System.currentTimeMillis()
                    )
                    stockDao.insertStocks(listOf(updatedStock))
                }
            }
            // Small delay to respect rate limit (60 calls/min = 1 call/sec)
            kotlinx.coroutines.delay(100)
        }
    }

    /**
     * Refresh a single stock's price from Finnhub API.
     */
    suspend fun refreshSingleStock(symbol: String): Result<Stock?> = runCatching {
        val quoteResult = finnhubApiService.getQuote(symbol)
        val quote = quoteResult.getOrThrow()
        val existing = stockDao.getStockBySymbol(symbol) ?: return@runCatching null
        if (quote.c > 0) {
            val updated = existing.copy(
                currentPrice = quote.c,
                previousClose = quote.pc,
                change = quote.d,
                changePercent = quote.dp,
                lastUpdated = System.currentTimeMillis()
            )
            stockDao.insertStocks(listOf(updated))
            updated
        } else {
            existing
        }
    }
}

@Singleton
class WatchlistRepository @Inject constructor(
    private val watchlistDao: WatchlistDao
) {
    fun getAllWatchlistItems(): Flow<List<WatchlistItem>> = watchlistDao.getAllWatchlistItems()

    suspend fun addToWatchlist(symbol: String, name: String) {
        val now = System.currentTimeMillis() // UTC epoch millis (Requirement 12.1)
        watchlistDao.insertWatchlistItem(
            WatchlistItem(symbol = symbol, name = name, createdAt = now, modifiedAt = now)
        )
    }

    suspend fun updateWatchlistItem(item: WatchlistItem) {
        val now = System.currentTimeMillis() // UTC epoch millis (Requirement 12.2)
        watchlistDao.updateWatchlistItem(item.copy(modifiedAt = now))
    }

    suspend fun removeFromWatchlist(symbol: String) {
        watchlistDao.deleteBySymbol(symbol)
    }

    suspend fun isInWatchlist(symbol: String): Boolean {
        return watchlistDao.isInWatchlist(symbol) > 0
    }
}

@Singleton
class JournalRepository @Inject constructor(
    private val journalDao: JournalDao
) {
    fun getAllEntries(): Flow<List<JournalEntry>> = journalDao.getAllEntries()

    fun getEntriesBySymbol(symbol: String): Flow<List<JournalEntry>> = journalDao.getEntriesBySymbol(symbol)

    suspend fun getEntryById(id: Long): JournalEntry? = journalDao.getEntryById(id)

    suspend fun addEntry(entry: JournalEntry): Long {
        // Ensure createdAt is set to current UTC time if not already provided (Requirement 12.3)
        val now = System.currentTimeMillis()
        val entryWithTimestamp = if (entry.createdAt == 0L) entry.copy(createdAt = now) else entry
        return journalDao.insertEntry(entryWithTimestamp)
    }

    suspend fun updateEntry(entry: JournalEntry) {
        val now = System.currentTimeMillis() // UTC epoch millis (Requirement 12.4)
        journalDao.updateEntry(entry.copy(modifiedAt = now))
    }

    suspend fun deleteEntry(entry: JournalEntry) = journalDao.deleteEntry(entry)

    suspend fun getTotalPnL(): Double = journalDao.getTotalPnL() ?: 0.0

    suspend fun getTotalTrades(): Int = journalDao.getTotalTrades()

    suspend fun loadSampleData() {
        SampleData.sampleJournalEntries.forEach { journalDao.insertEntry(it) }
    }
}
