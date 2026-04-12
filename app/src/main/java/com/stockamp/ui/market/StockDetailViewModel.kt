package com.stockamp.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stockamp.data.chart.TechnicalIndicatorCalculator
import com.stockamp.data.local.WatchlistDao
import com.stockamp.data.market.MarketRepository
import com.stockamp.data.model.ChartType
import com.stockamp.data.model.ChartUiState
import com.stockamp.data.model.LatestCloseResult
import com.stockamp.data.model.StockSymbolInfo
import com.stockamp.data.model.TechnicalIndicator
import com.stockamp.data.model.Timeframe
import com.stockamp.data.model.WatchlistItem
import com.stockamp.data.supabase.SupabaseClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class StockDetailUiState(
    val chartState: ChartUiState = ChartUiState.Loading,
    val symbolInfo: StockSymbolInfo? = null,
    val latestClose: LatestCloseResult? = null,
    val changePercent: Double? = null,
    val isInWatchlist: Boolean = false
)

@HiltViewModel
class StockDetailViewModel @Inject constructor(
    private val marketRepository: MarketRepository,
    private val watchlistDao: WatchlistDao,
    private val indicatorCalculator: TechnicalIndicatorCalculator,
    private val supabaseClient: SupabaseClient
) : ViewModel() {

    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    private val _uiState = MutableStateFlow(StockDetailUiState())
    val uiState: StateFlow<StockDetailUiState> = _uiState.asStateFlow()

    // Backward compat: expose chartState directly for ChartComponent
    val chartState: StateFlow<ChartUiState> get() = _chartStateInternal.asStateFlow()
    private val _chartStateInternal = MutableStateFlow<ChartUiState>(ChartUiState.Loading)

    private var currentSymbol: String = ""
    private var currentTimeframe: Timeframe = Timeframe.ONE_DAY
    private var currentChartType: ChartType = ChartType.CANDLESTICK
    private var visibleIndicators: MutableSet<TechnicalIndicator> = mutableSetOf()

    fun loadChartData(symbol: String, timeframe: Timeframe = currentTimeframe) {
        currentSymbol = symbol
        currentTimeframe = timeframe
        _chartStateInternal.value = ChartUiState.Loading
        _uiState.update { it.copy(chartState = ChartUiState.Loading) }

        viewModelScope.launch {
            // Load OHLCV data
            marketRepository.getOhlcv(symbol, timeframe.apiValue)
                .onSuccess { data ->
                    val indicators = mapOf(
                        TechnicalIndicator.MA20 to indicatorCalculator.calculateMA(data, 20),
                        TechnicalIndicator.MA50 to indicatorCalculator.calculateMA(data, 50)
                    )
                    val lastUpdated = data.maxByOrNull { it.timestamp }?.let { point ->
                        Instant.ofEpochMilli(point.timestamp)
                            .atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                            .format(dateFormatter)
                    }
                    val newChartState = ChartUiState.Success(
                        priceData = data,
                        chartType = currentChartType,
                        timeframe = currentTimeframe,
                        indicators = indicators,
                        visibleIndicators = visibleIndicators.toSet(),
                        lastUpdatedDate = lastUpdated
                    )
                    _chartStateInternal.value = newChartState

                    // Compute changePercent from first and last close
                    val changePercent = if (data.size >= 2) {
                        val first = data.first()
                        val last = data.last()
                        if (first.close != 0.0) (last.close - first.close) / first.close * 100 else null
                    } else null

                    _uiState.update { it.copy(chartState = newChartState, changePercent = changePercent) }
                }
                .onFailure { e ->
                    val errorState = ChartUiState.Error(e.message ?: "Unable to load chart data")
                    _chartStateInternal.value = errorState
                    _uiState.update { it.copy(chartState = errorState) }
                }

            // Load symbol info
            marketRepository.getAvailableSymbols()
                .onSuccess { symbols ->
                    val info = symbols.find { it.symbol == symbol }
                    _uiState.update { it.copy(symbolInfo = info) }
                }

            // Load latest close
            marketRepository.getLatestClose(symbol)
                .onSuccess { result ->
                    _uiState.update { it.copy(latestClose = result) }
                }

            // Check watchlist status
            val inWatchlist = watchlistDao.isInWatchlist(symbol) > 0
            _uiState.update { it.copy(isInWatchlist = inWatchlist) }
        }
    }

    fun toggleWatchlist() {
        val symbol = currentSymbol
        if (symbol.isBlank()) return

        viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState.isInWatchlist) {
                // Get item id before deleting (for Supabase sync)
                val item = watchlistDao.getWatchlistItem(symbol)
                watchlistDao.deleteBySymbol(symbol)
                _uiState.update { it.copy(isInWatchlist = false) }
                // Best-effort sync
                if (item != null) {
                    runCatching { supabaseClient.deleteWatchlistItem(item.id) }
                }
            } else {
                val symbolName = currentState.symbolInfo?.name ?: symbol
                val newItem = WatchlistItem(symbol = symbol, name = symbolName)
                watchlistDao.insertWatchlistItem(newItem)
                _uiState.update { it.copy(isInWatchlist = true) }
                // Best-effort sync
                runCatching { supabaseClient.upsertWatchlistItem(newItem) }
            }
        }
    }

    fun updateTimeframe(timeframe: Timeframe) {
        currentTimeframe = timeframe
        loadChartData(currentSymbol, timeframe)
    }

    fun toggleChartType(chartType: ChartType) {
        currentChartType = chartType
        val state = _chartStateInternal.value
        if (state is ChartUiState.Success) {
            val updated = state.copy(chartType = chartType)
            _chartStateInternal.value = updated
            _uiState.update { it.copy(chartState = updated) }
        }
    }

    fun toggleIndicator(indicator: TechnicalIndicator) {
        if (indicator in visibleIndicators) visibleIndicators.remove(indicator)
        else visibleIndicators.add(indicator)
        val state = _chartStateInternal.value
        if (state is ChartUiState.Success) {
            val updated = state.copy(visibleIndicators = visibleIndicators.toSet())
            _chartStateInternal.value = updated
            _uiState.update { it.copy(chartState = updated) }
        }
    }

    fun retryLoad() = loadChartData(currentSymbol, currentTimeframe)
}
