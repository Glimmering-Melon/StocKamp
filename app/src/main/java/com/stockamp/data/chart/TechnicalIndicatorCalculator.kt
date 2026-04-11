package com.stockamp.data.chart

import com.stockamp.data.model.PriceDataPoint
import javax.inject.Inject
import javax.inject.Singleton

interface TechnicalIndicatorCalculator {
    fun calculateMA(data: List<PriceDataPoint>, period: Int): List<Double?>
    fun calculateSMA(values: List<Double>, period: Int): List<Double?>
}

@Singleton
class TechnicalIndicatorCalculatorImpl @Inject constructor() : TechnicalIndicatorCalculator {

    override fun calculateMA(data: List<PriceDataPoint>, period: Int): List<Double?> =
        calculateSMA(data.map { it.close }, period)

    override fun calculateSMA(values: List<Double>, period: Int): List<Double?> {
        if (values.size < period) return List(values.size) { null }
        return values.indices.map { i ->
            if (i < period - 1) null
            else values.subList(i - period + 1, i + 1).average()
        }
    }
}
