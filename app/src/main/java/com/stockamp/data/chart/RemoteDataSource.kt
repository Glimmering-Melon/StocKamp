package com.stockamp.data.chart

import com.stockamp.BuildConfig // Import BuildConfig để lấy Key
import com.stockamp.data.model.Timeframe
import com.stockamp.data.network.chart.ChartDataResponse
import com.stockamp.data.network.chart.FinnhubCandleResponse
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
}

@Singleton
class RemoteDataSourceImpl @Inject constructor(
    private val httpClient: HttpClient
) : RemoteDataSource {

    override suspend fun fetchChartData(symbol: String, timeframe: Timeframe): Result<ChartDataResponse> =
        runCatching {
            // 1. Tính toán thời gian (UNIX Timestamp tính bằng giây)
            val toTime = System.currentTimeMillis() / 1000
            val fromTime = toTime - (30 * 24 * 60 * 60) // Lấy mặc định 30 ngày

            // 2. Gọi thẳng API của Finnhub
            val response = httpClient.get("https://finnhub.io/api/v1/stock/candle") {
                parameter("symbol", symbol)
                parameter("resolution", "D") // Nến ngày
                parameter("from", fromTime)
                parameter("to", toTime)
                parameter("token", BuildConfig.FINNHUB_KEY) // Lấy Key an toàn từ BuildConfig
            }

            if (!response.status.isSuccess()) {
                error("Lỗi mạng hoặc hết lượt API (HTTP ${response.status.value})")
            }

            val finnhubData = response.body<FinnhubCandleResponse>()

            if (finnhubData.s != "ok" || finnhubData.t == null) {
                error("Không có dữ liệu cho mã $symbol")
            }

            // 3. Chuyển đổi dữ liệu sang format của app
            val pricePoints = mutableListOf<PricePointResponse>()
            val size = finnhubData.t.size

            for (i in 0 until size) {
                pricePoints.add(
                    PricePointResponse(
                        t = finnhubData.t[i],
                        o = finnhubData.o?.get(i) ?: 0.0,
                        h = finnhubData.h?.get(i) ?: 0.0,
                        l = finnhubData.l?.get(i) ?: 0.0,
                        c = finnhubData.c?.get(i) ?: 0.0,
                        v = finnhubData.v?.get(i) ?: 0L
                    )
                )
            }

            ChartDataResponse(symbol = symbol, prices = pricePoints)
        }
}