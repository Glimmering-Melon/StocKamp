package com.stockamp.market

import com.stockamp.data.chart.TechnicalIndicatorCalculator
import com.stockamp.data.local.StockSymbolCacheDao
import com.stockamp.data.market.MarketRepository
import com.stockamp.data.market.MarketRepositoryImpl
import com.stockamp.data.model.ChartUiState
import com.stockamp.data.model.JournalEntry
import com.stockamp.data.model.LatestCloseResult
import com.stockamp.data.model.PriceDataPoint
import com.stockamp.data.model.StockSymbolCacheEntity
import com.stockamp.data.model.StockSymbolInfo
import com.stockamp.data.model.Timeframe
import com.stockamp.data.repository.JournalRepository
import com.stockamp.data.supabase.SupabaseClient
import com.stockamp.data.sync.SyncEngine
import com.stockamp.ui.journal.JournalViewModel
import com.stockamp.ui.market.ChartViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarketUnitTests {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- Property 4: Cache TTL round-trip ----
    // Feature: market-data-service, Property 4: Cache TTL round-trip
    // Within 30 minutes, getAvailableSymbols() returns cached data without network call
    @Test
    fun `Property 4 - cache hit within 30 minutes does not call Supabase`() = runTest {
        val supabaseClient = mockk<SupabaseClient>()
        val cacheDao = mockk<StockSymbolCacheDao>()

        val freshCachedAt = System.currentTimeMillis()
        val cachedEntities = listOf(
            StockSymbolCacheEntity("VNM", "Vinamilk", "HOSE", null, cachedAt = freshCachedAt)
        )

        coEvery { cacheDao.getAll() } returns cachedEntities

        val repo = MarketRepositoryImpl(supabaseClient, cacheDao)
        val result = repo.getAvailableSymbols()

        assertTrue(result.isSuccess)
        assertEquals("VNM", result.getOrThrow().first().symbol)
        // Supabase should NOT have been called
        coVerify(exactly = 0) { supabaseClient.fetchStockSymbols() }
    }

    @Test
    fun `Property 4 - expired cache triggers Supabase call`() = runTest {
        val supabaseClient = mockk<SupabaseClient>()
        val cacheDao = mockk<StockSymbolCacheDao>()

        val expiredCachedAt = System.currentTimeMillis() - 31 * 60 * 1000L
        val cachedEntities = listOf(
            StockSymbolCacheEntity("VNM", "Vinamilk", "HOSE", null, cachedAt = expiredCachedAt)
        )
        val freshSymbols = listOf(StockSymbolInfo("VNM", "Vinamilk", "HOSE", null))

        coEvery { cacheDao.getAll() } returns cachedEntities
        coEvery { supabaseClient.fetchStockSymbols() } returns Result.success(freshSymbols)
        coEvery { cacheDao.deleteAll() } returns Unit
        coEvery { cacheDao.insertAll(any()) } returns Unit

        val repo = MarketRepositoryImpl(supabaseClient, cacheDao)
        val result = repo.getAvailableSymbols()

        assertTrue(result.isSuccess)
        // Supabase SHOULD have been called because cache is expired
        coVerify(exactly = 1) { supabaseClient.fetchStockSymbols() }
    }

    // ---- Property 5: Fallback to cache on network failure ----
    // Feature: market-data-service, Property 5: Fallback to cache on network failure
    // When Supabase fails, getAvailableSymbols() returns cached data
    @Test
    fun `Property 5 - fallback to stale cache when Supabase fails`() = runTest {
        val supabaseClient = mockk<SupabaseClient>()
        val cacheDao = mockk<StockSymbolCacheDao>()

        val expiredCachedAt = System.currentTimeMillis() - 31 * 60 * 1000L
        val cachedEntities = listOf(
            StockSymbolCacheEntity("VNM", "Vinamilk", "HOSE", null, cachedAt = expiredCachedAt)
        )

        coEvery { cacheDao.getAll() } returns cachedEntities
        coEvery { supabaseClient.fetchStockSymbols() } returns Result.failure(Exception("Network error"))

        val repo = MarketRepositoryImpl(supabaseClient, cacheDao)
        val result = repo.getAvailableSymbols()

        // Should succeed with cached data despite Supabase failure
        assertTrue(result.isSuccess)
        assertEquals("VNM", result.getOrThrow().first().symbol)
    }

    // ---- Task 15.6: Unit tests ----

    // Default timeframe 1D
    // Feature: market-data-service, Req 5.2: default timeframe is 1D
    @Test
    fun `loadChartData uses default timeframe 1D`() = runTest {
        val marketRepository = mockk<MarketRepository>()
        val indicatorCalculator = mockk<TechnicalIndicatorCalculator>()

        val sampleData = listOf(
            PriceDataPoint(1_000_000L, 10.0, 12.0, 9.0, 11.0, 1000L)
        )

        coEvery { marketRepository.getOhlcv("VNM", "1D") } returns Result.success(sampleData)
        every { indicatorCalculator.calculateMA(any(), any()) } returns emptyList()

        val viewModel = ChartViewModel(marketRepository, indicatorCalculator)
        viewModel.loadChartData("VNM")
        advanceUntilIdle()

        coVerify(exactly = 1) { marketRepository.getOhlcv("VNM", Timeframe.ONE_DAY.apiValue) }
    }

    // Empty data state
    // Feature: market-data-service, Req 5.5: empty data shows success with empty list
    @Test
    fun `loadChartData with empty result produces Success state with empty priceData`() = runTest {
        val marketRepository = mockk<MarketRepository>()
        val indicatorCalculator = mockk<TechnicalIndicatorCalculator>()

        coEvery { marketRepository.getOhlcv(any(), any()) } returns Result.success(emptyList())
        every { indicatorCalculator.calculateMA(any(), any()) } returns emptyList()

        val viewModel = ChartViewModel(marketRepository, indicatorCalculator)
        viewModel.loadChartData("VNM")
        advanceUntilIdle()

        val state = viewModel.chartState.value
        assertTrue(state is ChartUiState.Success)
        assertTrue((state as ChartUiState.Success).priceData.isEmpty())
    }

    // SELL entry no margin
    // Feature: market-data-service, Req 7.5: SELL entries do not trigger getLatestClose
    @Test
    fun `JournalViewModel does not call getLatestClose for SELL entries`() = runTest {
        val journalRepository = mockk<JournalRepository>()
        val syncEngine = mockk<SyncEngine>()
        val marketRepository = mockk<MarketRepository>()

        val sellEntry = JournalEntry(
            id = 1L,
            symbol = "VNM",
            action = "SELL",
            quantity = 100
        )

        every { journalRepository.getAllEntries() } returns flowOf(listOf(sellEntry))
        coEvery { journalRepository.getTotalTrades() } returns 1
        coEvery { syncEngine.syncJournal() } returns Result.success(Unit)

        val viewModel = JournalViewModel(journalRepository, syncEngine, marketRepository)
        advanceUntilIdle()

        // getLatestClose should NOT be called for SELL entries
        coVerify(exactly = 0) { marketRepository.getLatestClose(any()) }
    }

    // Date formatter
    // Feature: market-data-service, Req 7.3: date formatted as DD/MM/YYYY
    @Test
    fun `formatDate converts ISO date to DD-MM-YYYY format`() {
        fun formatDate(isoDate: String): String {
            val parts = isoDate.split("-")
            return if (parts.size == 3) "${parts[2]}/${parts[1]}/${parts[0]}" else isoDate
        }

        assertEquals("15/01/2024", formatDate("2024-01-15"))
        assertEquals("01/12/2023", formatDate("2023-12-01"))
        assertEquals("31/07/2025", formatDate("2025-07-31"))
        // Invalid input returns as-is
        assertEquals("not-a-date", formatDate("not-a-date"))
    }

    // BUY entry DOES trigger getLatestClose (complementary test)
    @Test
    fun `JournalViewModel calls getLatestClose for BUY entries`() = runTest {
        val journalRepository = mockk<JournalRepository>()
        val syncEngine = mockk<SyncEngine>()
        val marketRepository = mockk<MarketRepository>()

        val buyEntry = JournalEntry(
            id = 2L,
            symbol = "VNM",
            action = "BUY",
            quantity = 100
        )

        every { journalRepository.getAllEntries() } returns flowOf(listOf(buyEntry))
        coEvery { journalRepository.getTotalTrades() } returns 1
        coEvery { syncEngine.syncJournal() } returns Result.success(Unit)
        coEvery { marketRepository.getLatestClose("VNM") } returns Result.success(
            LatestCloseResult("VNM", 55.0, "2024-01-15")
        )

        val viewModel = JournalViewModel(journalRepository, syncEngine, marketRepository)
        advanceUntilIdle()

        coVerify(atLeast = 1) { marketRepository.getLatestClose("VNM") }
    }
}
