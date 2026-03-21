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
import java.text.SimpleDateFormat
import java.util.*

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
            // Header
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
                        text = "Nhật ký giao dịch",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SyncStatusIndicator()
                    Spacer(modifier = Modifier.height(16.dp))

                    // Stats Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatCard(
                            modifier = Modifier.weight(1f),
                            title = "Tổng giao dịch",
                            value = "${uiState.totalTrades}",
                            icon = Icons.Default.Receipt,
                            color = AccentBlue
                        )
                        StatCard(
                            modifier = Modifier.weight(1f),
                            title = "Lợi nhuận (P&L)",
                            value = String.format("%,.0f", uiState.totalPnL),
                            icon = Icons.Default.TrendingUp,
                            color = if (uiState.totalPnL >= 0) AccentGreen else AccentRed
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
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Chưa có giao dịch nào",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Bấm + để ghi lại giao dịch đầu tiên",
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
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalEntryCard(
    entry: JournalEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val isBuy = entry.action == "BUY"
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AccentRed),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Delete,
                    "Delete",
                    modifier = Modifier.padding(end = 20.dp),
                    tint = MaterialTheme.colorScheme.onError
                )
            }
        },
        enableDismissFromStartToEnd = false
    ) {
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
                    // Action Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isBuy) AccentGreen.copy(alpha = 0.12f) else AccentRed.copy(alpha = 0.12f)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            entry.action,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isBuy) AccentGreen else AccentRed
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        entry.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        String.format("%,.0f VNĐ", entry.totalValue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${entry.quantity} cổ phiếu × ${String.format("%,.0f", entry.price)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        dateFormat.format(Date(entry.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

                if (entry.strategy.isNotBlank() || entry.emotion.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entry.strategy.isNotBlank()) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text(entry.strategy, style = MaterialTheme.typography.labelSmall) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                        if (entry.emotion.isNotBlank()) {
                            val emoji = when (entry.emotion) {
                                "confident" -> "😎"
                                "nervous" -> "😰"
                                else -> "😐"
                            }
                            SuggestionChip(
                                onClick = {},
                                label = { Text("$emoji ${entry.emotion}", style = MaterialTheme.typography.labelSmall) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
