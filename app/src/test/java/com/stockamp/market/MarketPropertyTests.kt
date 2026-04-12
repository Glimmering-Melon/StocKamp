package com.stockamp.market

import com.stockamp.data.market.MarketRepository
import com.stockamp.data.market.ProfitMarginCalculator
import com.stockamp.data.market.SymbolValidatorImpl
import com.stockamp.data.model.PriceDataPoint
import com.stockamp.data.model.StockSymbolInfo
import com.stockamp.data.model.ValidationResult
import io.kotest.matchers.doubles.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.arbitrary.string
import io.kotest.property.assume
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MarketPropertyTests {

    // ---- Arbitraries ----

    private val priceDataPointArb: Arb<PriceDataPoint> = Arb.bind(
        Arb.long(1_000_000L, 9_999_999_999_999L),
        Arb.numericDouble(min = 0.01, max = 1_000_000.0),
        Arb.numericDouble(min = 0.01, max = 1_000_000.0),
        Arb.numericDouble(min = 0.01, max = 1_000_000.0),
        Arb.numericDouble(min = 0.01, max = 1_000_000.0),
        Arb.long(0L, 1_000_000_000L)
    ) { ts, open, high, low, close, volume ->
        PriceDataPoint(
            timestamp = ts,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume
        )
    }

    // ---- Property 6: OHLCV sort order ----
    // Feature: market-data-service, Property 6: OHLCV sort order
    // For any list of PriceDataPoints, getOhlcv() returns them sorted ascending by timestamp
    // Validates: Requirements 4.2
    @Test
    fun `Property 6 - getOhlcv returns list sorted ascending by timestamp`() = runTest {
        val mockRepo = mockk<MarketRepository>()

        checkAll(100, Arb.list(priceDataPointArb, 1..50)) { points ->
            val shuffled = points.shuffled()
            coEvery { mockRepo.getOhlcv(any(), any()) } returns Result.success(
                shuffled.sortedBy { it.timestamp }
            )

            val result = mockRepo.getOhlcv("VNM", "1D").getOrThrow()

            result.zipWithNext { a, b -> a.timestamp <= b.timestamp }
                .all { it } shouldBe true
        }
    }

    // ---- Property 7: Symbol validation rejects unknown symbols ----
    // Feature: market-data-service, Property 7: Symbol validation rejects unknown symbols
    // For any symbol not in the known list, validate() returns Invalid with correct message
    // Validates: Requirements 6.1, 6.2
    @Test
    fun `Property 7 - validate returns Invalid for unknown symbols`() = runTest {
        val knownSymbols = setOf("VNM", "VIC", "VHM", "HPG", "MSN", "TCB", "VCB", "BID", "CTG", "MBB")

        val knownSymbolInfos = knownSymbols.map { sym ->
            StockSymbolInfo(symbol = sym, name = sym, exchange = "HOSE", sector = null)
        }

        val mockRepo = mockk<MarketRepository>()
        coEvery { mockRepo.getAvailableSymbols() } returns Result.success(knownSymbolInfos)

        val validator = SymbolValidatorImpl(mockRepo)

        checkAll(100, Arb.string(minSize = 1, maxSize = 10)) { symbol ->
            assume(symbol.uppercase() !in knownSymbols)

            val result = validator.validate(symbol)
            result shouldBe ValidationResult.Invalid("Mã cổ phiếu không có trong hệ thống")
        }
    }

    // ---- Property 8: Profit margin formula correctness ----
    // Feature: market-data-service, Property 8: Profit margin formula correctness
    // ProfitMarginCalculator.calculate(entryPrice, latestClose) == (latestClose - entryPrice) / entryPrice * 100.0
    // Validates: Requirements 7.1
    @Test
    fun `Property 8 - profit margin formula is correct`() = runTest {
        checkAll(
            100,
            Arb.numericDouble(min = 0.01, max = 1_000_000.0),
            Arb.numericDouble(min = 0.0, max = 1_000_000.0)
        ) { entryPrice, latestClose ->
            val result = ProfitMarginCalculator.calculate(entryPrice, latestClose)
            val expected = (latestClose - entryPrice) / entryPrice * 100.0

            result shouldBeWithinPercentageOf(expected, 0.0001)
        }
    }
}
