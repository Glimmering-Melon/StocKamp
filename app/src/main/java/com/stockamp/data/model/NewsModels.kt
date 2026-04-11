package com.stockamp.data.model

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class NewsArticle(
    val id: String,
    val title: String,
    val url: String,
    val summary: String?,
    val sourceName: String,
    val publishedAt: Instant,
    val stockSymbols: List<String>,
    val sentimentLabel: SentimentLabel?,
    val sentimentScore: Float?,
    val status: ArticleStatus
)

enum class SentimentLabel { POSITIVE, NEGATIVE, NEUTRAL }

enum class ArticleStatus { PENDING_ANALYSIS, ANALYZED, ANALYSIS_FAILED }

@Serializable
data class NewsArticleDto(
    val id: String,
    val title: String,
    val url: String,
    val summary: String? = null,
    @SerialName("source_name") val sourceName: String,
    @SerialName("published_at") val publishedAt: String,  // ISO 8601 UTC
    @SerialName("stock_symbols") val stockSymbols: List<String> = emptyList(),
    @SerialName("sentiment_label") val sentimentLabel: String? = null,
    @SerialName("sentiment_score") val sentimentScore: Float? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null
)
