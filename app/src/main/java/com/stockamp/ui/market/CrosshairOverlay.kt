package com.stockamp.ui.market

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.stockamp.data.chart.PriceDataFormatter
import com.stockamp.data.model.PriceDataPoint
import com.stockamp.data.model.Timeframe

private val CrosshairColor = Color(0xFFBDBDBD)
private const val SNAP_THRESHOLD_DP = 20f

/**
 * Draws crosshair lines and an info overlay for the nearest data point.
 * Snaps to the nearest data point within [SNAP_THRESHOLD_DP] dp.
 */
@Composable
fun CrosshairOverlay(
    touchPosition: Offset,
    priceData: List<PriceDataPoint>,
    timeframe: Timeframe,
    formatter: PriceDataFormatter,
    modifier: Modifier = Modifier
) {
    if (priceData.isEmpty()) return

    val density = LocalDensity.current
    val snapThresholdPx = with(density) { SNAP_THRESHOLD_DP.dp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stepX = size.width / priceData.size

            // Find nearest data point
            val nearestIndex = priceData.indices.minByOrNull { i ->
                val cx = i * stepX + stepX / 2
                kotlin.math.abs(cx - touchPosition.x)
            } ?: return@Canvas

            val nearestX = nearestIndex * stepX + stepX / 2
            val snappedX = if (kotlin.math.abs(nearestX - touchPosition.x) <= snapThresholdPx) {
                nearestX
            } else {
                touchPosition.x
            }

            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

            // Vertical crosshair line
            drawLine(
                color = CrosshairColor,
                start = Offset(snappedX, 0f),
                end = Offset(snappedX, size.height),
                pathEffect = dashEffect,
                strokeWidth = 1.5f
            )
            // Horizontal crosshair line
            drawLine(
                color = CrosshairColor,
                start = Offset(0f, touchPosition.y),
                end = Offset(size.width, touchPosition.y),
                pathEffect = dashEffect,
                strokeWidth = 1.5f
            )
        }

        // Info overlay
        val nearestIndex = run {
            val stepX = with(density) { 1.dp.toPx() } // approximate
            priceData.indices.minByOrNull { i ->
                val cx = i.toFloat() / priceData.size
                kotlin.math.abs(cx - touchPosition.x)
            } ?: 0
        }
        val point = priceData.getOrNull(nearestIndex) ?: return@Box

        CrosshairInfoBox(
            point = point,
            timeframe = timeframe,
            formatter = formatter,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        )
    }
}

@Composable
fun CrosshairInfoBox(
    point: PriceDataPoint,
    timeframe: Timeframe,
    formatter: PriceDataFormatter,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        shape = MaterialTheme.shapes.small,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = formatter.formatTimestamp(point.timestamp, timeframe),
                style = MaterialTheme.typography.labelSmall
            )
            Text("O: ${formatter.formatPrice(point.open)}", style = MaterialTheme.typography.bodySmall)
            Text("H: ${formatter.formatPrice(point.high)}", style = MaterialTheme.typography.bodySmall)
            Text("L: ${formatter.formatPrice(point.low)}", style = MaterialTheme.typography.bodySmall)
            Text("C: ${formatter.formatPrice(point.close)}", style = MaterialTheme.typography.bodySmall)
            Text("V: ${formatter.formatVolume(point.volume)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
