package com.stockamp.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "chart_data",
    primaryKeys = ["symbol", "timeframe"]
)
data class ChartDataEntity(
    val symbol: String,
    val timeframe: String,
    @ColumnInfo(name = "data_json") val dataJson: String,
    @ColumnInfo(name = "cache_timestamp") val cacheTimestamp: Long,
    @ColumnInfo(name = "data_size") val dataSize: Int
)
