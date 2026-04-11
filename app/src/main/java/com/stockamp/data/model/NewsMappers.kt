package com.stockamp.data.model

import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "NewsMappers"
private val VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh")

// Thin wrapper so unit tests can run without Android runtime
internal fun logWarning(tag: String, msg: String, e: Exception? = null) {
    try {
        if (e != null) Log.w(tag, msg, e) else Log.w(tag, msg)
    } catch (_: RuntimeException) {
        // android.util.Log not available in unit tests — fall back to stderr
        System.err.println("W/$tag: $msg${if (e != null) " — ${e.message}" else ""}")
    }
}

/**
 * Parse published_at ISO 8601 UTC string and convert to Vietnam timezone (UTC+7).
 * Returns null if the string is invalid.
 */
fun parsePublishedAt(isoString: String): Instant? {
    return try {
        Instant.parse(isoString)
    } catch (e: Exception) {
        logWarning(TAG, "Invalid published_at format: $isoString", e)
        null
    }
}

/**
 * Convert an Instant to ZonedDateTime in Vietnam timezone (UTC+7).
 */
fun Instant.toVietnamTime(): ZonedDateTime = this.atZone(VN_ZONE)

/**
 * Format an Instant as a display string in Vietnam timezone.
 * Example: "2024-01-15 17:00"
 */
fun Instant.toVietnamDisplayString(): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    return this.atZone(VN_ZONE).format(formatter)
}

/**
 * Map NewsArticleDto → NewsArticle domain model.
 * Returns null if any required field (id, title, url, published_at) is null or blank.
 * Logs a warning and returns null for invalid records.
 */
fun NewsArticleDto.toDomain(): NewsArticle? {
    if (id.isBlank()) {
        logWarning(TAG, "Skipping article: id is blank")
        return null
    }
    if (title.isBlank()) {
        logWarning(TAG, "Skipping article id=$id: title is blank")
        return null
    }
    if (url.isBlank()) {
        logWarning(TAG, "Skipping article id=$id: url is blank")
        return null
    }
    if (publishedAt.isBlank()) {
        logWarning(TAG, "Skipping article id=$id: published_at is blank")
        return null
    }

    val instant = parsePublishedAt(publishedAt) ?: run {
        logWarning(TAG, "Skipping article id=$id: cannot parse published_at=$publishedAt")
        return null
    }

    val label = sentimentLabel?.let {
        try {
            SentimentLabel.valueOf(it.uppercase())
        } catch (e: IllegalArgumentException) {
            logWarning(TAG, "Unknown sentiment_label=$it for article id=$id, treating as null")
            null
        }
    }

    val articleStatus = try {
        ArticleStatus.valueOf(status.uppercase().replace("-", "_"))
    } catch (e: IllegalArgumentException) {
        logWarning(TAG, "Unknown status=$status for article id=$id, defaulting to PENDING_ANALYSIS")
        ArticleStatus.PENDING_ANALYSIS
    }

    return NewsArticle(
        id = id,
        title = title,
        url = url,
        summary = summary,
        sourceName = sourceName,
        publishedAt = instant,
        stockSymbols = stockSymbols,
        sentimentLabel = label,
        sentimentScore = sentimentScore,
        status = articleStatus
    )
}

/**
 * Map NewsArticle domain model → NewsArticleEntity for Room storage.
 */
fun NewsArticle.toEntity(): NewsArticleEntity = NewsArticleEntity(
    id = id,
    title = title,
    url = url,
    summary = summary,
    sourceName = sourceName,
    publishedAt = publishedAt.toEpochMilli(),
    stockSymbols = Json.encodeToString(stockSymbols),
    sentimentLabel = sentimentLabel?.name,
    sentimentScore = sentimentScore,
    status = status.name,
    cachedAt = System.currentTimeMillis()
)

/**
 * Map NewsArticleEntity (Room) → NewsArticle domain model.
 */
fun NewsArticleEntity.toDomain(): NewsArticle = NewsArticle(
    id = id,
    title = title,
    url = url,
    summary = summary,
    sourceName = sourceName,
    publishedAt = Instant.ofEpochMilli(publishedAt),
    stockSymbols = try {
        Json.decodeFromString(stockSymbols)
    } catch (e: Exception) {
        logWarning(TAG, "Failed to parse stockSymbols for article id=$id", e)
        emptyList()
    },
    sentimentLabel = sentimentLabel?.let {
        try { SentimentLabel.valueOf(it) } catch (e: IllegalArgumentException) { null }
    },
    sentimentScore = sentimentScore,
    status = try {
        ArticleStatus.valueOf(status)
    } catch (e: IllegalArgumentException) {
        ArticleStatus.PENDING_ANALYSIS
    }
)

/**
 * Map NewsArticle domain model → NewsArticleDto for serialization.
 * Used in round-trip testing.
 */
fun NewsArticle.toDto(): NewsArticleDto = NewsArticleDto(
    id = id,
    title = title,
    url = url,
    summary = summary,
    sourceName = sourceName,
    publishedAt = publishedAt.toString(),  // ISO 8601 UTC
    stockSymbols = stockSymbols,
    sentimentLabel = sentimentLabel?.name,
    sentimentScore = sentimentScore,
    status = status.name
)
