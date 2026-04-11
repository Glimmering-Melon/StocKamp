package com.stockamp.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stockamp.data.model.MarketSector
import com.stockamp.data.model.Stock
import com.stockamp.data.model.StockPrice
import com.stockamp.data.repository.StockRepository
import com.stockamp.data.repository.WatchlistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MarketUiState(
    val stocks: List<Stock> = emptyList(),
    val sectors: List<MarketSector> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val selectedSector: String? = null
)

data class StockDetailUiState(
    val stock: Stock? = null,
    val priceHistory: List<StockPrice> = emptyList(),
    val isInWatchlist: Boolean = false,
    val isLoading: Boolean = true
)

@HiltViewModel
class MarketViewModel @Inject constructor(
    private val stockRepository: StockRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarketUiState())
    val uiState: StateFlow<MarketUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    init {
        viewModelScope.launch {
            stockRepository.loadSampleData()
            _uiState.update { it.copy(sectors = stockRepository.getSectors()) }
        }

        viewModelScope.launch {
            _searchQuery
                .debounce(300)
                .flatMapLatest { query ->
                    if (query.isBlank()) stockRepository.getAllStocks()
                    else stockRepository.searchStocks(query)
                }
                .collect { stocks ->
                    _uiState.update { it.copy(stocks = stocks, isLoading = false) }
                }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun filterBySector(sector: String?) {
        _uiState.update { it.copy(selectedSector = sector) }
        viewModelScope.launch {
            val flow = if (sector == null) stockRepository.getAllStocks()
            else stockRepository.getStocksBySector(sector)
            flow.collect { stocks ->
                _uiState.update { it.copy(stocks = stocks) }
            }
        }
    }
}

@HiltViewModel
class StockDetailViewModel @Inject constructor(
    private val stockRepository: StockRepository,
    private val watchlistRepository: WatchlistRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StockDetailUiState())
    val uiState: StateFlow<StockDetailUiState> = _uiState.asStateFlow()

    fun loadStock(symbol: String) {
        viewModelScope.launch {
            val stock = stockRepository.getStockBySymbol(symbol)
            val prices = stockRepository.getPriceHistory(symbol)
            val inWatchlist = watchlistRepository.isInWatchlist(symbol)
            _uiState.update {
                it.copy(
                    stock = stock,
                    priceHistory = prices,
                    isInWatchlist = inWatchlist,
                    isLoading = false
                )
            }
        }
    }

    fun toggleWatchlist() {
        viewModelScope.launch {
            val current = _uiState.value
            val stock = current.stock ?: return@launch
            if (current.isInWatchlist) {
                watchlistRepository.removeFromWatchlist(stock.symbol)
            } else {
                watchlistRepository.addToWatchlist(stock.symbol, stock.name)
            }
            _uiState.update { it.copy(isInWatchlist = !current.isInWatchlist) }
        }
    }
}
