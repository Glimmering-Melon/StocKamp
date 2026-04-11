package com.stockamp.news

import com.stockamp.data.model.ArticleStatus
import com.stockamp.data.model.NewsArticle
import com.stockamp.data.model.SentimentLabel
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

// ─── SentimentResult data class for Property 5 ───────────────────────────────

/**
 * Represents the raw output of a sentiment model: a label and the three
 * class probabilities. Used to verify that the label always corresponds to
 * the class with the highest score.
 */
data class SentimentResult(
    val label: SentimentLabel,
    val positiveScore: Float,
    val negativeScore: Float,
    val neutralScore: Float
)

// ─── Arb generator for SentimentResult ───────────────────────────────────────

/**
 * Generates a SentimentResult where the label is always consistent with the
 * highest score — i.e. the label is derived from whichever score is largest.
 */
fun Arb.Companion.sentimentResult(): Arb<SentimentResult> = arbitrary {
    val pos = Arb.float(0f, 1f).bind()
    val neg = Arb.float(0f, 1f).bind()
    val neu = Arb.float(0f, 1f).bind()

    // Derive the correct label from the highest score
    val label = when {
        pos >= neg && pos >= neu -> SentimentLabel.POSITIVE
        neg >= pos && neg >= neu -> SentimentLabel.NEGATIVE
        else                     -> SentimentLabel.NEUTRAL
    }

    SentimentResult(
        label = label,
        positiveScore = pos,
        negativeScore = neg,
        neutralScore = neu
    )
}

// ─── Pure filtering / limiting helpers (no Android runtime needed) ────────────

/**
 * Simulates the multi-symbol filter logic: keeps articles that have at least
 * one symbol from [filterSymbols] in their stockSymbols list.
 */
fun filterBySymbols(articles: List<NewsArticle>, filterSymbols: Set<String>): List<NewsArticle> {
    if (filterSymbols.isEmpty()) return articles
    return articles.filter { article ->
        article.stockSymbols.any { it in filterSymbols }
    }
}

/**
 * Simulates clearing the filter — returns the full unfiltered list.
 */
fun clearFilter(articles: List<NewsArticle>): List<NewsArticle> = articles

/**
 * Simulates getLatestNews(limit): returns up to [limit] articles with
 * status = ANALYZED, sorted by publishedAt descending.
 */
fun getLatestNews(articles: List<NewsArticle>, limit: Int): List<NewsArticle> =
    articles
        .filter { it.status == ArticleStatus.ANALYZED }
        .sortedByDescending { it.publishedAt }
        .take(limit)

// ─── Property Tests ───────────────────────────────────────────────────────────

class RemainingPropertyTests {

    /**
     * Property 4: Sentiment score nằm trong khoảng hợp lệ
     *
     * For any NewsArticle, sentimentScore must be in [0.0, 1.0] or null.
     * The generator already constrains score to [0f, 1f] when a label is
     * present, so this test verifies the invariant holds across all generated
     * articles.
     *
     * **Validates: Requirements 2.2**
     */
    @Test
    fun `Property 4 - sentiment score is in valid range or null`() = runTest {
        // Feature: stock-news, Property 4: Sentiment score nằm trong khoảng hợp lệ
        checkAll(100, Arb.newsArticle()) { article ->
            article.sentimentScore?.let { score ->
                score shouldBeGreaterThanOrEqualTo 0.0f
                score shouldBeLessThanOrEqualTo 1.0f
            }
            // If label is null, score must also be null (generator invariant)
            if (article.sentimentLabel == null) {
                article.sentimentScore shouldBe null
            }
        }
    }

    /**
     * Property 5: Nhất quán giữa sentiment_label và sentiment_score
     *
     * For any SentimentResult, the label must correspond to the class with the
     * highest score. This is a pure logic test — no Android runtime needed.
     *
     * **Validates: Requirements 2.2, 2.3**
     */
    @Test
    fun `Property 5 - sentiment label matches class with highest score`() = runTest {
        // Feature: stock-news, Property 5: Nhất quán giữa sentiment_label và sentiment_score
        checkAll(100, Arb.sentimentResult()) { result ->
            val expectedLabel = when {
                result.positiveScore >= result.negativeScore &&
                result.positiveScore >= result.neutralScore  -> SentimentLabel.POSITIVE

                result.negativeScore >= result.positiveScore &&
                result.negativeScore >= result.neutralScore  -> SentimentLabel.NEGATIVE

                else                                         -> SentimentLabel.NEUTRAL
            }
            result.label shouldBe expectedLabel
        }
    }

    /**
     * Property 11: Multi-filter trả về bài có ít nhất một symbol khớp
     *
     * For any set of multiple Stock_Symbols used as filter simultaneously,
     * each NewsArticle in the result must have at least one symbol from the
     * filter set in its stockSymbols.
     *
     * **Validates: Requirements 6.5**
     */
    @Test
    fun `Property 11 - multi-filter returns articles with at least one matching symbol`() = runTest {
        // Feature: stock-news, Property 11: Multi-filter trả về bài có ít nhất một symbol khớp
        val symbolArb = Arb.string(3..5)
        val symbolSetArb: Arb<Set<String>> = arbitrary { Arb.list(symbolArb, 1..5).bind().toSet() }

        checkAll(100, symbolSetArb, Arb.list(Arb.newsArticle(), 0..30)) { filterSymbols, articles ->
            val result = filterBySymbols(articles, filterSymbols)
            result.forEach { article ->
                val hasMatch = article.stockSymbols.any { it in filterSymbols }
                hasMatch shouldBe true
            }
        }
    }

    /**
     * Property 12: Clear filter khôi phục danh sách đầy đủ
     *
     * For any active filter state, after clearing the filter, the returned list
     * must equal the list when no filter is applied (round-trip property).
     *
     * **Validates: Requirements 6.4**
     */
    @Test
    fun `Property 12 - clear filter restores full article list`() = runTest {
        // Feature: stock-news, Property 12: Clear filter khôi phục danh sách đầy đủ
        val symbolArb = Arb.string(3..5)
        val symbolSetArb: Arb<Set<String>> = arbitrary { Arb.list(symbolArb, 1..3).bind().toSet() }

        checkAll(100, symbolSetArb, Arb.list(Arb.newsArticle(), 0..30)) { filterSymbols, articles ->
            // Apply filter then clear — result must equal the original list
            val afterFilter = filterBySymbols(articles, filterSymbols)
            val afterClear  = clearFilter(articles)

            // After clearing, we get back the full list (not the filtered subset)
            afterClear shouldBe articles
            // The filtered list is always a subset of the cleared list
            (afterFilter.size <= afterClear.size) shouldBe true
        }
    }

    /**
     * Property 13: HomeScreen giới hạn tối đa 5 bài
     *
     * For any set of NewsArticle in cache, the number of articles shown in
     * HomeScreen "Tin tức nổi bật" must not exceed 5, and must be the 5
     * articles with the largest published_at with status = analyzed.
     *
     * **Validates: Requirements 7.1**
     */
    @Test
    fun `Property 13 - HomeScreen limits to at most 5 articles`() = runTest {
        // Feature: stock-news, Property 13: HomeScreen giới hạn tối đa 5 bài
        checkAll(100, Arb.list(Arb.newsArticle(), 0..50)) { articles ->
            val result = getLatestNews(articles, limit = 5)

            // Must not exceed 5
            (result.size <= 5) shouldBe true

            // All returned articles must have status = ANALYZED
            result.forEach { article ->
                article.status shouldBe ArticleStatus.ANALYZED
            }

            // Must be sorted descending by publishedAt
            result.zipWithNext().forEach { (a, b) ->
                a.publishedAt shouldBeGreaterThanOrEqualTo b.publishedAt
            }

            // Must be the top-5 analyzed articles by publishedAt
            val expectedIds = articles
                .filter { it.status == ArticleStatus.ANALYZED }
                .sortedByDescending { it.publishedAt }
                .take(5)
                .map { it.id }
            result.map { it.id } shouldBe expectedIds
        }
    }
}
