package com.stockamp.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stockamp.data.chart.TechnicalIndicatorCalculator
import com.stockamp.data.market.MarketRepository
import com.stockamp.data.model.ChartType
import com.stockamp.data.model.ChartUiState
import com.stockamp.data.model.TechnicalIndicator
import com.stockamp.data.model.Timeframe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class ChartViewModel @Inject constructor(
    private val marketRepository: MarketRepository,
    private val indicatorCalculator: TechnicalIndicatorCalculator
) : ViewModel() {

    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    private val _chartState = MutableStateFlow<ChartUiState>(ChartUiState.Loading)
    val chartState: StateFlow<ChartUiState> = _chartState.asStateFlow()

    private var currentSymbol: String = ""
    private var currentTimeframe: Timeframe = Timeframe.ONE_DAY
    private var currentChartType: ChartType = ChartType.CANDLESTICK
    private var visibleIndicators: MutableSet<TechnicalIndicator> = mutableSetOf()

    fun loadChartData(symbol: String, timeframe: Timeframe = currentTimeframe) {
        currentSymbol = symbol
        currentTimeframe = timeframe
        _chartState.value = ChartUiState.Loading
        viewModelScope.launch {
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
                    _chartState.value = ChartUiState.Success(
                        priceData = data,
                        chartType = currentChartType,
                        timeframe = currentTimeframe,
                        indicators = indicators,
                        visibleIndicators = visibleIndicators.toSet(),
                        lastUpdatedDate = lastUpdated
                    )
                }
                .onFailure { e ->
                    _chartState.value = ChartUiState.Error(e.message ?: "Unable to load chart data")
                }
        }
    }

    fun updateTimeframe(timeframe: Timeframe) {
        currentTimeframe = timeframe
        loadChartData(currentSymbol, timeframe)
    }

    fun toggleChartType(chartType: ChartType) {
        currentChartType = chartType
        val state = _chartState.value
        if (state is ChartUiState.Success) {
            _chartState.value = state.copy(chartType = chartType)
        }
    }

    fun toggleIndicator(indicator: TechnicalIndicator) {
        if (indicator in visibleIndicators) visibleIndicators.remove(indicator)
        else visibleIndicators.add(indicator)
        val state = _chartState.value
        if (state is ChartUiState.Success) {
            _chartState.value = state.copy(visibleIndicators = visibleIndicators.toSet())
        }
    }

    fun retryLoad() = loadChartData(currentSymbol, currentTimeframe)
}
