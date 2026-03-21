package com.stockamp.data.model

import java.time.Duration
import kotlinx.serialization.Serializable

// Domain Models

@Serializable
data class PriceDataPoint(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

enum class Timeframe(val apiValue: String, val cacheDuration: Duration) {
    ONE_DAY("1D", Duration.ofMinutes(1)),
    ONE_WEEK("1W", Duration.ofMinutes(5)),
    ONE_MONTH("1M", Duration.ofMinutes(15)),
    THREE_MONTHS("3M", Duration.ofHours(1)),
    SIX_MONTHS("6M", Duration.ofHours(4)),
    ONE_YEAR("1Y", Duration.ofHours(24))
}

enum class ChartType {
    CANDLESTICK,
    LINE
}

enum class TechnicalIndicator {
    MA20,
    MA50
}

sealed class ChartUiState {
    object Loading : ChartUiState()
    data class Success(
        val priceData: List<PriceDataPoint>,
        val chartType: ChartType,
        val timeframe: Timeframe,
        val indicators: Map<TechnicalIndicator, List<Double?>>,
        val visibleIndicators: Set<TechnicalIndicator>
    ) : ChartUiState()
    data class Error(val message: String) : ChartUiState()
}
