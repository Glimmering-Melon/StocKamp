package com.stockamp.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.stockamp.data.model.NewsArticle
import com.stockamp.data.repository.NewsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewsViewModel @Inject constructor(
    private val repository: NewsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<NewsUiState>(NewsUiState.Loading)
    val uiState: StateFlow<NewsUiState> = _uiState.asStateFlow()

    val newsPagingData: Flow<PagingData<NewsArticle>> =
        repository.getNewsStream().cachedIn(viewModelScope)

    private val _activeFilters = MutableStateFlow<List<String>>(emptyList())

    init {
        observeLatestNews()
    }

    private fun observeLatestNews() {
        repository.getLatestNews(limit = 5)
            .onEach { articles ->
                val currentFilters = _activeFilters.value
                _uiState.update {
                    NewsUiState.Success(
                        latestNews = articles,
                        activeFilters = currentFilters,
                        isRefreshing = false
                    )
                }
            }
            .catch { e ->
                val hasCached = (_uiState.value as? NewsUiState.Success)?.latestNews?.isNotEmpty() == true
                _uiState.update { NewsUiState.Error(e.message ?: "Unknown error", hasCached) }
            }
            .launchIn(viewModelScope)
    }

    fun applyFilter(symbols: List<String>) {
        _activeFilters.value = symbols
        _uiState.update { current ->
            when (current) {
                is NewsUiState.Success -> current.copy(activeFilters = symbols)
                else -> current
            }
        }
    }

    fun clearFilter() {
        _activeFilters.value = emptyList()
        _uiState.update { current ->
            when (current) {
                is NewsUiState.Success -> current.copy(activeFilters = emptyList())
                else -> current
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { current ->
                when (current) {
                    is NewsUiState.Success -> current.copy(isRefreshing = true)
                    else -> NewsUiState.Loading
                }
            }
            val result = repository.refresh()
            result.onFailure { e ->
                val hasCached = (_uiState.value as? NewsUiState.Success)?.latestNews?.isNotEmpty() == true
                _uiState.update { NewsUiState.Error(e.message ?: "Refresh failed", hasCached) }
            }
        }
    }

    fun loadForSymbol(symbol: String) {
        applyFilter(listOf(symbol))
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            repository.unsubscribeRealtime()
        }
    }
}
