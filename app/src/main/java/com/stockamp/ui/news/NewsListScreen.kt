package com.stockamp.ui.news

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsListScreen(
    initialSymbolFilter: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    viewModel: NewsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pagingItems = viewModel.newsPagingData.collectAsLazyPagingItems()

    LaunchedEffect(initialSymbolFilter) {
        if (!initialSymbolFilter.isNullOrBlank()) {
            viewModel.applyFilter(initialSymbolFilter)
        }
    }

    val activeFilters = (uiState as? NewsUiState.Success)?.activeFilters ?: emptyList()
    var filterInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tin tức", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, "Quay lại")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Filter bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = filterInput,
                    onValueChange = { filterInput = it },
                    placeholder = { Text("Tìm kiếm bài viết...") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (filterInput.isNotBlank()) {
                            viewModel.applyFilter(filterInput.trim())
                            filterInput = ""
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Tìm kiếm") }
            }

            // Active filter chips
            if (activeFilters.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    items(activeFilters) { symbol ->
                        InputChip(
                            selected = true,
                            onClick = {
                                viewModel.clearFilter()
                            },
                            label = { Text("Từ khoá: $symbol") },
                            trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                    item {
                        TextButton(onClick = { viewModel.clearFilter() }) {
                            Text("Xóa tất cả")
                        }
                    }
                }
            }

            // News list
            when {
                pagingItems.loadState.refresh is LoadState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                pagingItems.loadState.refresh is LoadState.Error && pagingItems.itemCount == 0 -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Không thể tải tin tức", style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { pagingItems.refresh() }) { Text("Thử lại") }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(pagingItems.itemCount) { index ->
                            val article = pagingItems[index] ?: return@items


                            NewsArticleCard(
                                article = article,
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(article.url))
                                    context.startActivity(intent)
                                },

                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        if (pagingItems.loadState.append is LoadState.Loading) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }
}
