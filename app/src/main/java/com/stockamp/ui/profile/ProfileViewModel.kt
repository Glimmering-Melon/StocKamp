package com.stockamp.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stockamp.data.auth.AuthManager
import com.stockamp.data.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val isLoading: Boolean = false,
    val profile: UserProfile? = null,
    val errorMessage: String? = null,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val isDeleting: Boolean = false,
    val deleteSuccess: Boolean = false
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authManager: AuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
    }

    fun loadProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authManager.getProfile()
            if (result.isSuccess) {
                _uiState.update { it.copy(isLoading = false, profile = result.getOrNull()) }
            } else {
                val message = result.exceptionOrNull()?.message ?: "Không thể tải hồ sơ"
                _uiState.update { it.copy(isLoading = false, errorMessage = message) }
            }
        }
    }

    fun startEditing() {
        _uiState.update { it.copy(isEditing = true, saveSuccess = false) }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(isEditing = false) }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, errorMessage = null) }
            val result = authManager.deleteAccount()
            if (result.isSuccess) {
                _uiState.update { it.copy(isDeleting = false, deleteSuccess = true) }
            } else {
                val message = result.exceptionOrNull()?.message ?: "Không thể xóa tài khoản"
                _uiState.update { it.copy(isDeleting = false, errorMessage = message) }
            }
        }
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val result = authManager.updateProfile(name)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        isEditing = false,
                        saveSuccess = true,
                        profile = result.getOrNull()
                    )
                }
            } else {
                val message = result.exceptionOrNull()?.message ?: "Không thể cập nhật hồ sơ"
                _uiState.update { it.copy(isSaving = false, errorMessage = message) }
            }
        }
    }
}
