package com.stockamp.data.chart

import com.stockamp.data.model.Timeframe
import com.stockamp.data.network.chart.ChartDataResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

interface RemoteDataSource {
    suspend fun fetchChartData(symbol: String, timeframe: Timeframe): Result<ChartDataResponse>
}

@Singleton
class RemoteDataSourceImpl @Inject constructor(
    private val httpClient: HttpClient
) : RemoteDataSource {

    override suspend fun fetchChartData(symbol: String, timeframe: Timeframe): Result<ChartDataResponse> =
        runCatching {
            val response = httpClient.get("chart/$symbol") {
                parameter("timeframe", timeframe.apiValue)
            }
            if (!response.status.isSuccess()) {
                val msg = when (response.status.value) {
                    404 -> "Stock data not found"
                    429 -> "Rate limit exceeded, please try again later"
                    in 500..599 -> "Server error, please try again"
                    else -> "Unable to load chart data"
                }
                error(msg)
            }
            response.body<ChartDataResponse>()
        }
}
