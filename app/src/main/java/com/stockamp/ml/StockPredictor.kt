package com.stockamp.ml

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

private const val MODEL_FILE_NAME = "lstm_stock_model.tflite"
private const val MIN_CLOSE_PRICES = 10
private const val TIME_STEPS = 30
private const val FORECAST_DAYS = 30

@Singleton
class StockPredictor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val interpreter: Interpreter by lazy {
        Interpreter(loadModelFile())
    }

    @Synchronized
    fun predict30DaysAhead(recentClosePrices: List<Double>): List<Double> {
        return try {
            val cleanPrices = recentClosePrices
                .filter { it.isFinite() && it > 0.0 }
                .takeLast(TIME_STEPS)

            if (cleanPrices.size < MIN_CLOSE_PRICES) return emptyList()

            val rollingWindow = padToTimeSteps(cleanPrices).toMutableList()
            val forecasts = mutableListOf<Double>()

            repeat(FORECAST_DAYS) {
                val minPrice = rollingWindow.minOrNull() ?: return@repeat
                val maxPrice = rollingWindow.maxOrNull() ?: return@repeat
                val priceRange = max(maxPrice - minPrice, 1e-9)

                // Local MinMax scale: normalize only the current 30-day window.
                val input = Array(1) { Array(TIME_STEPS) { FloatArray(1) } }
                rollingWindow.forEachIndexed { index, price ->
                    input[0][index][0] = ((price - minPrice) / priceRange).toFloat()
                }

                val output = Array(1) { FloatArray(1) }
                interpreter.run(input, output)

                // De-normalize with the same local min/max, then roll the window forward.
                val normalizedPrediction = output[0][0].toDouble().coerceIn(0.0, 1.0)
                val predictedPrice = normalizedPrediction * priceRange + minPrice

                forecasts += predictedPrice
                rollingWindow.removeAt(0)
                rollingWindow += predictedPrice
            }

            forecasts
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        context.assets.openFd(MODEL_FILE_NAME).use { assetFileDescriptor ->
            FileInputStream(assetFileDescriptor.fileDescriptor).use { inputStream ->
                val fileChannel = inputStream.channel
                return fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.declaredLength
                )
            }
        }
    }

    private fun padToTimeSteps(prices: List<Double>): List<Double> {
        if (prices.size >= TIME_STEPS) return prices.takeLast(TIME_STEPS)

        val firstPrice = prices.first()
        val padding = List(TIME_STEPS - prices.size) { firstPrice }
        return padding + prices
    }
}
