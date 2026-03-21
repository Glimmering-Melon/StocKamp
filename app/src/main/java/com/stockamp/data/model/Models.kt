package com.stockamp.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "stocks")
data class Stock(
    @PrimaryKey
    val symbol: String,
    val name: String,
    val sector: String = "",
    val exchange: String = "",
    val currentPrice: Double = 0.0,
    val previousClose: Double = 0.0,
    val change: Double = 0.0,
    val changePercent: Double = 0.0,
    val volume: Long = 0,
    val marketCap: Long = 0,
    val high52Week: Double = 0.0,
    val low52Week: Double = 0.0,
    val logoUrl: String = "",
    val lastUpdated: Long = System.currentTimeMillis()
)

@Serializable
data class StockPrice(
    val symbol: String,
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

@Serializable
@Entity(tableName = "watchlist")
data class WatchlistItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: String = "",
    val symbol: String,
    val name: String,
    val addedAt: Long = System.currentTimeMillis(),
    
    // Sync fields
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val isDeleted: Boolean = false
)

@Serializable
@Entity(tableName = "journal_entries")
data class JournalEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: String = "",
    val symbol: String,
    val action: String, // "BUY" or "SELL"
    val quantity: Int,
    val price: Double,
    val totalValue: Double = quantity * price,
    val notes: String = "",
    val emotion: String = "", // "confident", "nervous", "neutral"
    val strategy: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    
    // Sync fields
    val modifiedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val isDeleted: Boolean = false
)

@Serializable
data class MarketSector(
    val name: String,
    val changePercent: Double,
    val stockCount: Int
)

@Serializable
data class UserProfile(
    val id: String = "",
    val email: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    val createdAt: String = ""
)

@Serializable
@Entity(tableName = "sync_queue")
data class SyncQueueItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val operationType: String, // "UPSERT_WATCHLIST", "DELETE_WATCHLIST", etc.
    val entityId: Long,
    val entityData: String, // JSON serialized entity
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastAttempt: Long? = null
)

@Serializable
@Entity(tableName = "sync_metadata")
data class SyncMetadata(
    @PrimaryKey
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)
