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
                    name = "VN-Index",
                    value = 1287.45,
                    change = 12.34,
                    changePercent = 0.97,
                    sparkline = listOf(1265.0, 1270.0, 1268.0, 1275.0, 1272.0, 1280.0, 1285.0, 1287.45)
                ),
                MarketIndex(
                    name = "HNX-Index",
                    value = 234.56,
                    change = -1.23,
                    changePercent = -0.52,
                    sparkline = listOf(238.0, 237.0, 236.5, 235.8, 236.2, 235.0, 234.8, 234.56)
                ),
                MarketIndex(
                    name = "UPCOM",
                    value = 98.12,
                    change = 0.45,
                    changePercent = 0.46,
                    sparkline = listOf(97.0, 97.3, 97.5, 97.8, 97.6, 97.9, 98.0, 98.12)
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
