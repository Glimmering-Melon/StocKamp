package com.stockamp.data.network.chart

import kotlinx.serialization.Serializable

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
