package com.stockamp.data.network.chart

import kotlinx.serialization.Serializable

// ----------------------------------------------------
// 1. CÁC MODEL DÙNG CHO UI CỦA APP (Giữ nguyên)
// ----------------------------------------------------
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

// ----------------------------------------------------
// 2. CÁC MODEL ĐỂ HỨNG DỮ LIỆU TỪ FINNHUB
// ----------------------------------------------------

// Model hứng dữ liệu Biểu đồ nến (Candle)
@Serializable
data class FinnhubCandleResponse(
    val c: List<Double>? = null, // List giá Close
    val h: List<Double>? = null, // List giá High
    val l: List<Double>? = null, // List giá Low
    val o: List<Double>? = null, // List giá Open
    val t: List<Long>? = null,   // List Timestamps
    val v: List<Long>? = null,   // List Volume
    val s: String                // Status ("ok" hoặc "no_data")
)

// Model hứng dữ liệu Tìm kiếm (Search)
// THÊM MODEL HỨNG KẾT QUẢ TÌM KIẾM
@Serializable
data class FinnhubSearchResponse(
    val count: Int,
    val result: List<FinnhubSearchResult>
)

@Serializable
data class FinnhubSearchResult(
    val description: String,
    val displaySymbol: String,
    val symbol: String,
    val type: String
)