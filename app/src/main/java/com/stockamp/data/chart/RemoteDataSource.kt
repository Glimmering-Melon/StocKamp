package com.stockamp.data.chart

import com.stockamp.BuildConfig
import com.stockamp.data.model.Timeframe
import com.stockamp.data.network.chart.AlphaVantageResponse // Đã import Model từ file khác
import com.stockamp.data.network.chart.ChartDataResponse
import com.stockamp.data.network.chart.FinnhubSearchResponse
import com.stockamp.data.network.chart.PricePointResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import java.text.SimpleDateFormat
import java.util.TimeZone
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
            // 1. SỬ DỤNG ALPHA VANTAGE ĐỂ VẼ BIỂU ĐỒ (Ổn định 100%)
            val alphaVantageKeys = listOf(
                "UOURIOCWX52JL0TL",
                "82UQPGCK41Z3XOM3",
                "YEVBH91JMXQIO6G0",
                "I23OUCQUJ2NFDSAG",
                "U64GFQ4MV2HK42A2"
            )
            val apiKey = alphaVantageKeys.random()

            // Lấy dữ liệu: compact = 100 ngày qua, full = 20 năm
            val outputSize = if (timeframe == Timeframe.ONE_DAY || timeframe == Timeframe.ONE_WEEK || timeframe == Timeframe.ONE_MONTH || timeframe == Timeframe.THREE_MONTHS) {
                "compact"
            } else {
                "full"
            }

            val response = httpClient.get("https://www.alphavantage.co/query") {
                parameter("function", "TIME_SERIES_DAILY")
                parameter("symbol", symbol)
                parameter("outputsize", outputSize)
                parameter("apikey", apiKey)
            }

            if (!response.status.isSuccess()) error("Lỗi kết nối Alpha Vantage: HTTP ${response.status.value}")

            val data = response.body<AlphaVantageResponse>()

            // Xử lý các thông báo lỗi từ Alpha Vantage
            if (data.errorMessage != null) error("Alpha Vantage không hỗ trợ biểu đồ mã $symbol")
            if (data.note != null) error("Đã hết lượt API Alpha Vantage (Giới hạn 25 lần/ngày). Hãy thử lại sau!")
            if (data.info != null) error("Thông báo từ Alpha Vantage: ${data.info}")

            val timeSeries = data.timeSeries ?: error("Không có dữ liệu biểu đồ nến.")

            val dateFormat = SimpleDateFormat("yyyy-MM-dd").apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            // Lọc dữ liệu theo khung thời gian bạn bấm
            val toTime = System.currentTimeMillis()
            val oneDayMs = 86400000L
            val fromTimeMs = when (timeframe) {
                Timeframe.ONE_DAY -> toTime - (5L * oneDayMs)
                Timeframe.ONE_WEEK -> toTime - (14L * oneDayMs)
                Timeframe.ONE_MONTH -> toTime - (30L * oneDayMs)
                Timeframe.THREE_MONTHS -> toTime - (90L * oneDayMs)
                Timeframe.SIX_MONTHS -> toTime - (180L * oneDayMs)
                Timeframe.ONE_YEAR -> toTime - (365L * oneDayMs)
            }

            val pricePoints = mutableListOf<PricePointResponse>()

            for ((dateStr, candle) in timeSeries) {
                val dateMs = dateFormat.parse(dateStr)?.time ?: continue
                if (dateMs in fromTimeMs..toTime) {
                    pricePoints.add(
                        PricePointResponse(
                            t = dateMs / 1000, // Đổi về giây cho khớp với UI
                            o = candle.open.toDoubleOrNull() ?: 0.0,
                            h = candle.high.toDoubleOrNull() ?: 0.0,
                            l = candle.low.toDoubleOrNull() ?: 0.0,
                            c = candle.close.toDoubleOrNull() ?: 0.0,
                            v = candle.volume.toLongOrNull() ?: 0L
                        )
                    )
                }
            }

            // Sắp xếp lại từ cũ đến mới để Canvas vẽ đúng chiều
            pricePoints.sortBy { it.t }

            if (pricePoints.isEmpty()) {
                error("Không có dữ liệu nến trong khoảng thời gian này.")
            }

            ChartDataResponse(symbol = symbol, prices = pricePoints)
        }

    override suspend fun searchStocks(query: String): Result<FinnhubSearchResponse> =
        runCatching {
            // 2. VẪN GIỮ FINNHUB CHO TÌM KIẾM (Vì nó rất nhanh và không giới hạn gắt gao)
            val response = httpClient.get("https://finnhub.io/api/v1/search") {
                parameter("q", query)
                parameter("token", BuildConfig.FINNHUB_KEY)
            }
            if (!response.status.isSuccess()) error("Search Failed")

            val rawData = response.body<FinnhubSearchResponse>()
            val usStocksOnly = rawData.result.filter { !it.symbol.contains(".") } // Xoá mã OTC
            FinnhubSearchResponse(count = usStocksOnly.size, result = usStocksOnly)
        }
}