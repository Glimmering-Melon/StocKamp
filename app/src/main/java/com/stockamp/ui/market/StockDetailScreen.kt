package com.stockamp.ui.market

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stockamp.data.model.StockPrice
import com.stockamp.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    symbol: String,
    onNavigateBack: () -> Unit,
    viewModel: StockDetailViewModel = hiltViewModel(),
    chartViewModel: ChartViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chartState by chartViewModel.chartState.collectAsStateWithLifecycle()

    LaunchedEffect(symbol) {
        viewModel.loadStock(symbol)
        chartViewModel.loadChartData(symbol)
    }

    val stock = uiState.stock
    val isPositive = stock != null && stock.change >= 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(symbol, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleWatchlist() }) {
                        Icon(
                            if (uiState.isInWatchlist) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "Watchlist",
                            tint = if (uiState.isInWatchlist) AccentYellow else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        if (uiState.isLoading || stock == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                // Stock Info Header
                Text(
                    stock.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        String.format("%,.0f", stock.currentPrice),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "VNĐ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isPositive) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (isPositive) AccentGreen else AccentRed
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "${if (isPositive) "+" else ""}${String.format("%,.0f", stock.change)} (${String.format("%.2f", stock.changePercent)}%)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPositive) AccentGreen else AccentRed
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Interactive Price Chart
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    ChartComponent(
                        chartState = chartState,
                        onTimeframeSelected = { chartViewModel.updateTimeframe(it) },
                        onChartTypeToggled = { chartViewModel.toggleChartType(it) },
                        onIndicatorToggled = { chartViewModel.toggleIndicator(it) },
                        onRetry = { chartViewModel.retryLoad() },
                        modifier = Modifier.padding(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Key Stats
                Text(
                    "Thông tin chi tiết",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        StatRow("Sàn", stock.exchange)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        StatRow("Ngành", stock.sector)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        StatRow("Khối lượng", String.format("%,d", stock.volume))
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        StatRow("Vốn hóa", formatMarketCap(stock.marketCap))
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        StatRow("Đỉnh 52 tuần", String.format("%,.0f", stock.high52Week))
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        StatRow("Đáy 52 tuần", String.format("%,.0f", stock.low52Week))
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        StatRow("Giá đóng cửa trước", String.format("%,.0f", stock.previousClose))
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun StockChart(
    prices: List<StockPrice>,
    isPositive: Boolean,
    modifier: Modifier = Modifier
) {
    val lineColor = if (isPositive) AccentGreen else AccentRed
    val fillColor = if (isPositive)
        Brush.verticalGradient(listOf(AccentGreen.copy(alpha = 0.3f), AccentGreen.copy(alpha = 0.0f)))
    else
        Brush.verticalGradient(listOf(AccentRed.copy(alpha = 0.3f), AccentRed.copy(alpha = 0.0f)))

    val closePrices = prices.map { it.close.toFloat() }
    val minPrice = closePrices.minOrNull() ?: 0f
    val maxPrice = closePrices.maxOrNull() ?: 1f
    val priceRange = (maxPrice - minPrice).coerceAtLeast(1f)

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val stepX = width / (closePrices.size - 1).coerceAtLeast(1)

        // Draw line
        val linePath = Path()
        val fillPath = Path()

        closePrices.forEachIndexed { index, price ->
            val x = index * stepX
            val y = height - ((price - minPrice) / priceRange * height)

            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        // Complete fill path
        fillPath.lineTo(width, height)
        fillPath.close()

        // Draw fill
        drawPath(path = fillPath, brush = fillColor)

        // Draw line
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 3f, cap = StrokeCap.Round)
        )

        // Draw dot at last point
        val lastX = (closePrices.size - 1) * stepX
        val lastY = height - ((closePrices.last() - minPrice) / priceRange * height)
        drawCircle(color = lineColor, radius = 6f, center = Offset(lastX, lastY))
        drawCircle(color = Color.White, radius = 3f, center = Offset(lastX, lastY))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatMarketCap(value: Long): String {
    return when {
        value >= 1_000_000_000_000 -> String.format("%.1fT", value / 1_000_000_000_000.0)
        value >= 1_000_000_000 -> String.format("%.1fB", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000.0)
        else -> String.format("%,d", value)
    }
}
