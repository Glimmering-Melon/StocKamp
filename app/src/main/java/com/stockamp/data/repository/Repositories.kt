package com.stockamp.data.repository

import com.stockamp.data.local.StockDao
import com.stockamp.data.local.WatchlistDao
import com.stockamp.data.local.JournalDao
import com.stockamp.data.model.*
import com.stockamp.data.sample.SampleData
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StockRepository @Inject constructor(
    private val stockDao: StockDao
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
