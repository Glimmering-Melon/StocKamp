package com.stockamp.data.repository

import com.stockamp.data.auth.AuthManager
import com.stockamp.data.local.StockDao
import com.stockamp.data.local.WatchlistDao
import com.stockamp.data.local.JournalDao
import com.stockamp.data.model.*
import com.stockamp.data.sample.SampleData
import com.stockamp.data.supabase.SupabaseClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StockRepository @Inject constructor(
    private val stockDao: StockDao
) {
    fun getAllStocks(): Flow<List<Stock>> = stockDao.getAllStocks()

    fun searchStocks(query: String): Flow<List<Stock>> = stockDao.searchStocks(query)

    /** Tìm kiếm và trả về list ngay (dùng cho search global trong ViewModel) */
    suspend fun searchStocksGlobal(query: String): List<Stock> {
        return stockDao.getStockBySymbol(query)?.let { listOf(it) }
            ?: SampleData.stocks.filter {
                it.symbol.contains(query, ignoreCase = true) ||
                it.name.contains(query, ignoreCase = true)
            }
    }

    fun getStocksBySector(sector: String): Flow<List<Stock>> = stockDao.getStocksBySector(sector)

    suspend fun getStockBySymbol(symbol: String): Stock? = stockDao.getStockBySymbol(symbol)

    fun getPriceHistory(symbol: String, days: Int = 30): List<StockPrice> {
        return SampleData.generatePriceHistory(symbol, days)
    }

    fun getSectors(): List<MarketSector> = SampleData.sectors

    suspend fun loadSampleData() {
        stockDao.insertStocks(SampleData.stocks)
    }

    /** Refresh giá tất cả stocks (hiện dùng sample data, sau này gọi API thật) */
    suspend fun refreshStockPrices(): Result<Unit> = runCatching {
        val updated = SampleData.stocks.map { stock ->
            val delta = (Math.random() - 0.5) * stock.currentPrice * 0.02
            val newPrice = (stock.currentPrice + delta).coerceAtLeast(0.01)
            val change = newPrice - stock.previousClose
            val changePercent = (change / stock.previousClose) * 100
            stock.copy(
                currentPrice = newPrice,
                change = change,
                changePercent = changePercent,
                lastUpdated = System.currentTimeMillis()
            )
        }
        stockDao.insertStocks(updated)
    }

    /** Refresh giá 1 stock cụ thể */
    suspend fun refreshSingleStock(symbol: String): Result<Unit> = runCatching {
        val stock = stockDao.getStockBySymbol(symbol)
            ?: SampleData.stocks.find { it.symbol == symbol }
            ?: return@runCatching
        val delta = (Math.random() - 0.5) * stock.currentPrice * 0.02
        val newPrice = (stock.currentPrice + delta).coerceAtLeast(0.01)
        val change = newPrice - stock.previousClose
        val changePercent = (change / stock.previousClose) * 100
        stockDao.insertStock(stock.copy(
            currentPrice = newPrice,
            change = change,
            changePercent = changePercent,
            lastUpdated = System.currentTimeMillis()
        ))
    }
}

@Singleton
class WatchlistRepository @Inject constructor(
    private val watchlistDao: WatchlistDao,
    private val authManager: AuthManager,
    private val supabaseClient: SupabaseClient
) {
    fun getAllWatchlistItems(): Flow<List<WatchlistItem>> = watchlistDao.getAllWatchlistItems()

    suspend fun addToWatchlist(symbol: String, name: String) {
        val now = System.currentTimeMillis()
        val userId = authManager.getCurrentUser().firstOrNull()?.id ?: ""
        val item = WatchlistItem(
            symbol = symbol,
            name = name,
            userId = userId,
            addedAt = now,
            createdAt = now,
            modifiedAt = now
        )
        watchlistDao.insertWatchlistItem(item)
        // Sync lên Supabase ngay lập tức
        if (userId.isNotBlank()) {
            supabaseClient.upsertWatchlistItem(item)
        }
    }

    suspend fun updateWatchlistItem(item: WatchlistItem) {
        val now = System.currentTimeMillis()
        val updated = item.copy(modifiedAt = now)
        watchlistDao.updateWatchlistItem(updated)
        if (updated.userId.isNotBlank()) {
            supabaseClient.upsertWatchlistItem(updated)
        }
    }

    suspend fun removeFromWatchlist(symbol: String) {
        val item = watchlistDao.getWatchlistItem(symbol)
        if (item != null) {
            if (item.syncedAt != null) {
                // Đã sync → đánh dấu isDeleted, sync engine sẽ xóa remote
                val deleted = item.copy(isDeleted = true, modifiedAt = System.currentTimeMillis(), syncedAt = null)
                watchlistDao.updateWatchlistItem(deleted)
                if (item.userId.isNotBlank()) {
                    supabaseClient.deleteWatchlistItem(item.id)
                }
            } else {
                watchlistDao.deleteBySymbol(symbol)
                if (item.userId.isNotBlank()) {
                    supabaseClient.deleteWatchlistItem(item.id)
                }
            }
        } else {
            watchlistDao.deleteBySymbol(symbol)
        }
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

    suspend fun getTotalTrades(): Int = journalDao.getTotalTrades()
}
