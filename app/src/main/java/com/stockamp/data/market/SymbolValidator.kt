package com.stockamp.data.market

import com.stockamp.data.model.StockSymbolInfo
import javax.inject.Inject
import javax.inject.Singleton

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val message: String) : ValidationResult()
}

interface SymbolValidator {
    suspend fun validate(symbol: String): ValidationResult
    fun getSuggestions(query: String): List<StockSymbolInfo>
}

@Singleton
class SymbolValidatorImpl @Inject constructor(
    private val marketRepository: MarketRepository
) : SymbolValidator {

    private var cachedSymbols: List<StockSymbolInfo> = emptyList()

    override suspend fun validate(symbol: String): ValidationResult {
        if (cachedSymbols.isEmpty()) {
            marketRepository.getAvailableSymbols().getOrNull()?.let {
                cachedSymbols = it
            }
        }
        return if (cachedSymbols.any { it.symbol == symbol.uppercase() }) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("Mã cổ phiếu không có trong hệ thống")
        }
    }

    override fun getSuggestions(query: String): List<StockSymbolInfo> {
        val upperQuery = query.uppercase()
        return cachedSymbols
            .filter { it.symbol.startsWith(upperQuery) || it.name.contains(query, ignoreCase = true) }
            .sortedBy { it.symbol }
            .take(10)
    }

    suspend fun refreshCache() {
        marketRepository.getAvailableSymbols().getOrNull()?.let {
            cachedSymbols = it
        }
    }
}
