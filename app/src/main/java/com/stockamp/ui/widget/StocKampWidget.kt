package com.stockamp.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.stockamp.data.market.MarketRepository
import com.stockamp.data.repository.WatchlistRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.firstOrNull

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun watchlistRepository(): WatchlistRepository
    fun marketRepository(): MarketRepository
}

val watchlistDataKey = stringPreferencesKey("watchlist_data")

// --- HÀM TIỆN ÍCH: VẼ BIỂU ĐỒ MINI (SPARKLINE) BẰNG CANVAS ---
fun createSparklineBitmap(prices: List<Float>, isPositive: Boolean): Bitmap {
    val width = 200
    val height = 80
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Chọn màu xanh lá nếu tăng, đỏ nếu giảm
    val lineColor = if (isPositive) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#F44336")

    val paint = Paint().apply {
        color = lineColor
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    if (prices.isEmpty() || prices.size == 1) return bitmap

    val max = prices.maxOrNull() ?: 1f
    val min = prices.minOrNull() ?: 0f
    val range = if (max == min) 1f else max - min
    val path = Path()

    val stepX = width.toFloat() / (prices.size - 1)
    prices.forEachIndexed { index, price ->
        val x = index * stepX
        // Tính toán tọa độ Y (đảo ngược vì trục Y của Canvas đi từ trên xuống)
        val y = height - (((price - min) / range) * (height - 10f) + 5f)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }

    canvas.drawPath(path, paint)
    return bitmap
}

class StocKampWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val rawData = prefs[watchlistDataKey] ?: ""

            val widgetBackground = ColorProvider(day = Color.White, night = Color(0xFF1E1E1E))
            val textColor = ColorProvider(day = Color.Black, night = Color.White)
            val dividerColor = ColorProvider(day = Color.LightGray, night = Color.DarkGray)

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .appWidgetBackground()
                    .background(widgetBackground)
                    .padding(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // HEADER VÀ NÚT LÀM MỚI
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Theo dõi",
                        style = TextStyle(fontWeight = FontWeight.Bold, color = textColor, fontSize = 18.sp),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Button(
                        text = "Làm mới",
                        onClick = actionRunCallback<RefreshWatchlistAction>()
                    )
                }

                if (rawData.isEmpty() || rawData == "empty") {
                    Text(text = "Nhấn Làm mới để tải dữ liệu...", style = TextStyle(color = textColor))
                } else {
                    val stockItems = rawData.split(";")
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(stockItems) { itemStr ->
                            // Parse cấu trúc: Symbol|Price|Change|p1,p2,p3...
                            val parts = itemStr.split("|")
                            if (parts.size == 4) {
                                val sym = parts[0]
                                val price = parts[1]
                                val change = parts[2].toFloatOrNull() ?: 0f
                                val historyStr = parts[3]

                                val isPositive = change >= 0
                                val changeColor = ColorProvider(
                                    day = if (isPositive) Color(0xFF2E7D32) else Color(0xFFC62828),
                                    night = if (isPositive) Color(0xFF4CAF50) else Color(0xFFF44336)
                                )

                                // Parse chuỗi lịch sử thành mảng Float
                                val historyPrices = historyStr.split(",")
                                    .mapNotNull { it.toFloatOrNull() }

                                // Vẽ biểu đồ mini!
                                val sparklineBitmap = createSparklineBitmap(historyPrices, isPositive)

                                Column(modifier = GlanceModifier.fillMaxWidth()) {
                                    Row(
                                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 1. Mã cổ phiếu
                                        Text(
                                            text = sym,
                                            style = TextStyle(fontWeight = FontWeight.Bold, color = textColor, fontSize = 16.sp),
                                            modifier = GlanceModifier.width(60.dp)
                                        )

                                        // 2. Biểu đồ Mini Graph ở giữa
                                        Image(
                                            provider = ImageProvider(sparklineBitmap),
                                            contentDescription = "Chart",
                                            modifier = GlanceModifier.defaultWeight().height(30.dp),
                                            contentScale = ContentScale.Fit
                                        )

                                        // 3. Giá tiền và % tăng giảm bên phải
                                        Column(horizontalAlignment = Alignment.End, modifier = GlanceModifier.width(80.dp)) {
                                            Text(
                                                text = price,
                                                style = TextStyle(fontWeight = FontWeight.Medium, color = textColor, fontSize = 15.sp)
                                            )
                                            Text(
                                                text = if (isPositive) "+$change" else "$change",
                                                style = TextStyle(color = changeColor, fontSize = 13.sp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(dividerColor))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class RefreshWatchlistAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val watchlistRepo = entryPoint.watchlistRepository()
        val marketRepo = entryPoint.marketRepository()

        val items = watchlistRepo.getAllWatchlistItems().firstOrNull() ?: emptyList()

        val formattedData = if (items.isEmpty()) {
            "empty"
        } else {
            items.mapNotNull { item ->
                // Lấy giá mới nhất
                val latest = marketRepo.getLatestClose(item.symbol).getOrNull()
                val price = latest?.close ?: 0.0

                // Lấy lịch sử giá (ví dụ khung 1D) để vẽ biểu đồ
                val history = marketRepo.getOhlcv(item.symbol, "1D").getOrNull() ?: emptyList()
                if (history.isNotEmpty()) {
                    // Lấy 20 điểm giá đóng cửa gần nhất (Ép kiểu Double sang Float để vẽ đồ họa)
                    val closes = history.takeLast(20).map { it.close.toFloat() }

                    val firstPrice = closes.firstOrNull() ?: 0f
                    val lastPrice = closes.lastOrNull() ?: 0f
                    // Tính mức độ thay đổi
                    val change = lastPrice - firstPrice

                    // Gói thành chuỗi: Symbol | Price | Change | p1,p2,p3...
                    val historyStr = closes.joinToString(",")
                    "${item.symbol}|$price|%.2f".format(change) + "|$historyStr"
                } else {
                    null
                }
            }.joinToString(";")
        }

        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[watchlistDataKey] = formattedData
        }
        StocKampWidget().update(context, glanceId)
    }
}

class StocKampWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StocKampWidget()
}