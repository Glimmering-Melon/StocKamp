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
import com.stockamp.data.model.ChartUiState
import com.stockamp.data.model.StockPrice
import com.stockamp.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(
    symbol: String,
    onNavigateBack: () -> Unit,
    viewModel: StockDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chartState by viewModel.chartState.collectAsStateWithLifecycle()

    LaunchedEffect(symbol) {
        viewModel.loadChartData(symbol)
    }

    val isChartLoading = chartState is ChartUiState.Loading
    val isChartError = chartState is ChartUiState.Error

    val changePercent = uiState.changePercent
    val isPositive = changePercent != null && changePercent >= 0

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
                            contentDescription = if (uiState.isInWatchlist) "Đã theo dõi" else "Thêm vào danh sách theo dõi",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Company name
            Text(
                text = uiState.symbolInfo?.name ?: symbol,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Price
            Row(verticalAlignment = Alignment.Bottom) {
                val priceText = uiState.latestClose?.close?.let {
                    String.format("%,.0f", it)
                } ?: "--"
                Text(
                    text = priceText,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "VND",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Change percent
            if (changePercent != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isPositive) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (isPositive) AccentGreen else AccentRed
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${if (isPositive) "+" else ""}${String.format("%.2f", changePercent)}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPositive) AccentGreen else AccentRed
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Watchlist button
            OutlinedButton(
                onClick = { viewModel.toggleWatchlist() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    if (uiState.isInWatchlist) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (uiState.isInWatchlist) "Đã theo dõi" else "Thêm vào danh sách theo dõi"
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Chart card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                when {
                    isChartLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    isChartError -> {
                        val errorMsg = (chartState as ChartUiState.Error).message
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = errorMsg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { viewModel.retryLoad() }) {
                                Text("Thử lại")
                            }
                        }
                    }
                    else -> {
                        ChartComponent(
                            chartState = chartState,
                            onTimeframeSelected = { viewModel.updateTimeframe(it) },
                            onChartTypeToggled = { viewModel.toggleChartType(it) },
                            onIndicatorToggled = { viewModel.toggleIndicator(it) },
                            onRetry = { viewModel.retryLoad() },
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Symbol info details
            uiState.symbolInfo?.let { info ->
                Text(
                    "Thông tin cổ phiếu",
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
                        StatRow("Sàn giao dịch", info.exchange)
                        if (info.sector != null) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                            StatRow("Ngành", info.sector)
                        }
                        uiState.latestClose?.let { close ->
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            )
                            StatRow("Ngày cập nhật", close.date)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
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

        fillPath.lineTo(width, height)
        fillPath.close()

        drawPath(path = fillPath, brush = fillColor)
        drawPath(path = linePath, color = lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))

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
