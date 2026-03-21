package com.stockamp.ui.sync

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stockamp.data.sync.SyncState
import com.stockamp.data.sync.SyncStatus
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Compact sync status chip for use in screen headers/toolbars.
 *
 * Requirements: 10.5, 15.1, 15.2, 15.3
 */
@Composable
fun SyncStatusIndicator(
    syncStatus: SyncStatus,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val (dotColor, label) = when (syncStatus.state) {
        SyncState.IDLE -> Color(0xFF4CAF50) to buildIdleLabel(syncStatus.lastSync)
        SyncState.SYNCING -> Color(0xFF2196F3) to buildSyncingLabel(syncStatus.pendingCount)
        SyncState.ERROR -> Color(0xFFF44336) to "Lỗi đồng bộ"
        SyncState.OFFLINE -> Color(0xFF9E9E9E) to "Ngoại tuyến"
    }

    // Spinning animation for SYNCING state
    val infiniteTransition = rememberInfiniteTransition(label = "sync_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing)
        ),
        label = "rotation"
    )

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (syncStatus.state == SyncState.SYNCING) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(8.dp)
                        .rotate(rotation),
                    color = dotColor,
                    strokeWidth = 1.5.dp
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }

            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * Overload that pulls state from [SyncViewModel] via Hilt and manages the error dialog.
 */
@Composable
fun SyncStatusIndicator(
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = hiltViewModel()
) {
    val syncStatus by viewModel.syncStatus.collectAsState()
    val errorLogs by viewModel.errorLogs.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    SyncStatusIndicator(
        syncStatus = syncStatus,
        onClick = {
            viewModel.refreshErrorLogs()
            showDialog = true
            onClick()
        },
        modifier = modifier
    )

    if (showDialog) {
        SyncErrorDialog(
            syncStatus = syncStatus,
            errorLogs = errorLogs,
            onDismiss = { showDialog = false }
        )
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun buildIdleLabel(lastSync: Long?): String {
    if (lastSync == null) return "Đã đồng bộ"
    val elapsed = System.currentTimeMillis() - lastSync
    return when {
        elapsed < TimeUnit.MINUTES.toMillis(1) -> "Đã đồng bộ vừa xong"
        elapsed < TimeUnit.HOURS.toMillis(1) -> {
            val mins = TimeUnit.MILLISECONDS.toMinutes(elapsed)
            "Đã đồng bộ $mins phút trước"
        }
        elapsed < TimeUnit.DAYS.toMillis(1) -> {
            val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
            "Đã đồng bộ $hours giờ trước"
        }
        else -> {
            val fmt = SimpleDateFormat("dd/MM", Locale.getDefault())
            "Đã đồng bộ ${fmt.format(Date(lastSync))}"
        }
    }
}

private fun buildSyncingLabel(pendingCount: Int): String =
    if (pendingCount > 0) "Đang đồng bộ... ($pendingCount)" else "Đang đồng bộ..."
