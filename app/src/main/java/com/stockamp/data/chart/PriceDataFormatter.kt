package com.stockamp.data.chart

import com.stockamp.data.model.Timeframe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

interface PriceDataFormatter {
    fun formatPrice(price: Double, precision: Int = 2): String
    fun formatTimestamp(timestamp: Long, timeframe: Timeframe): String
    fun formatVolume(volume: Long): String
}

@Singleton
class PriceDataFormatterImpl @Inject constructor() : PriceDataFormatter {

    override fun formatPrice(price: Double, precision: Int): String =
        "%.${precision}f".format(price)

    override fun formatTimestamp(timestamp: Long, timeframe: Timeframe): String {
        val date = Date(timestamp * 1000L)
        val pattern = when (timeframe) {
            Timeframe.ONE_DAY -> "HH:mm"
            Timeframe.ONE_WEEK, Timeframe.ONE_MONTH -> "MMM dd"
            Timeframe.THREE_MONTHS, Timeframe.SIX_MONTHS, Timeframe.ONE_YEAR -> "MMM yyyy"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
    }

    override fun formatVolume(volume: Long): String = when {
        volume >= 1_000_000_000L -> "%.1fB".format(volume / 1_000_000_000.0)
        volume >= 1_000_000L -> "%.1fM".format(volume / 1_000_000.0)
        volume >= 1_000L -> "%.1fK".format(volume / 1_000.0)
        else -> volume.toString()
    }
}
