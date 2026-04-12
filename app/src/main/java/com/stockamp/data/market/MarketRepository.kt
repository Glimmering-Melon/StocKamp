package com.stockamp.data.market

import com.stockamp.data.model.LatestCloseResult
import com.stockamp.data.model.PriceDataPoint
import com.stockamp.data.model.StockSymbolInfo

interface MarketRepository {
    suspend fun getOhlcv(symbol: String, timeframe: String): Result<List<PriceDataPoint>>
    suspend fun getAvailableSymbols(): Result<List<StockSymbolInfo>>
    suspend fun getLatestClose(symbol: String): Result<LatestCloseResult?>
    suspend fun prefetchSymbols()
}
