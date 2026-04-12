package com.stockamp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stockamp.data.model.Stock
import com.stockamp.data.model.WatchlistItem
import com.stockamp.data.model.JournalEntry
import com.stockamp.data.model.SyncQueueItem
import com.stockamp.data.model.SyncMetadata
import com.stockamp.data.model.ChartDataEntity
import com.stockamp.data.model.NewsArticleEntity
import com.stockamp.data.model.NewsConverters
import com.stockamp.data.model.StockSymbolCacheEntity

@Database(
    entities = [
        Stock::class,
        WatchlistItem::class,
        JournalEntry::class,
        SyncQueueItem::class,
        SyncMetadata::class,
        ChartDataEntity::class,
        NewsArticleEntity::class,
        StockSymbolCacheEntity::class
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(NewsConverters::class)
abstract class StocKampDatabase : RoomDatabase() {
    abstract fun stockDao(): StockDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun journalDao(): JournalDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun syncMetadataDao(): SyncMetadataDao
    abstract fun chartDataDao(): ChartDataDao
    abstract fun newsDao(): NewsDao
    abstract fun stockSymbolCacheDao(): StockSymbolCacheDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add sync fields to watchlist table
                database.execSQL(
                    "ALTER TABLE watchlist ADD COLUMN userId TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE watchlist ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE watchlist ADD COLUMN modifiedAt INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE watchlist ADD COLUMN syncedAt INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE watchlist ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0"
                )
                
                // Add sync fields to journal_entries table
                database.execSQL(
                    "ALTER TABLE journal_entries ADD COLUMN userId TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE journal_entries ADD COLUMN modifiedAt INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE journal_entries ADD COLUMN syncedAt INTEGER"
                )
                database.execSQL(
                    "ALTER TABLE journal_entries ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0"
                )
                
                // Create sync_queue table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_queue (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        operationType TEXT NOT NULL,
                        entityId INTEGER NOT NULL,
                        entityData TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        retryCount INTEGER NOT NULL,
                        lastAttempt INTEGER
                    )
                    """.trimIndent()
                )
                
                // Create sync_metadata table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_metadata (
                        key TEXT PRIMARY KEY NOT NULL,
                        value TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                
                // Set default values for existing records
                val currentTime = System.currentTimeMillis()
                database.execSQL(
                    "UPDATE watchlist SET createdAt = addedAt, modifiedAt = addedAt WHERE createdAt = 0"
                )
                database.execSQL(
                    "UPDATE journal_entries SET modifiedAt = createdAt WHERE modifiedAt = 0"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chart_data (
                        symbol TEXT NOT NULL,
                        timeframe TEXT NOT NULL,
                        data_json TEXT NOT NULL,
                        cache_timestamp INTEGER NOT NULL,
                        data_size INTEGER NOT NULL,
                        PRIMARY KEY(symbol, timeframe)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS news_articles (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        url TEXT NOT NULL,
                        summary TEXT,
                        source_name TEXT NOT NULL,
                        published_at INTEGER NOT NULL,
                        stock_symbols TEXT NOT NULL,
                        sentiment_label TEXT,
                        sentiment_score REAL,
                        status TEXT NOT NULL,
                        cached_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS stock_symbol_cache (
                        symbol TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        exchange TEXT NOT NULL,
                        sector TEXT,
                        cachedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE journal_entries_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        userId TEXT NOT NULL DEFAULT '',
                        symbol TEXT NOT NULL,
                        action TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        transactionDate TEXT NOT NULL DEFAULT '',
                        notes TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL,
                        modifiedAt INTEGER NOT NULL DEFAULT 0,
                        syncedAt INTEGER,
                        isDeleted INTEGER NOT NULL DEFAULT 0
                    )
                """)
                database.execSQL("""
                    INSERT INTO journal_entries_new
                        (id, userId, symbol, action, quantity, transactionDate, notes,
                         createdAt, modifiedAt, syncedAt, isDeleted)
                    SELECT id, userId, symbol, action, quantity, '', notes,
                           createdAt, modifiedAt, syncedAt, isDeleted
                    FROM journal_entries
                """)
                database.execSQL("DROP TABLE journal_entries")
                database.execSQL("ALTER TABLE journal_entries_new RENAME TO journal_entries")
            }
        }
    }
}
