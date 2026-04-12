package com.stockamp.ui.market
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.stockamp.data.model.ChartType
import com.stockamp.data.model.ChartUiState
import com.stockamp.data.model.PriceDataPoint
import com.stockamp.data.model.TechnicalIndicator

private val ColorUp = Color(0xFF26A69A)
private val ColorDown = Color(0xFFEF5350)
private val ColorNeutral = Color(0xFF9E9E9E)
private val ColorMA20 = Color(0xFFFFA726)
private val ColorMA50 = Color(0xFF7E57C2)

@Composable
fun PriceChartWithVolume(
    state: ChartUiState.Success,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
            Crossfade(
                targetState = state.timeframe to state.priceData,
                animationSpec = tween(durationMillis = 250),
                label = "timeframe_crossfade"
            ) { (_, data) ->
                Crossfade(
                    targetState = state.chartType,
                    animationSpec = tween(durationMillis = 200),
                    label = "chart_type_crossfade"
                ) { chartType ->
                    when (chartType) {
                        ChartType.CANDLESTICK -> CandlestickChart(data = data, modifier = Modifier.fillMaxSize())
                        ChartType.LINE -> LineChart(data = data, modifier = Modifier.fillMaxSize())
                    }
                }
            }

            if (state.priceData.isNotEmpty()) {
                val ma20 = state.indicators[TechnicalIndicator.MA20]
                val ma50 = state.indicators[TechnicalIndicator.MA50]
                if (TechnicalIndicator.MA20 in state.visibleIndicators && ma20 != null) {
                    MAOverlay(values = ma20, priceData = state.priceData, color = ColorMA20, modifier = Modifier.fillMaxSize())
                }
                if (TechnicalIndicator.MA50 in state.visibleIndicators && ma50 != null) {
                    MAOverlay(values = ma50, priceData = state.priceData, color = ColorMA50, modifier = Modifier.fillMaxSize())
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        VolumeChart(
            data = state.priceData,
            modifier = Modifier.fillMaxWidth().height(80.dp)
        )
    }
}

@Composable
fun CandlestickChart(data: List<PriceDataPoint>, modifier: Modifier = Modifier) {
    if (data.isEmpty()) return
    val minPrice = data.minOf { it.low }
    val maxPrice = data.maxOf { it.high }
    val priceRange = (maxPrice - minPrice).takeIf { it > 0 } ?: 1.0

    Canvas(modifier = modifier) {
        val candleWidth = size.width / data.size
        val bodyWidth = candleWidth * 0.6f

        data.forEachIndexed { i, point ->
            val color = when {
                point.close > point.open -> ColorUp
                point.close < point.open -> ColorDown
                else -> ColorNeutral
            }
            val centerX = i * candleWidth + candleWidth / 2

            // Wick
            val wickTop = size.height - ((point.high - minPrice) / priceRange * size.height).toFloat()
            val wickBottom = size.height - ((point.low - minPrice) / priceRange * size.height).toFloat()
            drawLine(color = color, start = Offset(centerX, wickTop), end = Offset(centerX, wickBottom), strokeWidth = 1.5f)

            // Body
            val bodyTop = size.height - ((maxOf(point.open, point.close) - minPrice) / priceRange * size.height).toFloat()
            val bodyBottom = size.height - ((minOf(point.open, point.close) - minPrice) / priceRange * size.height).toFloat()
            val bodyHeight = (bodyBottom - bodyTop).coerceAtLeast(1f)
            drawRect(
                color = color,
                topLeft = Offset(centerX - bodyWidth / 2, bodyTop),
                size = Size(bodyWidth, bodyHeight)
            )
        }
    }
}

@Composable
fun LineChart(data: List<PriceDataPoint>, modifier: Modifier = Modifier) {
    if (data.isEmpty()) return
    val minPrice = data.minOf { it.close }
    val maxPrice = data.maxOf { it.close }
    val priceRange = (maxPrice - minPrice).takeIf { it > 0 } ?: 1.0

    Canvas(modifier = modifier) {
        val stepX = size.width / (data.size - 1).coerceAtLeast(1)
        val path = Path()
        val fillPath = Path()

        data.forEachIndexed { i, point ->
            val x = i * stepX
            val y = size.height - ((point.close - minPrice) / priceRange * size.height).toFloat()
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(size.width, size.height)
        fillPath.close()

        drawPath(path = fillPath, color = ColorUp.copy(alpha = 0.15f))
        drawPath(path = path, color = ColorUp, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun VolumeChart(data: List<PriceDataPoint>, modifier: Modifier = Modifier) {
    if (data.isEmpty()) return
    val maxVolume = data.maxOf { it.volume }.toFloat()

    Canvas(modifier = modifier.fillMaxWidth()) {
        val barWidth = size.width / data.size
        data.forEachIndexed { i, point ->
            val color = when {
                point.close > point.open -> ColorUp
                point.close < point.open -> ColorDown
                else -> ColorNeutral
            }
            val barHeight = if (maxVolume > 0) (point.volume / maxVolume) * size.height else 0f
            drawRect(
                color = color,
                topLeft = Offset(x = i * barWidth, y = size.height - barHeight),
                size = Size(width = barWidth * 0.8f, height = barHeight)
            )
        }
    }
}

@Composable
fun MAOverlay(values: List<Double?>, priceData: List<PriceDataPoint>, color: Color, modifier: Modifier = Modifier) {
    if (values.isEmpty() || priceData.isEmpty()) return
    val minPrice = priceData.minOf { it.low }
    val maxPrice = priceData.maxOf { it.high }
    val priceRange = (maxPrice - minPrice).takeIf { it > 0 } ?: 1.0

    Canvas(modifier = modifier) {
        val stepX = size.width / values.size
        val path = Path()
        var started = false

        values.forEachIndexed { i, value ->
            if (value == null) { started = false; return@forEachIndexed }
            val x = i * stepX + stepX / 2
            val y = size.height - ((value - minPrice) / priceRange * size.height).toFloat()
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = Stroke(width = 3f))
    }
}
