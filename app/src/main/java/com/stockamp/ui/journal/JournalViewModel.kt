package com.stockamp.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stockamp.data.model.JournalEntry
import com.stockamp.data.repository.JournalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class JournalUiState(
    val entries: List<JournalEntry> = emptyList(),
    val totalTrades: Int = 0,
    val totalPnL: Double = 0.0,
    val isLoading: Boolean = true
)

@HiltViewModel
class JournalViewModel @Inject constructor(
    private val journalRepository: JournalRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(JournalUiState())
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            journalRepository.loadSampleData()
        }
        viewModelScope.launch {
            journalRepository.getAllEntries().collect { entries ->
                val totalPnL = journalRepository.getTotalPnL()
                val totalTrades = journalRepository.getTotalTrades()
                _uiState.update {
                    it.copy(
                        entries = entries,
                        totalPnL = totalPnL,
                        totalTrades = totalTrades,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun deleteEntry(entry: JournalEntry) {
        viewModelScope.launch {
            journalRepository.deleteEntry(entry)
        }
    }
}
