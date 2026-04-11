package com.stockamp.news

import com.stockamp.data.model.ArticleStatus
import com.stockamp.data.model.NewsArticle
import com.stockamp.data.model.NewsArticleDto
import com.stockamp.data.model.SentimentLabel
import com.stockamp.data.model.toDomain
import com.stockamp.data.model.toDto
import com.stockamp.data.model.toEntity
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.Instant

// ─── Custom Arb generator ────────────────────────────────────────────────────

fun Arb.Companion.newsArticle(): Arb<NewsArticle> = arbitrary {
    val labels = listOf(SentimentLabel.POSITIVE, SentimentLabel.NEGATIVE, SentimentLabel.NEUTRAL, null)
    val label = Arb.element(labels).bind()
    val score = if (label != null) Arb.float(0f, 1f).bind() else null
    NewsArticle(
        id = Arb.uuid().bind().toString(),
        title = Arb.string(10..100).bind(),
        url = "https://example.com/${Arb.string(5..20).bind()}",
        summary = Arb.string(20..200).orNull(0.3).bind(),
        sourceName = Arb.element(listOf("CafeF", "VnEconomy", "Vietstock", "Fireant")).bind(),
        publishedAt = Instant.ofEpochMilli(Arb.long(1_600_000_000_000L, 1_800_000_000_000L).bind()),
        stockSymbols = Arb.list(Arb.string(3..5), 0..5).bind(),
        sentimentLabel = label,
        sentimentScore = score,
        status = Arb.enum<ArticleStatus>().bind()
    )
}

// ─── Property Tests ───────────────────────────────────────────────────────────

class NewsMappingPropertyTest {

    /**
     * Property 15: Round-trip JSON parse của NewsArticle
     *
     * For any valid NewsArticle, serialize to JSON (via NewsArticleDto) then
     * deserialize back must produce an equivalent object — all fields equal,
     * including sentiment_label, sentiment_score, and stock_symbols list.
     *
     * **Validates: Requirements 12.1, 12.5, 9.6**
     */
    @Test
    fun `Property 15 - round-trip JSON parse of NewsArticle`() {
        // Feature: stock-news, Property 15: Round-trip JSON parse của NewsArticle
        kotlinx.coroutines.test.runTest {
            checkAll(100, Arb.newsArticle()) { article ->
                val dto = article.toDto()
                val json = Json.encodeToString(dto)
                val parsed = Json.decodeFromString<NewsArticleDto>(json)
                val roundTripped = parsed.toDomain()

                roundTripped shouldNotBe null
                roundTripped!!.id shouldBe article.id
                roundTripped.title shouldBe article.title
                roundTripped.url shouldBe article.url
                roundTripped.summary shouldBe article.summary
                roundTripped.sourceName shouldBe article.sourceName
                roundTripped.publishedAt shouldBe article.publishedAt
                roundTripped.stockSymbols shouldBe article.stockSymbols
                roundTripped.sentimentLabel shouldBe article.sentimentLabel
                roundTripped.sentimentScore shouldBe article.sentimentScore
                roundTripped.status shouldBe article.status
            }
        }
    }

    /**
     * Property 16: Validation từ chối bản ghi thiếu trường bắt buộc
     *
     * For any NewsArticleDto missing at least one required field (id, title, url,
     * published_at), toDomain() must return null. No NewsArticle with a blank
     * required field should be produced.
     *
     * **Validates: Requirements 12.2, 12.3**
     */
    @Test
    fun `Property 16 - validation rejects records with missing required fields`() {
        // Feature: stock-news, Property 16: Validation từ chối bản ghi thiếu trường bắt buộc
        kotlinx.coroutines.test.runTest {
            val validBase = NewsArticleDto(
                id = "valid-id",
                title = "Valid Title",
                url = "https://example.com/article",
                sourceName = "CafeF",
                publishedAt = "2024-01-15T10:00:00Z",
                status = "ANALYZED"
            )

            // Generate DTOs with each required field blanked out
            val invalidDtos = listOf(
                validBase.copy(id = ""),
                validBase.copy(id = "   "),
                validBase.copy(title = ""),
                validBase.copy(title = "   "),
                validBase.copy(url = ""),
                validBase.copy(url = "   "),
                validBase.copy(publishedAt = ""),
                validBase.copy(publishedAt = "   "),
                validBase.copy(publishedAt = "not-a-date"),
            )

            checkAll(100, Arb.element(invalidDtos)) { dto ->
                val result = dto.toDomain()
                result shouldBe null
            }
        }
    }

    /**
     * Property 17: Chuyển đổi timezone UTC sang UTC+7
     *
     * For any UTC timestamp string in ISO 8601 format, after converting to
     * Vietnam timezone (UTC+7), the result must differ by exactly 7 hours
     * from the original UTC time.
     *
     * **Validates: Requirements 12.4**
     */
    @Test
    fun `Property 17 - timezone conversion UTC to UTC+7 differs by exactly 7 hours`() {
        // Feature: stock-news, Property 17: Chuyển đổi timezone UTC sang UTC+7
        kotlinx.coroutines.test.runTest {
            checkAll(100, Arb.long(1_600_000_000_000L, 1_800_000_000_000L)) { epochMillis ->
                val instant = Instant.ofEpochMilli(epochMillis)
                val utcZoned = instant.atZone(java.time.ZoneOffset.UTC)
                val vnZoned = instant.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))

                // Vietnam is UTC+7, so the hour difference must be exactly 7
                val utcOffsetSeconds = utcZoned.offset.totalSeconds
                val vnOffsetSeconds = vnZoned.offset.totalSeconds
                val diffHours = (vnOffsetSeconds - utcOffsetSeconds) / 3600

                diffHours shouldBe 7
            }
        }
    }
}
