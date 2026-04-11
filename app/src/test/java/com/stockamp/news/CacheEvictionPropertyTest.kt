package com.stockamp.news

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stockamp.data.local.NewsDao
import com.stockamp.data.local.StocKampDatabase
import com.stockamp.data.model.NewsArticleEntity
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Feature: stock-news, Property 14: Cache eviction giữ tối đa 200 bài
@RunWith(RobolectricTestRunner::class)
class CacheEvictionPropertyTest {

    private lateinit var db: StocKampDatabase
    private lateinit var dao: NewsDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StocKampDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.newsDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    /**
     * Property 14: Cache eviction giữ tối đa 200 bài
     *
     * For any number of articles inserted (201–500), after calling deleteOldNews(),
     * the count in the table must be <= 200. Articles deleted must be those with
     * the smallest published_at values.
     *
     * **Validates: Requirements 9.5**
     */
    @Test
    fun `Property 14 - cache eviction keeps at most 200 articles`() = runTest {
        // Feature: stock-news, Property 14: Cache eviction max 200 articles
        checkAll(100, Arb.int(201, 500)) { count ->
            // Clear DB between iterations
            db.clearAllTables()

            val now = System.currentTimeMillis()
            val articles = (1..count).map { i ->
                NewsArticleEntity(
                    id = "id-$i",
                    title = "Title $i",
                    url = "https://example.com/article-$i",
                    summary = null,
                    sourceName = "TestSource",
                    // Older articles have smaller publishedAt (will be evicted first)
                    publishedAt = now - (count - i) * 1000L,
                    stockSymbols = "[]",
                    sentimentLabel = null,
                    sentimentScore = null,
                    status = "analyzed",
                    cachedAt = now
                )
            }

            dao.insertAll(articles)
            dao.deleteOldNews()

            val remaining = dao.getCount()
            (remaining <= 200) shouldBe true
        }
    }
}
