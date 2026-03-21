package com.stockamp.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stockamp.data.sync.SyncEngine
import com.stockamp.data.sync.SyncErrorLog
import com.stockamp.data.sync.SyncState
import com.stockamp.data.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncEngine: SyncEngine
) : ViewModel() {

    val syncStatus: StateFlow<SyncStatus> = syncEngine.getSyncStatus()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SyncStatus(
                state = SyncState.OFFLINE,
                pendingCount = 0,
                lastSync = null,
                error = null
            )
        )

    private val _errorLogs = MutableStateFlow<List<SyncErrorLog>>(emptyList())
    val errorLogs: StateFlow<List<SyncErrorLog>> = _errorLogs.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _errorLogs.value = syncEngine.getErrorLogs()
                delay(30_000L)
            }
        }
    }

    fun refreshErrorLogs() {
        viewModelScope.launch {
            _errorLogs.value = syncEngine.getErrorLogs()
        }
    }
}
