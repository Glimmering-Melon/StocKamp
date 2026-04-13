package com.stockamp.data.local

import androidx.room.*
import com.stockamp.data.model.StockSymbolCacheEntity

@Dao
interface StockSymbolCacheDao {
    @Query("SELECT * FROM stock_symbol_cache")
    suspend fun getAll(): List<StockSymbolCacheEntity>

    @Query("SELECT * FROM stock_symbol_cache WHERE symbol = :symbol")
    suspend fun getBySymbol(symbol: String): StockSymbolCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(symbols: List<StockSymbolCacheEntity>)

    @Query("DELETE FROM stock_symbol_cache")
    suspend fun deleteAll()
}
