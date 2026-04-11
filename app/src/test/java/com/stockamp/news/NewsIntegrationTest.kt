package com.stockamp.news

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stockamp.data.local.NewsDao
import com.stockamp.data.local.StocKampDatabase
import com.stockamp.data.model.NewsArticleDto
import com.stockamp.data.model.NewsArticleEntity
import com.stockamp.data.model.toDomain
import com.stockamp.data.model.toEntity
import com.stockamp.data.repository.NewsRepositoryImpl
import com.stockamp.data.supabase.SupabaseClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test: fetch → cache → display end-to-end flow.
 *
 * Uses Room in-memory database + mock Supabase client to verify the full
 * data pipeline: fetch articles from mock Supabase → store in Room →
 * read back from Room via repository.
 *
 * Requirements: 9.1, 9.2, 9.3, 9.4, 10.3
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NewsIntegrationTest {

    private lateinit var db: StocKampDatabase
    private lateinit var dao: NewsDao
    private lateinit var supabaseClient: SupabaseClient
    private lateinit var repository: NewsRepositoryImpl

    private fun makeDto(
        id: String,
        publishedAt: String = "2024-01-15T10:00:00Z",
        status: String = "ANALYZED"
    ) = NewsArticleDto(
        id = id,
        title = "Article $id",
        url = "https://example.com/$id",
        summary = "Summary for $id",
        sourceName = "CafeF",
        publishedAt = publishedAt,
        stockSymbols = listOf("VNM", "HPG"),
        sentimentLabel = "POSITIVE",
        sentimentScore = 0.85f,
        status = status
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StocKampDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.newsDao()
        supabaseClient = mockk(relaxed = true)
        repository = NewsRepositoryImpl(dao, supabaseClient)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * End-to-end: fetch from mock Supabase → store in Room → read back via repository.
     *
     * Requirements: 9.1, 9.2
     */
    @Test
    fun `fetch articles from Supabase are stored in Room and readable via repository`() = runTest {
        val dtos = listOf(
            makeDto("1", "2024-01-15T12:00:00Z"),
            makeDto("2", "2024-01-15T11:00:00Z"),
            makeDto("3", "2024-01-15T10:00:00Z")
        )
        coEvery { supabaseClient.fetchNewsArticles(any(), any()) } returns Result.success(dtos)

        // Trigger refresh (cache is empty → will fetch from Supabase)
        val result = repository.refresh()
        assertTrue(result.isSuccess)

        // Read back from Room via repository
        val articles = repository.getLatestNews(10).first()

        assertEquals(3, articles.size)
        // Verify data integrity
        val ids = articles.map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf("1", "2", "3")))
    }

    /**
     * Cache-first: after a successful fetch, a second refresh within TTL skips network.
     *
     * Requirements: 9.2, 9.3
     */
    @Test
    fun `second refresh within TTL reads from cache without network call`() = runTest {
        val dtos = listOf(makeDto("1"), makeDto("2"))
        coEvery { supabaseClient.fetchNewsArticles(any(), any()) } returns Result.success(dtos)

        // First refresh — fetches from network
        repository.refresh()

        // Second refresh immediately — cache is fresh, should skip network
        repository.refresh()

        // fetchNewsArticles should only be called once
        io.mockk.coVerify(exactly = 1) { supabaseClient.fetchNewsArticles(any(), any()) }
    }

    /**
     * Offline fallback: when network fails, cached data is still returned.
     *
     * Requirements: 9.4
     */
    @Test
    fun `offline fallback returns cached data when network fails`() = runTest {
        // Pre-populate cache directly
        val entities = listOf(
            NewsArticleEntity(
                id = "cached-1",
                title = "Cached Article",
                url = "https://example.com/cached-1",
                summary = null,
                sourceName = "VnEconomy",
                publishedAt = System.currentTimeMillis() - 3_600_000L,
                stockSymbols = "[]",
                sentimentLabel = "NEUTRAL",
                sentimentScore = 0.5f,
                status = "ANALYZED",
                cachedAt = System.currentTimeMillis() - 3_600_000L // 1 hour ago (stale)
            )
        )
        dao.insertAll(entities)

        // Network fails
        coEvery { supabaseClient.fetchNewsArticles(any(), any()) } returns
            Result.failure(Exception("No network"))

        // Refresh fails
        val refreshResult = repository.refresh()
        assertTrue(refreshResult.isFailure)

        // But cached data is still accessible
        val articles = repository.getLatestNews(10).first()
        assertEquals(1, articles.size)
        assertEquals("cached-1", articles[0].id)
    }

    /**
     * Cache eviction: after inserting > 200 articles, deleteOldNews keeps only 200.
     *
     * Requirements: 9.5
     */
    @Test
    fun `cache eviction keeps at most 200 articles after refresh`() = runTest {
        val now = System.currentTimeMillis()
        // Insert 250 articles directly into Room
        val entities = (1..250).map { i ->
            NewsArticleEntity(
                id = "id-$i",
                title = "Article $i",
                url = "https://example.com/$i",
                summary = null,
                sourceName = "CafeF",
                publishedAt = now - (250 - i) * 1000L,
                stockSymbols = "[]",
                sentimentLabel = null,
                sentimentScore = null,
                status = "ANALYZED",
                cachedAt = now
            )
        }
        dao.insertAll(entities)
        dao.deleteOldNews()

        val count = dao.getCount()
        assertTrue(count <= 200, "Expected <= 200 articles, got $count")
    }

    /**
     * Sort order: articles returned by getLatestNews are sorted by publishedAt DESC.
     *
     * Requirements: 4.1
     */
    @Test
    fun `getLatestNews returns articles sorted by publishedAt descending`() = runTest {
        val now = System.currentTimeMillis()
        val entities = listOf(
            NewsArticleEntity("id-old", "Old", "https://example.com/old", null, "CafeF",
                now - 3_000L, "[]", null, null, "ANALYZED", now),
            NewsArticleEntity("id-new", "New", "https://example.com/new", null, "CafeF",
                now - 1_000L, "[]", null, null, "ANALYZED", now),
            NewsArticleEntity("id-mid", "Mid", "https://example.com/mid", null, "CafeF",
                now - 2_000L, "[]", null, null, "ANALYZED", now)
        )
        dao.insertAll(entities)

        val articles = repository.getLatestNews(10).first()

        assertEquals(3, articles.size)
        // Verify descending order
        articles.zipWithNext().forEach { (a, b) ->
            assertTrue(
                a.publishedAt >= b.publishedAt,
                "Expected ${a.publishedAt} >= ${b.publishedAt}"
            )
        }
        assertEquals("id-new", articles[0].id)
        assertEquals("id-mid", articles[1].id)
        assertEquals("id-old", articles[2].id)
    }

    /**
     * Symbol filter: getNewsBySymbol returns only articles containing the symbol.
     *
     * Requirements: 6.2, 8.1
     */
    @Test
    fun `getNewsBySymbol returns only articles containing the symbol`() = runTest {
        val now = System.currentTimeMillis()
        val entities = listOf(
            NewsArticleEntity("id-vnm", "VNM Article", "https://example.com/vnm", null, "CafeF",
                now, """["VNM","HPG"]""", null, null, "ANALYZED", now),
            NewsArticleEntity("id-hpg", "HPG Only", "https://example.com/hpg", null, "CafeF",
                now - 1000L, """["HPG"]""", null, null, "ANALYZED", now),
            NewsArticleEntity("id-vic", "VIC Article", "https://example.com/vic", null, "CafeF",
                now - 2000L, """["VIC"]""", null, null, "ANALYZED", now)
        )
        dao.insertAll(entities)

        val articles = repository.getNewsBySymbol("VNM", 10).first()

        // Only articles containing "VNM" should be returned
        assertTrue(articles.isNotEmpty())
        articles.forEach { article ->
            assertTrue(
                article.stockSymbols.contains("VNM"),
                "Article ${article.id} does not contain VNM in ${article.stockSymbols}"
            )
        }
        // VIC article should not be included
        assertTrue(articles.none { it.id == "id-vic" })
    }

    /**
     * Data integrity: domain model fields are preserved through the full pipeline.
     *
     * Requirements: 12.1, 9.6
     */
    @Test
    fun `article fields are preserved through fetch-cache-display pipeline`() = runTest {
        val dto = makeDto("integrity-test", "2024-06-15T08:30:00Z")
        coEvery { supabaseClient.fetchNewsArticles(any(), any()) } returns Result.success(listOf(dto))

        repository.refresh()

        val articles = repository.getLatestNews(10).first()
        val article = articles.find { it.id == "integrity-test" }

        assertTrue(article != null, "Article not found in cache")
        assertEquals("Article integrity-test", article!!.title)
        assertEquals("https://example.com/integrity-test", article.url)
        assertEquals("Summary for integrity-test", article.summary)
        assertEquals("CafeF", article.sourceName)
        assertEquals(listOf("VNM", "HPG"), article.stockSymbols)
        assertEquals(com.stockamp.data.model.SentimentLabel.POSITIVE, article.sentimentLabel)
        assertEquals(0.85f, article.sentimentScore)
        assertEquals(com.stockamp.data.model.ArticleStatus.ANALYZED, article.status)
    }
}
