package com.stockamp.news

import com.stockamp.data.model.SentimentLabel
import com.stockamp.ui.news.formatSentimentScore
import com.stockamp.ui.news.sentimentColor
import com.stockamp.ui.theme.AccentGreen
import com.stockamp.ui.theme.AccentRed
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.float
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SentimentChipPropertyTest {

    /**
     * Property 8: Màu sắc SentimentChip tương ứng đúng với label
     *
     * For any NewsArticle with any sentiment_label, the color of SentimentChip
     * must correspond: POSITIVE → AccentGreen, NEGATIVE → AccentRed,
     * NEUTRAL → null (resolved from MaterialTheme.colorScheme.onSurfaceVariant).
     * No label should display the wrong color.
     *
     * **Validates: Requirements 5.1, 5.2, 5.3, 7.4**
     */
    @Test
    fun `Property 8 - SentimentChip color matches label`() = runTest {
        // Feature: stock-news, Property 8: Màu sắc SentimentChip tương ứng đúng với label
        checkAll(100, Arb.element(SentimentLabel.entries)) { label ->
            val color = sentimentColor(label)
            when (label) {
                SentimentLabel.POSITIVE -> color shouldBe AccentGreen
                SentimentLabel.NEGATIVE -> color shouldBe AccentRed
                SentimentLabel.NEUTRAL  -> color shouldBe null  // resolved from MaterialTheme at call site
            }
        }
    }

    /**
     * Property 8 (extended): POSITIVE and NEGATIVE never share the same color,
     * and NEUTRAL is distinct from both.
     *
     * **Validates: Requirements 5.1, 5.2, 5.3**
     */
    @Test
    fun `Property 8 - POSITIVE and NEGATIVE have distinct colors`() = runTest {
        // Feature: stock-news, Property 8: Màu sắc SentimentChip tương ứng đúng với label
        val positiveColor = sentimentColor(SentimentLabel.POSITIVE)
        val negativeColor = sentimentColor(SentimentLabel.NEGATIVE)
        val neutralColor  = sentimentColor(SentimentLabel.NEUTRAL)

        positiveColor shouldNotBe negativeColor
        positiveColor shouldNotBe neutralColor  // null != AccentGreen
        negativeColor shouldNotBe neutralColor  // null != AccentRed
    }

    /**
     * Property 9: Định dạng phần trăm sentiment score
     *
     * For any sentiment_score in [0.0, 1.0], the display string must be a valid
     * integer percentage (e.g. score=0.87 → "87%", score=0.0 → "0%",
     * score=1.0 → "100%"). No value outside "0%" to "100%" is allowed.
     *
     * **Validates: Requirements 5.4**
     */
    @Test
    fun `Property 9 - sentiment score formatted as integer percentage`() = runTest {
        // Feature: stock-news, Property 9: Định dạng phần trăm sentiment score
        checkAll(100, Arb.float(0f, 1f)) { score ->
            val formatted = formatSentimentScore(score)

            // Must end with '%'
            formatted shouldEndWith "%"

            // Strip '%' and parse as integer
            val numericPart = formatted.dropLast(1)
            val percent = numericPart.toInt()

            // Must be in [0, 100]
            percent shouldBeGreaterThanOrEqualTo 0
            percent shouldBeLessThanOrEqualTo 100
        }
    }

    /**
     * Property 9 (spot checks): Verify exact expected values for known inputs.
     *
     * **Validates: Requirements 5.4**
     */
    @Test
    fun `Property 9 - spot check known score values`() {
        // Feature: stock-news, Property 9: Định dạng phần trăm sentiment score
        formatSentimentScore(0.0f)  shouldBe "0%"
        formatSentimentScore(1.0f)  shouldBe "100%"
        formatSentimentScore(0.87f) shouldBe "87%"
        formatSentimentScore(0.5f)  shouldBe "50%"
    }
}
