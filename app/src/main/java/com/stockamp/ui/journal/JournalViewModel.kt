package com.stockamp.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stockamp.data.local.JournalDao
import com.stockamp.data.market.MarketRepository
import com.stockamp.data.market.ProfitMarginCalculator
import com.stockamp.data.model.JournalEntry
import com.stockamp.data.sync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class EntryPnL(
    val entryPrice: Double?,      // close from OHLCV on transactionDate
    val latestClose: Double?,
    val latestDate: String?,
    val profitPercent: Double?,   // (latestClose - entryPrice) / entryPrice * 100
    val profitVnd: Double?        // (latestClose - entryPrice) * quantity
)

data class JournalUiState(
    val entries: List<JournalEntry> = emptyList(),
    val totalTrades: Int = 0,
    val totalUnrealizedPnLVnd: Double = 0.0,
    val pnlMap: Map<Long, EntryPnL> = emptyMap(),   // keyed by entry.id
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false
)

@HiltViewModel
class JournalViewModel @Inject constructor(
    private val journalDao: JournalDao,
    private val syncEngine: SyncEngine,
    private val marketRepository: MarketRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(JournalUiState())
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()

    init {
        // Sync from Supabase when screen opens
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            syncEngine.syncJournal()
            _uiState.update { it.copy(isSyncing = false) }
        }

        // Observe Room (local DB) — auto-updates after sync completes
        viewModelScope.launch {
            journalDao.getAllEntries().collect { entries ->
                _uiState.update {
                    it.copy(
                        entries = entries,
                        totalTrades = entries.size,
                        isLoading = false
                    )
                }
                computePnL(entries)
            }
        }
    }

    private suspend fun computePnL(entries: List<JournalEntry>) = coroutineScope {
        // Deduplicate: fetch OHLCV and latestClose once per unique symbol
        val uniqueSymbols = entries.map { it.symbol }.distinct()

        val ohlcvBySymbol = uniqueSymbols.associateWith { symbol ->
            async { marketRepository.getOhlcv(symbol, "1D").getOrNull() }
        }.mapValues { (_, deferred) -> deferred.await() }

        val latestCloseBySymbol = uniqueSymbols.associateWith { symbol ->
            async { marketRepository.getLatestClose(symbol).getOrNull() }
        }.mapValues { (_, deferred) -> deferred.await() }

        val pnlMap = mutableMapOf<Long, EntryPnL>()
        var totalUnrealizedPnLVnd = 0.0

        for (entry in entries) {
            val ohlcv = ohlcvBySymbol[entry.symbol]
            val latestCloseResult = latestCloseBySymbol[entry.symbol]

            // Find PriceDataPoint matching entry.transactionDate
            val entryPrice: Double? = if (!entry.transactionDate.isNullOrBlank() && ohlcv != null) {
                val targetDate = runCatching { LocalDate.parse(entry.transactionDate) }.getOrNull()
                ohlcv.firstOrNull { point ->
                    LocalDate.ofEpochDay(point.timestamp / 86400000L) == targetDate
                }?.close
            } else null

            val latestClose = latestCloseResult?.close
            val latestDate = latestCloseResult?.date

            val profitPercent = if (entryPrice != null && entryPrice != 0.0 && latestClose != null) {
                ProfitMarginCalculator.calculate(entryPrice, latestClose)
            } else null

            val profitVnd = if (entryPrice != null && latestClose != null) {
                (latestClose - entryPrice) * entry.quantity
            } else null

            pnlMap[entry.id] = EntryPnL(
                entryPrice = entryPrice,
                latestClose = latestClose,
                latestDate = latestDate,
                profitPercent = profitPercent,
                profitVnd = profitVnd
            )

            // Sum profitVnd for BUY entries with available data
            if (entry.action == "BUY" && profitVnd != null) {
                totalUnrealizedPnLVnd += profitVnd
            }
        }

        _uiState.update {
            it.copy(
                pnlMap = pnlMap,
                totalUnrealizedPnLVnd = totalUnrealizedPnLVnd
            )
        }
    }

    fun deleteEntry(entry: JournalEntry) {
        viewModelScope.launch {
            journalDao.deleteEntry(entry)
            syncEngine.syncJournal()
        }
    }
}
