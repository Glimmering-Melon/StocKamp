package com.stockamp.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stockamp.data.sync.SyncErrorLog
import com.stockamp.data.sync.SyncState
import com.stockamp.data.sync.SyncStatus
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dialog showing detailed sync status and recent error logs.
 *
 * Requirements: 15.4, 15.5
 */
@Composable
fun SyncErrorDialog(
    syncStatus: SyncStatus,
    errorLogs: List<SyncErrorLog>,
    onDismiss: () -> Unit
) {
    val recentErrors = remember(errorLogs) {
        errorLogs.filter { it.isUserFriendly }.takeLast(5).reversed()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Chi tiết đồng bộ",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Sync state
                SyncDetailRow(
                    label = "Trạng thái",
                    value = when (syncStatus.state) {
                        SyncState.IDLE -> "Đã đồng bộ"
                        SyncState.SYNCING -> "Đang đồng bộ"
                        SyncState.ERROR -> "Lỗi đồng bộ"
                        SyncState.OFFLINE -> "Ngoại tuyến"
                    }
                )

                // Pending operations
                SyncDetailRow(
                    label = "Thao tác chờ",
                    value = syncStatus.pendingCount.toString()
                )

                // Last sync timestamp
                SyncDetailRow(
                    label = "Lần cuối đồng bộ",
                    value = syncStatus.lastSync?.let { ts ->
                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ts))
                    } ?: "Chưa đồng bộ"
                )

                // Error message if present
                if (syncStatus.error != null) {
                    SyncDetailRow(label = "Lỗi", value = syncStatus.error)
                }

                // Recent error logs
                if (recentErrors.isNotEmpty()) {
                    Divider()
                    Text(
                        text = "Lỗi gần đây",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 200.dp)
                    ) {
                        items(recentErrors) { log ->
                            ErrorLogItem(log)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Đóng")
            }
        }
    )
}

@Composable
private fun SyncDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ErrorLogItem(log: SyncErrorLog) {
    val timeStr = remember(log.timestamp) {
        SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date(log.timestamp))
    }
    Column {
        Text(
            text = log.errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = "${log.operation} · $timeStr",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
