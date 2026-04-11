package com.stockamp.ui.news

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stockamp.data.model.ArticleStatus
import com.stockamp.data.model.SentimentLabel
import com.stockamp.ui.theme.AccentGreen
import com.stockamp.ui.theme.AccentRed

/**
 * Maps a [SentimentLabel] to its display color.
 * Extracted as a pure function so it can be tested without Compose rendering.
 *
 * POSITIVE → AccentGreen
 * NEGATIVE → AccentRed
 * NEUTRAL  → null (caller should use MaterialTheme.colorScheme.onSurfaceVariant)
 */
fun sentimentColor(label: SentimentLabel): Color? = when (label) {
    SentimentLabel.POSITIVE -> AccentGreen
    SentimentLabel.NEGATIVE -> AccentRed
    SentimentLabel.NEUTRAL -> null  // resolved from MaterialTheme at call site
}

/**
 * Formats a sentiment score in [0.0, 1.0] as an integer percentage string.
 * e.g. 0.87 → "87%", 0.0 → "0%", 1.0 → "100%"
 */
fun formatSentimentScore(score: Float): String = "${(score * 100).toInt()}%"

@Composable
fun SentimentChip(
    label: SentimentLabel?,
    score: Float?,
    status: ArticleStatus = ArticleStatus.ANALYZED,
    modifier: Modifier = Modifier
) {
    if (status == ArticleStatus.PENDING_ANALYSIS) {
        Text(
            text = "Đang phân tích...",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = modifier
        )
        return
    }

    if (label == null) return

    val color = sentimentColor(label) ?: MaterialTheme.colorScheme.onSurfaceVariant
    val labelText = when (label) {
        SentimentLabel.POSITIVE -> "Tích cực"
        SentimentLabel.NEGATIVE -> "Tiêu cực"
        SentimentLabel.NEUTRAL -> "Trung lập"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = labelText,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        if (score != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = formatSentimentScore(score),
                color = color,
                fontSize = 11.sp
            )
        }
    }
}
