package com.stockamp.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stockamp.data.auth.AuthManager
import com.stockamp.data.local.JournalDao
import com.stockamp.data.local.WatchlistDao
import com.stockamp.data.market.MarketRepository
import com.stockamp.data.model.JournalEntry
import com.stockamp.data.model.WatchlistItem
import com.stockamp.data.sync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

sealed class ReferencePriceState {
    object Idle : ReferencePriceState()
    object Loading : ReferencePriceState()
    data class Available(val price: Double, val date: String) : ReferencePriceState()
    object NotAvailable : ReferencePriceState()
}

data class AddEditJournalUiState(
    val watchlistSymbols: List<WatchlistItem> = emptyList(),
    val selectedSymbol: String = "",
    val selectedSymbolName: String = "",
    val action: String = "BUY",
    val quantity: String = "",
    val transactionDate: String = LocalDate.now().toString(),
    val notes: String = "",
    val referencePriceState: ReferencePriceState = ReferencePriceState.Idle,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveSuccess: Boolean = false
)

@HiltViewModel
class AddEditJournalViewModel @Inject constructor(
    private val watchlistDao: WatchlistDao,
    private val marketRepository: MarketRepository,
    private val journalDao: JournalDao,
    private val syncEngine: SyncEngine,
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddEditJournalUiState())
    val uiState: StateFlow<AddEditJournalUiState> = _uiState.asStateFlow()

    // Holds the loaded entry when editing
    private var loadedEntry: JournalEntry? = null

    init {
        viewModelScope.launch {
            val items = watchlistDao.getAllWatchlistItems().firstOrNull() ?: emptyList()
            _uiState.update { it.copy(watchlistSymbols = items) }
        }
    }

    fun loadEntry(id: Long) {
        viewModelScope.launch {
            val entry = journalDao.getEntryById(id) ?: return@launch
            loadedEntry = entry
            _uiState.update {
                it.copy(
                    selectedSymbol = entry.symbol,
                    selectedSymbolName = it.watchlistSymbols
                        .firstOrNull { w -> w.symbol == entry.symbol }?.name ?: "",
                    action = entry.action,
                    quantity = entry.quantity.toString(),
                    transactionDate = entry.transactionDate,
                    notes = entry.notes
                )
            }
            fetchReferencePrice(entry.symbol, entry.transactionDate)
        }
    }

    fun onSymbolSelected(symbol: String) {
        val name = _uiState.value.watchlistSymbols
            .firstOrNull { it.symbol == symbol }?.name ?: ""
        _uiState.update { it.copy(selectedSymbol = symbol, selectedSymbolName = name) }
        val date = _uiState.value.transactionDate
        if (symbol.isNotBlank() && date.isNotBlank()) {
            fetchReferencePrice(symbol, date)
        }
    }

    fun onActionChanged(action: String) {
        _uiState.update { it.copy(action = action) }
    }

    fun onQuantityChanged(quantity: String) {
        _uiState.update { it.copy(quantity = quantity) }
    }

    fun onDateChanged(date: String) {
        _uiState.update { it.copy(transactionDate = date) }
        val symbol = _uiState.value.selectedSymbol
        if (symbol.isNotBlank() && date.isNotBlank()) {
            fetchReferencePrice(symbol, date)
        }
    }

    fun onNotesChanged(notes: String) {
        _uiState.update { it.copy(notes = notes) }
    }

    private fun fetchReferencePrice(symbol: String, transactionDate: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(referencePriceState = ReferencePriceState.Loading) }
            val result = marketRepository.getOhlcv(symbol, "1D")
            result.fold(
                onSuccess = { dataPoints ->
                    val targetDate = runCatching { LocalDate.parse(transactionDate) }.getOrNull()
                    val match = if (targetDate != null) {
                        dataPoints.firstOrNull { point ->
                            LocalDate.ofEpochDay(point.timestamp / 86400000L) == targetDate
                        }
                    } else null

                    _uiState.update {
                        it.copy(
                            referencePriceState = if (match != null) {
                                ReferencePriceState.Available(match.close, transactionDate)
                            } else {
                                ReferencePriceState.NotAvailable
                            }
                        )
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(referencePriceState = ReferencePriceState.NotAvailable) }
                }
            )
        }
    }

    fun save(onSuccess: () -> Unit) {
        val state = _uiState.value
        val symbol = state.selectedSymbol
        val quantityInt = state.quantity.toIntOrNull()
        val transactionDate = state.transactionDate

        if (symbol.isBlank()) {
            _uiState.update { it.copy(saveError = "Vui lòng chọn mã cổ phiếu") }
            return
        }
        if (quantityInt == null || quantityInt <= 0) {
            _uiState.update { it.copy(saveError = "Số lượng phải là số nguyên dương") }
            return
        }
        if (transactionDate.isBlank()) {
            _uiState.update { it.copy(saveError = "Vui lòng chọn ngày giao dịch") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveError = null) }
            val userId = authManager.getCurrentUser().firstOrNull()?.id ?: ""
            val existing = loadedEntry
            val entry = JournalEntry(
                id = existing?.id ?: 0L,
                userId = existing?.userId?.ifBlank { userId } ?: userId,
                symbol = symbol,
                action = state.action,
                quantity = quantityInt,
                transactionDate = transactionDate,
                notes = state.notes,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                modifiedAt = System.currentTimeMillis(),
                syncedAt = existing?.syncedAt,
                isDeleted = false
            )
            if (existing != null) {
                journalDao.updateEntry(entry)
            } else {
                journalDao.insertEntry(entry)
            }
            syncEngine.syncJournal()
            _uiState.update { it.copy(isSaving = false, saveSuccess = true) }
            onSuccess()
        }
    }

    fun deleteEntry(onSuccess: () -> Unit) {
        val entry = loadedEntry ?: return
        viewModelScope.launch {
            journalDao.deleteEntry(entry)
            syncEngine.syncJournal()
            onSuccess()
        }
    }
}
