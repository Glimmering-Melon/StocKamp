package com.stockamp.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "news_articles")
data class NewsArticleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val summary: String?,
    @ColumnInfo(name = "source_name") val sourceName: String,
    @ColumnInfo(name = "published_at") val publishedAt: Long,       // epoch millis
    @ColumnInfo(name = "stock_symbols") val stockSymbols: String,   // JSON array string
    @ColumnInfo(name = "sentiment_label") val sentimentLabel: String?,
    @ColumnInfo(name = "sentiment_score") val sentimentScore: Float?,
    val status: String,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis()
)

class NewsConverters {
    @TypeConverter
    fun fromStockSymbols(value: String): List<String> = Json.decodeFromString(value)

    @TypeConverter
    fun toStockSymbols(list: List<String>): String = Json.encodeToString(list)
}
