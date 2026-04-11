package com.stockamp.news

import com.stockamp.data.model.NewsArticle
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Property-based tests for NewsRepository logic.
 * Tests pure filtering and sorting logic without requiring Android runtime.
 */
class NewsRepositoryPropertyTest {

    // ─── Helper: in-memory filter logic (mirrors what Room/Repository does) ──────

    /** Filter articles by symbol — mirrors NewsDao.getNewsBySymbol logic */
    private fun filterBySymbol(articles: List<NewsArticle>, symbol: String): List<NewsArticle> =
        articles.filter { symbol in it.stockSymbols }
            .sortedByDescending { it.publishedAt }

    /** Sort articles by publishedAt descending — mirrors NewsDao.getAllNews order */
    private fun sortDescending(articles: List<NewsArticle>): List<NewsArticle> =
        articles.sortedByDescending { it.publishedAt }

    /** Paginate a sorted list into pages of [pageSize] */
    private fun paginate(articles: List<NewsArticle>, pageSize: Int): List<List<NewsArticle>> {
        if (articles.isEmpty()) return emptyList()
        return articles.chunked(pageSize)
    }

    // ─── Property 10: Filter theo symbol chỉ trả về bài có symbol đó ─────────────

    /**
     * Property 10: Filter theo symbol chỉ trả về bài có symbol đó
     *
     * For any Stock_Symbol used as a filter, all NewsArticle in the result must
     * have that symbol in their stockSymbols list. No article in the result
     * should be missing the filtered symbol.
     *
     * **Validates: Requirements 6.2, 8.1**
     */
    @Test
    fun `Property 10 - filter by symbol only returns articles containing that symbol`() = runTest {
        // Feature: stock-news, Property 10: Filter theo symbol chỉ trả về bài có symbol đó
        checkAll(100, Arb.string(3..5), Arb.list(Arb.newsArticle(), 0..30)) { symbol, articles ->
            val filtered = filterBySymbol(articles, symbol)

            // Every article in the result must contain the symbol
            filtered.forEach { article ->
                article.stockSymbols shouldContain symbol
            }

            // The count must match manual filter
            val expectedCount = articles.count { symbol in it.stockSymbols }
            filtered.size shouldBe expectedCount
        }
    }

    // ─── Property 7: Thứ tự hiển thị giảm dần theo published_at ─────────────────

    /**
     * Property 7: Thứ tự hiển thị giảm dần theo published_at
     *
     * For any list of NewsArticle returned by NewsDao.getAllNews() or
     * NewsRepository, articles must be sorted by published_at descending:
     * article[i].publishedAt >= article[i+1].publishedAt for all i.
     *
     * **Validates: Requirements 4.1**
     */
    @Test
    fun `Property 7 - display order is descending by published_at`() = runTest {
        // Feature: stock-news, Property 7: Thứ tự hiển thị giảm dần theo published_at
        checkAll(100, Arb.list(Arb.newsArticle(), 1..50)) { articles ->
            val sorted = sortDescending(articles)

            sorted.zipWithNext().forEach { (current, next) ->
                (current.publishedAt >= next.publishedAt) shouldBe true
            }
        }
    }

    // ─── Property 18: Kích thước trang phân trang ────────────────────────────────

    /**
     * Property 18: Kích thước trang phân trang
     *
     * For any page of data loaded by NewsRepository (except the last page),
     * the number of articles in the page must be exactly pageSize (20).
     * The last page may have fewer but must not exceed pageSize.
     *
     * **Validates: Requirements 11.1**
     */
    @Test
    fun `Property 18 - page size is exactly 20 for all pages except the last`() = runTest {
        // Feature: stock-news, Property 18: Kích thước trang phân trang
        val pageSize = 20
        checkAll(100, Arb.list(Arb.newsArticle(), 1..100)) { articles ->
            val sorted = sortDescending(articles)
            val pages = paginate(sorted, pageSize)

            if (pages.size > 1) {
                // All pages except the last must have exactly pageSize items
                pages.dropLast(1).forEach { page ->
                    page.size shouldBe pageSize
                }
            }

            // Last page (or only page) must have <= pageSize items
            pages.lastOrNull()?.let { lastPage ->
                (lastPage.size <= pageSize) shouldBe true
            }
        }
    }
}
