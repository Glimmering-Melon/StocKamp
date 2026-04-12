package com.stockamp.ui.market

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stockamp.data.market.MarketRepository
import com.stockamp.data.model.StockSymbolInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MarketSymbolRow(
    val info: StockSymbolInfo,
    val latestClose: Double?,
    val changePercent: Double?
)

data class MarketUiState(
    val allSymbols: List<MarketSymbolRow> = emptyList(),
    val filteredSymbols: List<MarketSymbolRow> = emptyList(),
    val selectedExchange: String = "Tất cả",   // "Tất cả" | "HOSE" | "HNX" | "UPCOM"
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

@HiltViewModel
class MarketViewModel @Inject constructor(
    private val marketRepository: MarketRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarketUiState())
    val uiState: StateFlow<MarketUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    init {
        loadSymbols()

        viewModelScope.launch {
            _searchQuery
                .debounce(300)
                .collect { query ->
                    applyFilters(query = query)
                }
        }
    }

    private fun exchangePriority(exchange: String): Int = when (exchange.uppercase()) {
        "HOSE" -> 0
        "HNX"  -> 1
        "UPCOM" -> 2
        else   -> 3
    }

    private fun loadSymbols() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val result = marketRepository.getAvailableSymbols()
            Log.d("MarketVM", "getAvailableSymbols result: isSuccess=${result.isSuccess}, size=${result.getOrNull()?.size}, error=${result.exceptionOrNull()?.message}")
            result.onFailure { e ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load symbols")
                }
                return@launch
            }

            val sorted = result.getOrDefault(emptyList())
                .sortedBy { exchangePriority(it.exchange) }

            val first10 = sorted.take(10)

            // Fetch latestClose for first 10 in parallel
            val closeResults = first10.map { info ->
                async { marketRepository.getLatestClose(info.symbol) }
            }.awaitAll()

            val allRows = sorted.mapIndexed { index, info ->
                val close = if (index < 10) closeResults[index].getOrNull()?.close else null
                MarketSymbolRow(info = info, latestClose = close, changePercent = null)
            }

            _uiState.update {
                it.copy(
                    allSymbols = allRows,
                    filteredSymbols = allRows.take(10),
                    isLoading = false
                )
            }
        }
    }

    private fun applyFilters(
        query: String = _uiState.value.searchQuery,
        exchange: String = _uiState.value.selectedExchange
    ) {
        val all = _uiState.value.allSymbols
        val filtered = all
            .let { list ->
                if (exchange == "Tất cả") list.take(50)
                else list.filter { it.info.exchange.equals(exchange, ignoreCase = true) }
            }
            .let { list ->
                if (query.isBlank()) list
                else list.filter { row ->
                    row.info.symbol.contains(query, ignoreCase = true) ||
                        row.info.name.contains(query, ignoreCase = true)
                }
            }
        _uiState.update { it.copy(filteredSymbols = filtered) }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        _searchQuery.value = query
    }

    fun onExchangeSelected(exchange: String) {
        _uiState.update { it.copy(selectedExchange = exchange) }
        applyFilters(exchange = exchange)
    }

    fun retry() {
        loadSymbols()
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
