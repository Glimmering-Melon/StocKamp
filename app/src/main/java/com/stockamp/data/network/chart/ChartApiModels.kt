package com.stockamp.data.network.chart

import kotlinx.serialization.Serializable

// Giữ nguyên model cũ của bạn để UI dùng
@Serializable
data class ChartDataResponse(
    val symbol: String,
    val prices: List<PricePointResponse>
)

@Serializable
data class PricePointResponse(
    val t: Long,   // timestamp
    val o: Double, // open
    val h: Double, // high
    val l: Double, // low
    val c: Double, // close
    val v: Long    // volume
)

// THÊM MỚI: Model để hứng dữ liệu từ Finnhub Candle API
@Serializable
data class FinnhubCandleResponse(
    val c: List<Double>? = null, // List giá Close
    val h: List<Double>? = null, // List giá High
    val l: List<Double>? = null, // List giá Low
    val o: List<Double>? = null, // List giá Open
    val t: List<Long>? = null,   // List Timestamps
    val v: List<Long>? = null,   // List Volume
    val s: String                // Status (ví dụ: "ok" hoặc "no_data")
)