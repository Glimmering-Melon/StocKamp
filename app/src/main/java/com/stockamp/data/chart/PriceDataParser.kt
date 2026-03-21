package com.stockamp.data.chart

import com.stockamp.data.model.PriceDataPoint
import com.stockamp.data.network.chart.ChartDataResponse
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

interface PriceDataParser {
    fun parse(json: String): Result<List<PriceDataPoint>>
    fun validate(dataPoint: PriceDataPoint): Boolean
}

@Singleton
class PriceDataParserImpl @Inject constructor() : PriceDataParser {

    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(json: String): Result<List<PriceDataPoint>> = runCatching {
        val response = this.json.decodeFromString<ChartDataResponse>(json)
        response.prices.map { p ->
            PriceDataPoint(
                timestamp = p.t,
                open = p.o,
                high = p.h,
                low = p.l,
                close = p.c,
                volume = p.v
            )
        }.also { points ->
            points.forEach { point ->
                if (!validate(point)) {
                    val msg = buildValidationError(point)
                    throw IllegalArgumentException(msg)
                }
            }
        }
    }

    override fun validate(dataPoint: PriceDataPoint): Boolean {
        return dataPoint.high >= dataPoint.low &&
            dataPoint.high >= dataPoint.open &&
            dataPoint.high >= dataPoint.close &&
            dataPoint.low <= dataPoint.open &&
            dataPoint.low <= dataPoint.close &&
            dataPoint.volume >= 0 &&
            dataPoint.timestamp > 0
    }

    private fun buildValidationError(p: PriceDataPoint): String = when {
        p.high < p.low -> "OHLC violation: high(${p.high}) < low(${p.low})"
        p.high < p.open -> "OHLC violation: high(${p.high}) < open(${p.open})"
        p.high < p.close -> "OHLC violation: high(${p.high}) < close(${p.close})"
        p.low > p.open -> "OHLC violation: low(${p.low}) > open(${p.open})"
        p.low > p.close -> "OHLC violation: low(${p.low}) > close(${p.close})"
        p.volume < 0 -> "Invalid volume: ${p.volume}"
        else -> "Invalid timestamp: ${p.timestamp}"
    }
}
