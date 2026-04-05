package com.stockamp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stockamp.data.model.Stock
import com.stockamp.data.model.WatchlistItem
import com.stockamp.data.repository.StockRepository
import com.stockamp.data.repository.WatchlistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MarketIndex(
    val name: String,
    val value: Double,
    val change: Double,
    val changePercent: Double,
    val sparkline: List<Double> = emptyList()
)

data class HomeUiState(
    val indices: List<MarketIndex> = emptyList(),
    val watchlistStocks: List<Stock> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val stockRepository: StockRepository,
    private val watchlistRepository: WatchlistRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            stockRepository.loadSampleData()
            loadData()
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            // Sample market indices
            val indices = listOf(
                MarketIndex(
                    name = "S&P 500",
                    value = 5248.49,
                    change = 44.91,
                    changePercent = 0.86,
                    sparkline = listOf(5180.0, 5195.0, 5210.0, 5205.0, 5225.0, 5240.0, 5235.0, 5248.49)
                ),
                MarketIndex(
                    name = "NASDAQ",
                    value = 16399.52,
                    change = -23.87,
                    changePercent = -0.15,
                    sparkline = listOf(16450.0, 16440.0, 16420.0, 16430.0, 16410.0, 16405.0, 16400.0, 16399.52)
                ),
                MarketIndex(
                    name = "DOW 30",
                    value = 39807.37,
                    change = 47.29,
                    changePercent = 0.12,
                    sparkline = listOf(39700.0, 39720.0, 39750.0, 39780.0, 39760.0, 39790.0, 39800.0, 39807.37)
                )
            )
            _uiState.update { it.copy(indices = indices) }

            // Load watchlist stocks
            watchlistRepository.getAllWatchlistItems().collect { items ->
                val symbols = items.map { it.symbol }
                val stocks = if (symbols.isEmpty()) {
                    // Show top stocks if watchlist empty
                    stockRepository.getAllStocks().first().take(5)
                } else {
                    symbols.mapNotNull { stockRepository.getStockBySymbol(it) }
                }
                _uiState.update { it.copy(watchlistStocks = stocks, isLoading = false) }
            }
        }
    }
}
