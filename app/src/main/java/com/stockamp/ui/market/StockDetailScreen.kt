package com.stockamp.ui.market

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stockamp.data.model.ChartUiState
import com.stockamp.data.model.PriceDataPoint
import java.util.Locale
import com.stockamp.data.model.StockPrice
import com.stockamp.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        viewModel.generate30DaysForecast(symbol)
    }

    val isChartLoading = chartState is ChartUiState.Loading
    val isChartError = chartState is ChartUiState.Error
    val changePercent = uiState.changePercent
    val isPositive = changePercent != null && changePercent >= 0
    val forecastDay30 = uiState.forecastedPrices.lastOrNull()
    val currentPrice = uiState.latestClose?.close
        ?: (chartState as? ChartUiState.Success)?.priceData?.lastOrNull()?.close

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(symbol, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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

            Text(
                text = uiState.symbolInfo?.name ?: symbol,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                val priceText = uiState.latestClose?.close?.let {
                    String.format(Locale.US, "%,.0f", it)
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

            if (changePercent != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isPositive) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (isPositive) AccentGreen else AccentRed
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${if (isPositive) "+" else ""}${String.format(Locale.US, "%.2f", changePercent)}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPositive) AccentGreen else AccentRed
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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

            Spacer(modifier = Modifier.height(12.dp))

            ForecastSummaryCard(
                isForecasting = uiState.isForecasting,
                forecastDay30 = forecastDay30,
                currentPrice = currentPrice,
                forecastError = uiState.forecastError
            )

            Spacer(modifier = Modifier.height(24.dp))

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
                            androidx.compose.material3.Button(onClick = { viewModel.retryLoad() }) {
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

            // Price history table
            val priceHistory = (chartState as? ChartUiState.Success)?.priceData
                ?.sortedByDescending { it.timestamp } ?: emptyList()
            if (priceHistory.isNotEmpty()) {
                PriceHistoryTable(priceData = priceHistory)
                Spacer(modifier = Modifier.height(24.dp))
            }

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
private fun ForecastSummaryCard(
    isForecasting: Boolean,
    forecastDay30: Double?,
    currentPrice: Double?,
    forecastError: String?
) {
    val isForecastUp = currentPrice?.let { price ->
        forecastDay30?.let { it >= price }
    } ?: true
    val trendColor = if (isForecastUp) AccentGreen else AccentRed

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                isForecasting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "AI đang dự báo",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Đang tính giá 30 ngày tới...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                forecastDay30 != null -> {
                    Icon(
                        imageVector = Icons.Default.AutoGraph,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = trendColor
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dự báo AI sau 30 ngày",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${String.format(Locale.US, "%,.0f", forecastDay30)} VND",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = trendColor
                        )
                    }
                    currentPrice?.let { price ->
                        val percent = if (price != 0.0) {
                            (forecastDay30 - price) / price * 100.0
                        } else {
                            0.0
                        }
                        Text(
                            text = "${if (percent >= 0) "+" else ""}${String.format(Locale.US, "%.2f", percent)}%",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = trendColor
                        )
                    }
                }

                forecastError != null -> {
                    Icon(
                        imageVector = Icons.Default.AutoGraph,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Chưa có dự báo AI",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = forecastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    Icon(
                        imageVector = Icons.Default.AutoGraph,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Đang chờ dữ liệu dự báo AI",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceHistoryTable(priceData: List<PriceDataPoint>) {
    val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))
    val displayedRows = priceData.take(30)

    Text(
        "Lịch sử giá",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(12.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Ngày",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(2f)
                )
                Text(
                    "Mở",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.5f),
                    textAlign = TextAlign.End
                )
                Text(
                    "Cao",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.5f),
                    textAlign = TextAlign.End
                )
                Text(
                    "Thấp",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.5f),
                    textAlign = TextAlign.End
                )
                Text(
                    "Đóng",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.5f),
                    textAlign = TextAlign.End
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            displayedRows.forEachIndexed { index, point ->
                val date = formatter.format(Instant.ofEpochMilli(point.timestamp))
                val prevClose = priceData.getOrNull(index + 1)?.close
                val isUp = prevClose == null || point.close >= prevClose
                val closeColor = if (isUp) AccentGreen else AccentRed

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        date,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(2f)
                    )
                    Text(
                        String.format(Locale.US, "%,.0f", point.open),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1.5f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        String.format(Locale.US, "%,.0f", point.high),
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentGreen,
                        modifier = Modifier.weight(1.5f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        String.format(Locale.US, "%,.0f", point.low),
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentRed,
                        modifier = Modifier.weight(1.5f),
                        textAlign = TextAlign.End
                    )
                    Text(
                        String.format(Locale.US, "%,.0f", point.close),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = closeColor,
                        modifier = Modifier.weight(1.5f),
                        textAlign = TextAlign.End
                    )
                }

                if (index < displayedRows.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
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

@Composable
private fun PriceHistoryTable(priceData: List<PriceDataPoint>) {
    val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        .withZone(ZoneId.of("Asia/Ho_Chi_Minh"))

    Text(
        "Lịch sử giá",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(12.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Ngày", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(2f))
                Text("Mở", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.5f), textAlign = TextAlign.End)
                Text("Cao", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.5f), textAlign = TextAlign.End)
                Text("Thấp", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.5f), textAlign = TextAlign.End)
                Text("Đóng", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.5f), textAlign = TextAlign.End)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            priceData.take(30).forEachIndexed { index, point ->
                val date = formatter.format(Instant.ofEpochMilli(point.timestamp))
                val prevClose = priceData.getOrNull(index + 1)?.close
                val isUp = prevClose == null || point.close >= prevClose
                val closeColor = if (isUp) AccentGreen else AccentRed

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(date, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(2f))
                    Text(String.format("%,.0f", point.open),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1.5f), textAlign = TextAlign.End)
                    Text(String.format("%,.0f", point.high),
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentGreen,
                        modifier = Modifier.weight(1.5f), textAlign = TextAlign.End)
                    Text(String.format("%,.0f", point.low),
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentRed,
                        modifier = Modifier.weight(1.5f), textAlign = TextAlign.End)
                    Text(String.format("%,.0f", point.close),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = closeColor,
                        modifier = Modifier.weight(1.5f), textAlign = TextAlign.End)
                }
                if (index < priceData.take(30).size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}
