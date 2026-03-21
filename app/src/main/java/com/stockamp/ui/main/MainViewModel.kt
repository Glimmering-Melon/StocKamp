package com.stockamp.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stockamp.data.auth.AuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val isLoading: Boolean = true,
    val isAuthenticated: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        restoreSession()
    }

    private fun restoreSession() {
        viewModelScope.launch {
            val result = authManager.restoreSession()
            val isAuthenticated = result.isSuccess && result.getOrNull() != null
            _uiState.update { it.copy(isLoading = false, isAuthenticated = isAuthenticated) }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authManager.logout()
            _uiState.update { it.copy(isAuthenticated = false) }
        }
    }
}
