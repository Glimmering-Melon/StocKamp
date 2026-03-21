package com.stockamp.data.chart

import com.stockamp.data.model.PriceDataPoint
import com.stockamp.data.model.Timeframe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

interface PriceDataRepository {
    suspend fun getChartData(symbol: String, timeframe: Timeframe): Result<List<PriceDataPoint>>
    suspend fun clearCache(symbol: String)
}

@Singleton
class PriceDataRepositoryImpl @Inject constructor(
    private val remoteDataSource: RemoteDataSource,
    private val localDataSource: LocalDataSource,
    private val priceDataParser: PriceDataParser
) : PriceDataRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getChartData(symbol: String, timeframe: Timeframe): Result<List<PriceDataPoint>> {
        // 1. Try fresh cache
        val cached = localDataSource.getCachedData(symbol, timeframe)
        if (cached != null) return Result.success(cached)

        // 2. Fetch from network
        val remoteResult = remoteDataSource.fetchChartData(symbol, timeframe)
        if (remoteResult.isFailure) return Result.failure(remoteResult.exceptionOrNull()!!)

        val response = remoteResult.getOrThrow()
        val rawJson = json.encodeToString(response)

        // 3. Parse and validate
        val parseResult = priceDataParser.parse(rawJson)
        if (parseResult.isFailure) return Result.failure(parseResult.exceptionOrNull()!!)

        val data = parseResult.getOrThrow()

        // 4. Cache and return
        runCatching { localDataSource.saveData(symbol, timeframe, data) }
        return Result.success(data)
    }

    override suspend fun clearCache(symbol: String) = localDataSource.clearCache(symbol)
}
