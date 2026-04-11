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
    private val finnhubApiService: FinnhubApiService,
    private val remoteDataSource: com.stockamp.data.chart.RemoteDataSource
) {
    fun getAllStocks(): Flow<List<Stock>> = stockDao.getAllStocks()
    fun searchStocks(query: String): Flow<List<Stock>> = stockDao.searchStocks(query)
    fun getStocksBySector(sector: String): Flow<List<Stock>> = stockDao.getStocksBySector(sector)
    suspend fun getStockBySymbol(symbol: String): Stock? = stockDao.getStockBySymbol(symbol)
    fun getPriceHistory(symbol: String, days: Int = 30): List<StockPrice> = SampleData.generatePriceHistory(symbol, days)
    fun getSectors(): List<MarketSector> = SampleData.sectors

    suspend fun loadSampleData() {
        stockDao.insertStocks(SampleData.stocks)
    }

    suspend fun refreshStockPrices(): Result<Unit> = runCatching {
        val stocks = SampleData.stocks
        for (stock in stocks) {
            val quoteResult = finnhubApiService.getQuote(stock.symbol)
            quoteResult.onSuccess { quote ->
                if (quote.c > 0) {
                    val updatedStock = stock.copy(currentPrice = quote.c, previousClose = quote.pc, change = quote.d, changePercent = quote.dp, lastUpdated = System.currentTimeMillis())
                    stockDao.insertStocks(listOf(updatedStock))
                }
            }
            kotlinx.coroutines.delay(100)
        }
    }

    suspend fun refreshSingleStock(symbol: String): Result<Stock?> = runCatching {
        val quoteResult = finnhubApiService.getQuote(symbol)
        val quote = quoteResult.getOrThrow()
        val existing = stockDao.getStockBySymbol(symbol) ?: return@runCatching null
        if (quote.c > 0) {
            val updated = existing.copy(currentPrice = quote.c, previousClose = quote.pc, change = quote.d, changePercent = quote.dp, lastUpdated = System.currentTimeMillis())
            stockDao.insertStocks(listOf(updated))
            updated
        } else existing
    }

    suspend fun searchStocksGlobal(query: String): List<Stock> {
        if (query.isBlank()) return emptyList()
        return try {
            val result = remoteDataSource.searchStocks(query).getOrThrow()
            val mappedStocks = result.result.map { finnhubStock ->
                Stock(
                    symbol = finnhubStock.displaySymbol,
                    name = finnhubStock.description,
                    sector = finnhubStock.type,
                    exchange = "Global",
                    currentPrice = 0.0, previousClose = 0.0, change = 0.0, changePercent = 0.0, volume = 0L, marketCap = 0L, high52Week = 0.0, low52Week = 0.0, logoUrl = ""
                )
            }
            if (mappedStocks.isNotEmpty()) stockDao.insertStocks(mappedStocks)
            mappedStocks
        } catch (e: Exception) {
            emptyList()
        }
    }
}

@Singleton
class WatchlistRepository @Inject constructor(private val watchlistDao: WatchlistDao) {
    fun getAllWatchlistItems(): Flow<List<WatchlistItem>> = watchlistDao.getAllWatchlistItems()
    suspend fun addToWatchlist(symbol: String, name: String) {
        val now = System.currentTimeMillis()
        watchlistDao.insertWatchlistItem(WatchlistItem(symbol = symbol, name = name, createdAt = now, modifiedAt = now))
    }
    suspend fun updateWatchlistItem(item: WatchlistItem) {
        val now = System.currentTimeMillis()
        watchlistDao.updateWatchlistItem(item.copy(modifiedAt = now))
    }
    suspend fun removeFromWatchlist(symbol: String) = watchlistDao.deleteBySymbol(symbol)
    suspend fun isInWatchlist(symbol: String): Boolean = watchlistDao.isInWatchlist(symbol) > 0
}

@Singleton
class JournalRepository @Inject constructor(private val journalDao: JournalDao) {
    fun getAllEntries(): Flow<List<JournalEntry>> = journalDao.getAllEntries()
    fun getEntriesBySymbol(symbol: String): Flow<List<JournalEntry>> = journalDao.getEntriesBySymbol(symbol)
    suspend fun getEntryById(id: Long): JournalEntry? = journalDao.getEntryById(id)
    suspend fun addEntry(entry: JournalEntry): Long {
        val now = System.currentTimeMillis()
        val entryWithTimestamp = if (entry.createdAt == 0L) entry.copy(createdAt = now) else entry
        return journalDao.insertEntry(entryWithTimestamp)
    }
    suspend fun updateEntry(entry: JournalEntry) {
        val now = System.currentTimeMillis()
        journalDao.updateEntry(entry.copy(modifiedAt = now))
    }

    suspend fun deleteEntry(entry: JournalEntry) {
        if (entry.syncedAt != null) {
            // Đã sync lên Supabase → đánh dấu isDeleted để sync engine xóa remote
            journalDao.updateEntry(entry.copy(
                isDeleted = true,
                modifiedAt = System.currentTimeMillis(),
                syncedAt = null // reset để getUnsyncedJournalEntries nhận ra
            ))
        } else {
            // Chưa sync → xóa thẳng local
            journalDao.deleteEntry(entry)
        }
    }

    suspend fun getTotalPnL(): Double = journalDao.getTotalPnL() ?: 0.0
    suspend fun getTotalTrades(): Int = journalDao.getTotalTrades()
}
