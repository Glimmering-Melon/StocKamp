package com.stockamp.data.market

object ProfitMarginCalculator {
    fun calculate(entryPrice: Double, latestClose: Double): Double =
        (latestClose - entryPrice) / entryPrice * 100.0
}
