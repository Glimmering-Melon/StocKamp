package com.stockamp.ui.market

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stockamp.data.model.ChartType
import com.stockamp.data.model.ChartUiState
import com.stockamp.data.model.TechnicalIndicator
import com.stockamp.data.model.Timeframe

@Composable
fun ChartComponent(
    chartState: ChartUiState,
    onTimeframeSelected: (Timeframe) -> Unit,
    onChartTypeToggled: (ChartType) -> Unit,
    onIndicatorToggled: (TechnicalIndicator) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 1. TẠO TRÍ NHỚ CHO GIAO DIỆN ĐỂ KHÔNG BỊ NHẢY NÚT
    var selectedTimeframe by remember { mutableStateOf(Timeframe.ONE_MONTH) }

    // Đồng bộ lại nếu ViewModel có dữ liệu mới
    LaunchedEffect(chartState) {
        if (chartState is ChartUiState.Success) {
            selectedTimeframe = chartState.timeframe
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Timeframe selector row
        TimeframeSelector(
            selected = selectedTimeframe,
            onSelected = { tf ->
                selectedTimeframe = tf // Cập nhật nút sáng đèn ngay lập tức
                onTimeframeSelected(tf) // Gọi API
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Chart type toggle + indicator toggles
        if (chartState is ChartUiState.Success) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChartTypeToggle(
                    current = chartState.chartType,
                    onToggle = onChartTypeToggled
                )
                IndicatorToggles(
                    visibleIndicators = chartState.visibleIndicators,
                    onToggle = onIndicatorToggled
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Chart display area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(
                targetState = chartState,
                animationSpec = tween(durationMillis = 300)
            ) { state ->
                when (state) {
                    is ChartUiState.Loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                    is ChartUiState.Error -> ChartErrorView(message = state.message, onRetry = onRetry)
                    is ChartUiState.Success -> ChartDisplayArea(state = state)
                }
            }
        }
    }
}

@Composable
fun TimeframeSelector(
    selected: Timeframe,
    onSelected: (Timeframe) -> Unit,
    modifier: Modifier = Modifier
) {
    // Dùng LazyRow để các nút không bị ép chèn lên nhau
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(Timeframe.entries.toTypedArray()) { tf ->
            val isSelected = tf == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelected(tf) },
                label = { Text(tf.apiValue) } // Hiện chữ 1D, 1M, 1Y...
            )
        }
    }
}

@Composable
fun ChartTypeToggle(
    current: ChartType,
    onToggle: (ChartType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Candle", style = MaterialTheme.typography.labelMedium)
        Switch(
            checked = current == ChartType.LINE,
            onCheckedChange = { isLine ->
                onToggle(if (isLine) ChartType.LINE else ChartType.CANDLESTICK)
            },
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Text("Line", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun ChartErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Thử lại") }
    }
}

@Composable
fun ChartDisplayArea(
    state: ChartUiState.Success,
    modifier: Modifier = Modifier
) {
    PriceChartWithVolume(state = state, modifier = modifier.fillMaxSize())
}

@Composable
fun IndicatorToggles(
    visibleIndicators: Set<TechnicalIndicator>,
    onToggle: (TechnicalIndicator) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(TechnicalIndicator.entries.toTypedArray()) { indicator ->
            FilterChip(
                selected = indicator in visibleIndicators,
                onClick = { onToggle(indicator) },
                label = { Text(indicator.name) }
            )
        }
    }
}