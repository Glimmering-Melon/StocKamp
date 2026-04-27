package com.stockamp.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
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

// hilt connect
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun watchlistRepository(): WatchlistRepository
    fun marketRepository(): MarketRepository
}

// key
val watchlistDataKey = stringPreferencesKey("watchlist_data")

// main widget
class StocKampWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val displayData = prefs[watchlistDataKey] ?: "Nhấn Làm mới để tải dữ liệu..."

            Column(
                modifier = GlanceModifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // header + refresh button
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Theo dõi (Watchlist)",
                        style = TextStyle(fontWeight = FontWeight.Bold),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Button(
                        text = "Làm mới",
                        onClick = actionRunCallback<RefreshWatchlistAction>()
                    )
                }

                // list co phieu
                Text(
                    text = displayData,
                    modifier = GlanceModifier.padding(top = 8.dp)
                )
            }
        }
    }
}

// refresh
class RefreshWatchlistAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // lay repos tu hilt
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val watchlistRepo = entryPoint.watchlistRepository()
        val marketRepo = entryPoint.marketRepository()

        // lay watchlist
        val items = watchlistRepo.getAllWatchlistItems().firstOrNull() ?: emptyList()

        val formattedData = if (items.isEmpty()) {
            "Danh sách theo dõi trống."
        } else {
            items.map { item ->
                val latest = marketRepo.getLatestClose(item.symbol).getOrNull()
                val price = latest?.closePrice ?: "---"
                "${item.symbol}: $price VND"
            }.joinToString("\n")
        }

        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[watchlistDataKey] = formattedData
        }
        StocKampWidget().update(context, glanceId)
    }
}

// receiver
class StocKampWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StocKampWidget()
}