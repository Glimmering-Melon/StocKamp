package com.stockamp.data.chart

import com.stockamp.BuildConfig
import com.stockamp.data.model.Timeframe
import com.stockamp.data.network.chart.ChartDataResponse
import com.stockamp.data.network.chart.FinnhubCandleResponse
import com.stockamp.data.network.chart.FinnhubSearchResponse
import com.stockamp.data.network.chart.PricePointResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

interface RemoteDataSource {
    suspend fun fetchChartData(symbol: String, timeframe: Timeframe): Result<ChartDataResponse>
    suspend fun searchStocks(query: String): Result<FinnhubSearchResponse>
}

@Singleton
class RemoteDataSourceImpl @Inject constructor(
    private val httpClient: HttpClient
) : RemoteDataSource {

    override suspend fun fetchChartData(symbol: String, timeframe: Timeframe): Result<ChartDataResponse> =
        runCatching {
            val toTime = System.currentTimeMillis() / 1000
            val fromTime = toTime - (30 * 24 * 60 * 60)

            val response = httpClient.get("https://finnhub.io/api/v1/stock/candle") {
                parameter("symbol", symbol)
                parameter("resolution", "D")
                parameter("from", fromTime)
                parameter("to", toTime)
                parameter("token", BuildConfig.FINNHUB_KEY)
            }

            if (!response.status.isSuccess()) error("HTTP Error")
            val finnhubData = response.body<FinnhubCandleResponse>()
            if (finnhubData.s != "ok" || finnhubData.t == null) error("No data")

            val pricePoints = mutableListOf<PricePointResponse>()
            for (i in finnhubData.t.indices) {
                pricePoints.add(PricePointResponse(finnhubData.t[i], finnhubData.o?.get(i) ?: 0.0, finnhubData.h?.get(i) ?: 0.0, finnhubData.l?.get(i) ?: 0.0, finnhubData.c?.get(i) ?: 0.0, finnhubData.v?.get(i) ?: 0L))
            }
            ChartDataResponse(symbol = symbol, prices = pricePoints)
        }

    override suspend fun searchStocks(query: String): Result<FinnhubSearchResponse> =
        runCatching {
            val response = httpClient.get("https://finnhub.io/api/v1/search") {
                parameter("q", query)
                parameter("token", BuildConfig.FINNHUB_KEY)
            }
            if (!response.status.isSuccess()) error("Search Failed")
            response.body<FinnhubSearchResponse>()
        }
}