package com.stockamp.data.network

import kotlinx.serialization.Serializable

/**
 * Response from Finnhub /quote endpoint
 * GET https://finnhub.io/api/v1/quote?symbol=AAPL&token=KEY
 */
@Serializable
data class FinnhubQuoteResponse(
    val c: Double = 0.0,   // Current price
    val d: Double = 0.0,   // Change
    val dp: Double = 0.0,  // Percent change
    val h: Double = 0.0,   // High price of the day
    val l: Double = 0.0,   // Low price of the day
    val o: Double = 0.0,   // Open price of the day
    val pc: Double = 0.0,  // Previous close price
    val t: Long = 0        // Timestamp
)

/**
 * Response from Finnhub /stock/candle endpoint
 * GET https://finnhub.io/api/v1/stock/candle?symbol=AAPL&resolution=D&from=X&to=X&token=KEY
 *
 * Arrays are parallel: c[i], h[i], l[i], o[i], t[i], v[i] all refer to the same candle.
 */
@Serializable
data class FinnhubCandleResponse(
    val c: List<Double> = emptyList(),  // Close prices
    val h: List<Double> = emptyList(),  // High prices
    val l: List<Double> = emptyList(),  // Low prices
    val o: List<Double> = emptyList(),  // Open prices
    val t: List<Long> = emptyList(),    // Timestamps (UNIX seconds)
    val v: List<Long> = emptyList(),    // Volume
    val s: String = ""                   // Status: "ok" or "no_data"
)
