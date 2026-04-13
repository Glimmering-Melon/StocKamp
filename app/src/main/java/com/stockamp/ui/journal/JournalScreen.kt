package com.stockamp.ui.journal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stockamp.data.model.JournalEntry
import com.stockamp.ui.sync.SyncStatusIndicator
import com.stockamp.ui.theme.*

private fun formatDate(isoDate: String): String {
    val parts = isoDate.split("-")
    return if (parts.size == 3) "${parts[2]}/${parts[1]}/${parts[0]}" else isoDate
}

private fun formatProfitPercent(value: Double): String {
    return if (value >= 0) "+${String.format("%.2f", value)}%" else "${String.format("%.2f", value)}%"
}

private fun formatProfitVnd(value: Double): String {
    return if (value >= 0) "+${String.format("%,.0f", value)} VNĐ" else "${String.format("%,.0f", value)} VNĐ"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    onAddEntry: () -> Unit,
    onEditEntry: (Long) -> Unit,
    viewModel: JournalViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddEntry,
                containerColor = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, "Add entry")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                AccentPurple.copy(alpha = 0.08f),
                                MaterialTheme.colorScheme.background
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(0f, 300f)
                        )
                    )
                    .padding(horizontal = 20.dp)
                    .statusBarsPadding()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Trading Journal",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SyncStatusIndicator()
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatCard(
                            modifier = Modifier.weight(1f),
                            title = "Total Trades",
                            value = "${uiState.totalTrades}",
                            icon = Icons.Default.Receipt,
                            color = AccentBlue
                        )
                        val pnlVnd = uiState.totalUnrealizedPnLVnd
                        val pnlColor = when {
                            pnlVnd > 0 -> AccentGreen
                            pnlVnd < 0 -> AccentRed
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val pnlText = if (pnlVnd == 0.0 && uiState.pnlMap.isEmpty()) {
                            "--"
                        } else {
                            String.format("%+,.0f VNĐ", pnlVnd)
                        }
                        StatCard(
                            modifier = Modifier.weight(1f),
                            title = "Lợi nhuận (P&L)",
                            value = pnlText,
                            icon = Icons.Default.TrendingUp,
                            color = pnlColor
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No trades yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tap + to record your first trade",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.entries, key = { it.id }) { entry ->
                        JournalEntryCard(
                            entry = entry,
                            pnl = uiState.pnlMap[entry.id],
                            onClick = { onEditEntry(entry.id) },
                            onDelete = { viewModel.deleteEntry(entry) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, modifier = Modifier.size(16.dp), tint = color)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalEntryCard(
    entry: JournalEntry,
    pnl: EntryPnL?,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val isBuy = entry.action == "BUY"
    val actionLabel = if (isBuy) "MUA" else "BÁN"
    val badgeColor = if (isBuy) AccentGreen else AccentRed

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${entry.symbol} · $actionLabel",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${entry.quantity} cổ phiếu",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatDate(entry.transactionDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (pnl == null || pnl.entryPrice == null) {
                Text(
                    "Không có dữ liệu giá",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val cardTitle = if (isBuy) {
                    "Tình trạng hiện tại"
                } else {
                    "Nếu giữ lại đến ${pnl.latestDate?.let { formatDate(it) } ?: "--"}"
                }
                PnLCard(
                    title = cardTitle,
                    profitPercent = pnl.profitPercent,
                    profitVnd = pnl.profitVnd,
                    latestDate = if (isBuy) pnl.latestDate else null
                )
            }

            if (entry.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    entry.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun PnLCard(
    title: String,
    profitPercent: Double?,
    profitVnd: Double?,
    latestDate: String?
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (profitPercent != null) {
                    val percentColor = when {
                        profitPercent > 0 -> AccentGreen
                        profitPercent < 0 -> AccentRed
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        formatProfitPercent(profitPercent),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = percentColor
                    )
                }
                if (profitVnd != null) {
                    val vndColor = when {
                        profitVnd > 0 -> AccentGreen
                        profitVnd < 0 -> AccentRed
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        formatProfitVnd(profitVnd),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = vndColor
                    )
                }
            }
            if (latestDate != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Cập nhật: ${formatDate(latestDate)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}