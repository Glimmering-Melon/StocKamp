package com.stockamp.data.network.chart

import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


// 1. CÁC MODEL DÙNG CHO UI CỦA APP (Giữ nguyên)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ChartDataResponse(
    val symbol: String,
    val prices: List<PricePointResponse>
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class PricePointResponse(
    val t: Long,   // timestamp
    val o: Double, // open
    val h: Double, // high
    val l: Double, // low
    val c: Double, // close
    val v: Long    // volume
)


// 2. CÁC MODEL ĐỂ HỨNG DỮ LIỆU TỪ FINNHUB

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class FinnhubCandleResponse(
    val c: List<Double>? = null,
    val h: List<Double>? = null,
    val l: List<Double>? = null,
    val o: List<Double>? = null,
    val t: List<Long>? = null,
    val v: List<Long>? = null,
    val s: String
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class FinnhubSearchResponse(
    val count: Int,
    val result: List<FinnhubSearchResult>
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class FinnhubSearchResult(
    val description: String,
    val displaySymbol: String,
    val symbol: String,
    val type: String
)


// 3. CÁC MODEL ĐỂ HỨNG DỮ LIỆU TỪ ALPHA VANTAGE

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class AlphaVantageResponse(
    @SerialName("Time Series (Daily)") val timeSeries: Map<String, AlphaVantageCandle>? = null,
    @SerialName("Error Message") val errorMessage: String? = null,
    @SerialName("Note") val note: String? = null,
    @SerialName("Information") val info: String? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class AlphaVantageCandle(
    @SerialName("1. open") val open: String,
    @SerialName("2. high") val high: String,
    @SerialName("3. low") val low: String,
    @SerialName("4. close") val close: String,
    @SerialName("5. volume") val volume: String
)