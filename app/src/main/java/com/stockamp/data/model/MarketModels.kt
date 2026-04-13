package com.stockamp.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

data class StockSymbolInfo(
    val symbol: String,
    val name: String,
    val exchange: String,
    val sector: String?
)

data class LatestCloseResult(
    val symbol: String,
    val close: Double,
    val date: String  // ISO date string "YYYY-MM-DD"
)

@Entity(tableName = "stock_symbol_cache")
data class StockSymbolCacheEntity(
    @PrimaryKey val symbol: String,
    val name: String,
    val exchange: String,
    val sector: String?,
    val cachedAt: Long = System.currentTimeMillis()
)
