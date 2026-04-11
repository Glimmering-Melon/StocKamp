package com.stockamp.ui.news

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stockamp.data.model.NewsArticle
import com.stockamp.data.model.toVietnamDisplayString
import com.stockamp.ui.theme.AccentBlue

@Composable
fun NewsSection(
    articles: List<NewsArticle>,
    title: String,
    onSeeAllClick: (() -> Unit)? = null,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (onSeeAllClick != null) {
                TextButton(onClick = onSeeAllClick) {
                    Text("Xem tất cả", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        when {
            isLoading -> {
                repeat(3) {
                    NewsSkeleton(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
            }
            articles.isEmpty() -> {
                Text(
                    text = "Chưa có tin tức liên quan",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            else -> {
                articles.forEach { article ->
                    NewsSectionItem(
                        article = article,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(article.url))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NewsSectionItem(
    article: NewsArticle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = article.sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentBlue,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = article.publishedAt.toVietnamDisplayString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = article.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            SentimentChip(
                label = article.sentimentLabel,
                score = article.sentimentScore,
                status = article.status
            )
        }
    }
}

@Composable
private fun NewsSkeleton(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .height(12.dp)
                    .padding(bottom = 4.dp)
            )
            Box(modifier = Modifier.fillMaxWidth().height(14.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.fillMaxWidth(0.7f).height(14.dp))
        }
    }
}
