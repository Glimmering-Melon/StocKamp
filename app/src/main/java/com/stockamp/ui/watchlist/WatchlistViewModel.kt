package com.stockamp.ui.watchlist

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

data class WatchlistUiState(
    val items: List<WatchlistItem> = emptyList(),
    val stockMap: Map<String, Stock> = emptyMap(),
    val isLoading: Boolean = true
)

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val watchlistRepository: WatchlistRepository,
    private val stockRepository: StockRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WatchlistUiState())
    val uiState: StateFlow<WatchlistUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            watchlistRepository.getAllWatchlistItems().collect { items ->
                val stockMap = mutableMapOf<String, Stock>()
                items.forEach { item ->
                    stockRepository.getStockBySymbol(item.symbol)?.let { stock ->
                        stockMap[item.symbol] = stock
                    }
                }
                _uiState.update {
                    it.copy(items = items, stockMap = stockMap, isLoading = false)
                }
            }
        }
    }

    fun removeFromWatchlist(symbol: String) {
        viewModelScope.launch {
            watchlistRepository.removeFromWatchlist(symbol)
        }
    }
}
