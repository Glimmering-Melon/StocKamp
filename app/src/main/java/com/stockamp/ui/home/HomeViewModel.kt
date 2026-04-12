package com.stockamp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stockamp.data.local.WatchlistDao
import com.stockamp.data.market.MarketRepository
import com.stockamp.data.model.WatchlistItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MarketIndex(
    val name: String,
    val value: Double,
    val change: Double,
    val changePercent: Double,
    val sparkline: List<Double> = emptyList()
)

data class WatchlistMarketRow(
    val item: WatchlistItem,
    val sparklineCloses: List<Double>,   // close values from last 7 days of 1D OHLCV
    val latestClose: Double?,            // null if unavailable
    val changePercent: Double?           // null if unavailable
)

data class HomeUiState(
    val indices: List<MarketIndex> = emptyList(),
    val watchlistRows: List<WatchlistMarketRow> = emptyList(),
    val isWatchlistLoading: Boolean = true,
    val watchlistError: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val watchlistDao: WatchlistDao,
    private val marketRepository: MarketRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadIndices()
        loadWatchlist()
    }

    private fun loadIndices() {
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
    }

    private fun loadWatchlist() {
        viewModelScope.launch {
            _uiState.update { it.copy(isWatchlistLoading = true, watchlistError = null) }
            try {
                // Collect first emission and take top 2 items (already ordered by addedAt DESC)
                val items = watchlistDao.getAllWatchlistItems().first().take(2)

                // Launch parallel coroutines for each item
                val rows = items.map { item ->
                    async { buildRow(item) }
                }.map { it.await() }

                _uiState.update { it.copy(watchlistRows = rows, isWatchlistLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isWatchlistLoading = false,
                        watchlistError = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    private suspend fun buildRow(item: WatchlistItem): WatchlistMarketRow {
        val symbol = item.symbol

        // Launch OHLCV and latestClose in parallel
        val ohlcvDeferred = viewModelScope.async {
            marketRepository.getOhlcv(symbol, "1D")
        }
        val latestCloseDeferred = viewModelScope.async {
            marketRepository.getLatestClose(symbol)
        }

        val ohlcvResult = ohlcvDeferred.await()
        val latestCloseResult = latestCloseDeferred.await()

        // Extract sparkline closes; empty list on failure
        val sparklineCloses = ohlcvResult.getOrNull()?.map { it.close } ?: emptyList()

        // Extract latestClose value; null on failure or null result
        val latestClose = latestCloseResult.getOrNull()?.close

        // Compute changePercent using second-to-last close as previousClose
        val changePercent = if (latestClose != null && sparklineCloses.size >= 2) {
            val previousClose = sparklineCloses[sparklineCloses.size - 2]
            if (previousClose != 0.0) {
                (latestClose - previousClose) / previousClose * 100
            } else null
        } else null

        return WatchlistMarketRow(
            item = item,
            sparklineCloses = sparklineCloses,
            latestClose = latestClose,
            changePercent = changePercent
        )
    }
}
