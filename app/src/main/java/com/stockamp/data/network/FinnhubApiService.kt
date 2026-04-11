package com.stockamp.data.network

import com.stockamp.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service layer for Finnhub REST API.
 * Docs: https://finnhub.io/docs/api
 *
 * Free tier: 60 API calls/minute
 */
@Singleton
class FinnhubApiService @Inject constructor(
    private val httpClient: HttpClient
) {
    private val baseUrl = "https://finnhub.io/api/v1"
    private val apiKey = BuildConfig.FINNHUB_KEY

    /**
     * Get real-time quote for a stock symbol.
     * Returns current price, change, percent change, high, low, open, previous close.
     */
    suspend fun getQuote(symbol: String): Result<FinnhubQuoteResponse> = runCatching {
        val response = httpClient.get("$baseUrl/quote") {
            parameter("symbol", symbol)
            parameter("token", apiKey)
        }
        if (!response.status.isSuccess()) {
            handleError(response.status.value)
        }
        response.body<FinnhubQuoteResponse>()
    }

    /**
     * Get historical OHLCV candle data for charting.
     *
     * @param symbol Stock ticker (e.g. "AAPL")
     * @param resolution Candle resolution: 1, 5, 15, 30, 60, D, W, M
     * @param from Start UNIX timestamp (seconds)
     * @param to End UNIX timestamp (seconds)
     */
    suspend fun getCandles(
        symbol: String,
        resolution: String,
        from: Long,
        to: Long
    ): Result<FinnhubCandleResponse> = runCatching {
        val response = httpClient.get("$baseUrl/stock/candle") {
            parameter("symbol", symbol)
            parameter("resolution", resolution)
            parameter("from", from)
            parameter("to", to)
            parameter("token", apiKey)
        }
        if (!response.status.isSuccess()) {
            handleError(response.status.value)
        }
        val body = response.body<FinnhubCandleResponse>()
        if (body.s == "no_data") {
            error("No candle data available for $symbol")
        }
        body
    }

    private fun handleError(statusCode: Int): Nothing {
        val msg = when (statusCode) {
            401 -> "Invalid API key"
            403 -> "Access denied - check API key permissions"
            429 -> "Rate limit exceeded (60 calls/min). Please wait and try again."
            in 500..599 -> "Finnhub server error, please try again"
            else -> "API request failed (HTTP $statusCode)"
        }
        error(msg)
    }
}
