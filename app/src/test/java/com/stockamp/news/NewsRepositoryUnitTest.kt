package com.stockamp.news

import com.stockamp.data.local.NewsDao
import com.stockamp.data.model.NewsArticleDto
import com.stockamp.data.model.NewsArticleEntity
import com.stockamp.data.repository.NewsRepositoryImpl
import com.stockamp.data.supabase.SupabaseClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NewsRepositoryUnitTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var newsDao: NewsDao
    private lateinit var supabaseClient: SupabaseClient
    private lateinit var repository: NewsRepositoryImpl

    private val CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes

    private fun makeDto(id: String = "id-1") = NewsArticleDto(
        id = id,
        title = "Title $id",
        url = "https://example.com/$id",
        sourceName = "CafeF",
        publishedAt = "2024-01-15T10:00:00Z",
        status = "ANALYZED"
    )

    private fun makeEntity(id: String = "id-1", publishedAt: Long = System.currentTimeMillis()) =
        NewsArticleEntity(
            id = id,
            title = "Title $id",
            url = "https://example.com/$id",
            summary = null,
            sourceName = "CafeF",
            publishedAt = publishedAt,
            stockSymbols = "[]",
            sentimentLabel = "POSITIVE",
            sentimentScore = 0.85f,
            status = "ANALYZED",
            cachedAt = System.currentTimeMillis()
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        newsDao = mockk(relaxed = true)
        supabaseClient = mockk(relaxed = true)

        every { newsDao.getAllNews() } returns mockk(relaxed = true)
        every { newsDao.getNewsBySymbol(any(), any()) } returns flowOf(emptyList())
        every { newsDao.getLatestNews(any()) } returns flowOf(emptyList())
        coEvery { newsDao.insertAll(any()) } returns Unit
        coEvery { newsDao.deleteOldNews() } returns Unit
        coEvery { newsDao.getCount() } returns 0
        coEvery { newsDao.getMostRecentCachedAt() } returns null

        repository = NewsRepositoryImpl(newsDao, supabaseClient)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── Cache-first logic ────────────────────────────────────────────────────

    /**
     * Requirements 9.2, 9.3: When cache is fresh (< 10 min), refresh() skips network fetch.
     */
    @Test
    fun `refresh skips network fetch when cache is fresh`() = runTest {
        val freshCachedAt = System.currentTimeMillis() - (CACHE_TTL_MS / 2) // 5 min ago
        coEvery { newsDao.getMostRecentCachedAt() } returns freshCachedAt
        coEvery { newsDao.getCount() } returns 10

        val result = repository.refresh()

        assertTrue(result.isSuccess)
        // Supabase should NOT be called
        coVerify(exactly = 0) { supabaseClient.fetchNewsArticles(any(), any()) }
    }

    /**
     * Requirements 9.3: When cache is stale (> 10 min), refresh() fetches from network.
     */
    @Test
    fun `refresh fetches from network when cache is stale`() = runTest {
        val staleCachedAt = System.currentTimeMillis() - (CACHE_TTL_MS + 60_000L) // 11 min ago
        coEvery { newsDao.getMostRecentCachedAt() } returns staleCachedAt
        coEvery { newsDao.getCount() } returns 5
        coEvery { supabaseClient.fetchNewsArticles(any(), any()) } returns
            Result.success(listOf(makeDto("1"), makeDto("2")))

        val result = repository.refresh()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { supabaseClient.fetchNewsArticles(any(), any()) }
    }

    /**
     * Requirements 9.3: When cache is empty (null cachedAt), refresh() fetches from network.
     */
    @Test
    fun `refresh fetches from network when cache is empty`() = runTest {
        coEvery { newsDao.getMostRecentCachedAt() } returns null
        coEvery { newsDao.getCount() } returns 0
        coEvery { supabaseClient.fetchNewsArticles(any(), any()) } returns
            Result.success(listOf(makeDto("1")))

        val result = repository.refresh()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { supabaseClient.fetchNewsArticles(any(), any()) }
    }

    /**
     * Requirements 9.3: When cachedAt is 0 (epoch), treat as stale and fetch.
     */
    @Test
    fun `refresh fetches from network when cachedAt is zero`() = runTest {
        coEvery { newsDao.getMostRecentCachedAt() } returns 0L
        coEvery { newsDao.getCount() } returns 0
        coEvery { supabaseClient.fetchNewsArticles(any(), any()) } returns
            Result.success(emptyList())

        val result = repository.refresh()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { supabaseClient.fetchNewsArticles(any(), any()) }
    }

    /**
     * Requirements 9.1, 9.5: After fetching, articles are inserted and old ones deleted.
     */
    @Test
    fun `refresh inserts fetched articles and calls deleteOldNews`() = runTest {
        coEvery { newsDao.getMostRecentCachedAt() } returns null
        coEvery { supabaseClient.fetchNewsArticles(any(), any()) } returns
            Result.success(listOf(makeDto("1"), makeDto("2"), makeDto("3")))

        repository.refresh()

        coVerify(exactly = 1) { newsDao.insertAll(any()) }
        coVerify(exactly = 1) { newsDao.deleteOldNews() }
    }

    /**
     * Requirements 9.4: When offline (network failure), refresh() returns failure
     * but does not crash. Cached data remains accessible via getLatestNews().
     */
    @Test
    fun `refresh returns failure when network is unavailable`() = runTest {
        coEvery { newsDao.getMostRecentCachedAt() } returns null
        coEvery { supabaseClient.fetchNewsArticles(any(), any()) } returns
            Result.failure(Exception("No network"))

        val result = repository.refresh()

        assertTrue(result.isFailure)
        // DAO insert should NOT be called on failure
        coVerify(exactly = 0) { newsDao.insertAll(any()) }
    }

    /**
     * Requirements 9.4: Offline fallback — getLatestNews() returns cached data
     * from Room without requiring a network call.
     */
    @Test
    fun `getLatestNews returns cached data without network call`() = runTest {
        val cachedEntities = listOf(makeEntity("1"), makeEntity("2"))
        every { newsDao.getLatestNews(5) } returns flowOf(cachedEntities)

        val flow = repository.getLatestNews(5)

        // Collect first emission
        val collected = mutableListOf<com.stockamp.data.model.NewsArticle>()
        val job = launch {
            flow.collect { collected.addAll(it) }
        }
        advanceUntilIdle()
        job.cancel()

        assertTrue(collected.size == 2)
        // No network call was made
        coVerify(exactly = 0) { supabaseClient.fetchNewsArticles(any(), any()) }
    }

    /**
     * Requirements 9.4: getNewsBySymbol() returns cached data without network call.
     */
    @Test
    fun `getNewsBySymbol returns cached data without network call`() = runTest {
        val cachedEntities = listOf(makeEntity("1"))
        every { newsDao.getNewsBySymbol("VNM", 10) } returns flowOf(cachedEntities)

        val flow = repository.getNewsBySymbol("VNM", 10)

        val collected = mutableListOf<com.stockamp.data.model.NewsArticle>()
        val job = launch {
            flow.collect { collected.addAll(it) }
        }
        advanceUntilIdle()
        job.cancel()

        assertTrue(collected.size == 1)
        coVerify(exactly = 0) { supabaseClient.fetchNewsArticles(any(), any()) }
    }

    // ─── TTL boundary tests ───────────────────────────────────────────────────

    /**
     * Requirements 9.3: Cache exactly at TTL boundary (10 min) is treated as stale.
     */
    @Test
    fun `refresh fetches when cache age equals TTL exactly`() = runTest {
        val exactTtlCachedAt = System.currentTimeMillis() - CACHE_TTL_MS
        coEvery { newsDao.getMostRecentCachedAt() } returns exactTtlCachedAt
        coEvery { newsDao.getCount() } returns 5
        coEvery { supabaseClient.fetchNewsArticles(any(), any()) } returns
            Result.success(emptyList())

        repository.refresh()

        // At exactly TTL, age >= TTL so it should fetch
        coVerify(exactly = 1) { supabaseClient.fetchNewsArticles(any(), any()) }
    }

    /**
     * Requirements 9.3: Cache well within TTL (5 minutes old) is still fresh.
     */
    @Test
    fun `refresh skips fetch when cache is well within TTL`() = runTest {
        val freshCachedAt = System.currentTimeMillis() - (CACHE_TTL_MS / 2) // 5 min ago
        coEvery { newsDao.getMostRecentCachedAt() } returns freshCachedAt
        coEvery { newsDao.getCount() } returns 5

        repository.refresh()

        coVerify(exactly = 0) { supabaseClient.fetchNewsArticles(any(), any()) }
    }

    // ─── Invalid DTO filtering ────────────────────────────────────────────────

    /**
     * Requirements 12.2, 12.3: Invalid DTOs (missing required fields) are filtered out
     * before inserting into Room.
     */
    @Test
    fun `refresh filters out invalid DTOs before inserting`() = runTest {
        coEvery { newsDao.getMostRecentCachedAt() } returns null
        val dtos = listOf(
            makeDto("valid-1"),
            makeDto("valid-2").copy(title = ""),   // invalid: blank title
            makeDto("valid-3").copy(url = ""),      // invalid: blank url
            makeDto("valid-4")
        )
        coEvery { supabaseClient.fetchNewsArticles(any(), any()) } returns Result.success(dtos)

        val insertedEntities = mutableListOf<List<NewsArticleEntity>>()
        coEvery { newsDao.insertAll(capture(insertedEntities)) } returns Unit

        repository.refresh()

        // Only 2 valid DTOs should be inserted
        assertTrue(insertedEntities.isNotEmpty())
        assertTrue(insertedEntities.first().size == 2)
    }
}
