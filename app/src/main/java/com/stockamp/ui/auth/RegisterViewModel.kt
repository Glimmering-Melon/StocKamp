package com.stockamp.ui.auth

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

data class RegisterUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val registerSuccess: Boolean = false,
    val emailConfirmationRequired: Boolean = false
)

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    fun register(email: String, password: String, displayName: String) {
        // Client-side validation
        if (password.length < 8) {
            _uiState.update { it.copy(errorMessage = "Mật khẩu phải có ít nhất 8 ký tự") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val result = authManager.register(email, password)

            if (result.isSuccess) {
                val profile = result.getOrThrow()
                // Nếu id rỗng → cần xác nhận email
                if (profile.id.isBlank()) {
                    _uiState.update { it.copy(isLoading = false, emailConfirmationRequired = true) }
                } else {
                    _uiState.update { it.copy(isLoading = false, registerSuccess = true) }
                }
            } else {
                val message = result.exceptionOrNull()?.message ?: "Đăng ký thất bại"
                _uiState.update { it.copy(isLoading = false, errorMessage = message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
