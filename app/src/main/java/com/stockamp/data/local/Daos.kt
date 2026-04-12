package com.stockamp.data.local

import androidx.room.*
import com.stockamp.data.model.Stock
import com.stockamp.data.model.WatchlistItem
import com.stockamp.data.model.JournalEntry
import com.stockamp.data.model.SyncQueueItem
import com.stockamp.data.model.SyncMetadata
import kotlinx.coroutines.flow.Flow

@Dao
interface StockDao {
    @Query("SELECT * FROM stocks ORDER BY symbol ASC")
    fun getAllStocks(): Flow<List<Stock>>

    @Query("SELECT * FROM stocks WHERE symbol = :symbol")
    suspend fun getStockBySymbol(symbol: String): Stock?

    @Query("SELECT * FROM stocks WHERE name LIKE '%' || :query || '%' OR symbol LIKE '%' || :query || '%'")
    fun searchStocks(query: String): Flow<List<Stock>>

    @Query("SELECT * FROM stocks WHERE sector = :sector")
    fun getStocksBySector(sector: String): Flow<List<Stock>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStocks(stocks: List<Stock>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStock(stock: Stock)

    @Delete
    suspend fun deleteStock(stock: Stock)
}

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun getAllWatchlistItems(): Flow<List<WatchlistItem>>

    @Query("SELECT * FROM watchlist WHERE symbol = :symbol LIMIT 1")
    suspend fun getWatchlistItem(symbol: String): WatchlistItem?

    @Query("SELECT * FROM watchlist WHERE id = :id LIMIT 1")
    suspend fun getWatchlistItemById(id: Long): WatchlistItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWatchlistItem(item: WatchlistItem)

    @Update
    suspend fun updateWatchlistItem(item: WatchlistItem)

    @Delete
    suspend fun deleteWatchlistItem(item: WatchlistItem)

    @Query("DELETE FROM watchlist WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)

    @Query("SELECT COUNT(*) FROM watchlist WHERE symbol = :symbol")
    suspend fun isInWatchlist(symbol: String): Int

    /** Returns items that have never been synced or have been modified since last sync. */
    @Query("SELECT * FROM watchlist WHERE syncedAt IS NULL OR modifiedAt > syncedAt")
    suspend fun getUnsyncedWatchlistItems(): List<WatchlistItem>

    /** Deletes all watchlist items from the local database (used during account deletion). */
    @Query("DELETE FROM watchlist")
    suspend fun deleteAllWatchlistItems()
}

@Dao
interface JournalDao {
    @Query("SELECT * FROM journal_entries WHERE isDeleted = 0 ORDER BY createdAt DESC")
    fun getAllEntries(): Flow<List<JournalEntry>>

    @Query("SELECT * FROM journal_entries WHERE id = :id")
    suspend fun getEntryById(id: Long): JournalEntry?

    @Query("SELECT * FROM journal_entries WHERE symbol = :symbol ORDER BY createdAt DESC")
    fun getEntriesBySymbol(symbol: String): Flow<List<JournalEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: JournalEntry): Long

    @Update
    suspend fun updateEntry(entry: JournalEntry)

    @Delete
    suspend fun deleteEntry(entry: JournalEntry)

    @Query("SELECT COUNT(*) FROM journal_entries")
    suspend fun getTotalTrades(): Int

    /** Returns entries that have never been synced or have been modified since last sync. */
    @Query("SELECT * FROM journal_entries WHERE syncedAt IS NULL OR modifiedAt > syncedAt")
    suspend fun getUnsyncedJournalEntries(): List<JournalEntry>

    /** Deletes all journal entries from the local database (used during account deletion). */
    @Query("DELETE FROM journal_entries")
    suspend fun deleteAllJournalEntries()
}

@Dao
interface SyncQueueDao {
    @Query("SELECT * FROM sync_queue ORDER BY createdAt ASC")
    suspend fun getAllOperations(): List<SyncQueueItem>

    @Query("SELECT * FROM sync_queue ORDER BY createdAt ASC LIMIT 1")
    suspend fun peek(): SyncQueueItem?

    @Query("SELECT COUNT(*) FROM sync_queue")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SyncQueueItem): Long

    @Update
    suspend fun update(item: SyncQueueItem)

    @Delete
    suspend fun delete(item: SyncQueueItem)

    @Query("DELETE FROM sync_queue")
    suspend fun clear()

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SyncMetadataDao {
    @Query("SELECT * FROM sync_metadata WHERE key = :key")
    suspend fun getMetadata(key: String): SyncMetadata?

    @Query("SELECT * FROM sync_metadata")
    suspend fun getAllMetadata(): List<SyncMetadata>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: SyncMetadata)

    @Update
    suspend fun update(metadata: SyncMetadata)

    @Delete
    suspend fun delete(metadata: SyncMetadata)

    @Query("DELETE FROM sync_metadata WHERE key = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM sync_metadata")
    suspend fun clear()
}

@Dao
interface ChartDataDao {
    @Query("SELECT * FROM chart_data WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun getChartData(symbol: String, timeframe: String): com.stockamp.data.model.ChartDataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChartData(data: com.stockamp.data.model.ChartDataEntity)

    @Query("DELETE FROM chart_data WHERE symbol = :symbol")
    suspend fun deleteChartData(symbol: String)

    @Query("SELECT SUM(data_size) FROM chart_data")
    suspend fun getTotalCacheSize(): Long

    @Query("DELETE FROM chart_data WHERE cache_timestamp = (SELECT MIN(cache_timestamp) FROM chart_data)")
    suspend fun deleteOldestEntry()
}
