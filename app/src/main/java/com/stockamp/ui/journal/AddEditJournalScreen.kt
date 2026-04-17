package com.stockamp.ui.journal

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stockamp.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditJournalScreen(
    entryId: Long?,
    onNavigateBack: () -> Unit,
    viewModel: AddEditJournalViewModel = hiltViewModel()
) {
    LaunchedEffect(entryId) {
        if (entryId != null) {
            viewModel.loadEntry(entryId)
        }
    }

    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Xóa giao dịch") },
            text = { Text("Bạn có chắc muốn xóa giao dịch này không?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteEntry(onNavigateBack)
                    }
                ) {
                    Text("Xóa", color = AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Hủy")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (entryId != null) "Edit Trade" else "Add Trade",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (entryId != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, "Xóa", tint = AccentRed)
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Action Toggle
            Text(
                "Loại giao dịch",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = uiState.action == "BUY",
                    onClick = { viewModel.onActionChanged("BUY") },
                    label = { Text("MUA (BUY)", fontWeight = FontWeight.SemiBold) },
                    leadingIcon = if (uiState.action == "BUY") {
                        { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentGreen.copy(alpha = 0.15f),
                        selectedLabelColor = AccentGreen
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                FilterChip(
                    selected = uiState.action == "SELL",
                    onClick = { viewModel.onActionChanged("SELL") },
                    label = { Text("BÁN (SELL)", fontWeight = FontWeight.SemiBold) },
                    leadingIcon = if (uiState.action == "SELL") {
                        { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentRed.copy(alpha = 0.15f),
                        selectedLabelColor = AccentRed
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Symbol Dropdown
            Text(
                "Mã cổ phiếu",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            SymbolDropdownField(
                selectedSymbol = uiState.selectedSymbol,
                selectedSymbolName = uiState.selectedSymbolName,
                watchlistSymbols = uiState.watchlistSymbols.map { it.symbol to it.name },
                onSymbolSelected = { viewModel.onSymbolSelected(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Quantity
            OutlinedTextField(
                value = uiState.quantity,
                onValueChange = { viewModel.onQuantityChanged(it.filter { c -> c.isDigit() }) },
                label = { Text("Số lượng") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Date Picker
            DatePickerField(
                date = uiState.transactionDate,
                onDateChanged = { viewModel.onDateChanged(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Reference Price Card
            ReferencePriceCard(state = uiState.referencePriceState)

            Spacer(modifier = Modifier.height(16.dp))

            // Notes
            OutlinedTextField(
                value = uiState.notes,
                onValueChange = { viewModel.onNotesChanged(it) },
                label = { Text("Ghi chú") },
                placeholder = { Text("Lý do giao dịch, phân tích...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                shape = RoundedCornerShape(12.dp),
                maxLines = 5
            )

            // Save error
            if (uiState.saveError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.saveError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Save Button
            val isSaveEnabled = uiState.selectedSymbol.isNotBlank() &&
                    uiState.quantity.isNotBlank() &&
                    (uiState.quantity.toIntOrNull() ?: 0) > 0 &&
                    uiState.transactionDate.isNotBlank() &&
                    !uiState.isSaving

            Button(
                onClick = { viewModel.save(onNavigateBack) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = isSaveEnabled
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (entryId != null) "Cập nhật" else "Lưu giao dịch",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SymbolDropdownField(
    selectedSymbol: String,
    selectedSymbolName: String,
    watchlistSymbols: List<Pair<String, String>>,
    onSymbolSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = if (selectedSymbol.isNotBlank() && selectedSymbolName.isNotBlank()) {
        "$selectedSymbol - $selectedSymbolName"
    } else if (selectedSymbol.isNotBlank()) {
        selectedSymbol
    } else {
        ""
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            label = { Text("Mã cổ phiếu") },
            leadingIcon = { Icon(Icons.Default.Business, "Symbol") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (watchlistSymbols.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Danh sách theo dõi trống",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = { expanded = false }
                )
            } else {
                watchlistSymbols.forEach { (symbol, name) ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = symbol,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onSymbolSelected(symbol)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DatePickerField(
    date: String,
    onDateChanged: (String) -> Unit
) {
    val context = LocalContext.current
    val displayFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    val displayDate = runCatching {
        LocalDate.parse(date).format(displayFormatter)
    }.getOrElse { date }

    OutlinedTextField(
        value = displayDate,
        onValueChange = {},
        readOnly = true,
        label = { Text("Ngày giao dịch") },
        leadingIcon = { Icon(Icons.Default.DateRange, "Date") },
        trailingIcon = {
            IconButton(onClick = {
                val cal = Calendar.getInstance()
                runCatching { LocalDate.parse(date) }.getOrNull()?.let { ld ->
                    cal.set(ld.year, ld.monthValue - 1, ld.dayOfMonth)
                }
                DatePickerDialog(
                    context,
                    { _, year, month, dayOfMonth ->
                        val picked = LocalDate.of(year, month + 1, dayOfMonth)
                        onDateChanged(picked.toString())
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            }) {
                Icon(Icons.Default.Edit, "Chọn ngày")
            }
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        singleLine = true
    )
}

@Composable
private fun ReferencePriceCard(state: ReferencePriceState) {
    when (state) {
        is ReferencePriceState.Idle -> Unit
        is ReferencePriceState.Loading -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "Đang tải giá tham chiếu...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        is ReferencePriceState.Available -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = AccentGreen.copy(alpha = 0.08f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Giá tham chiếu",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = String.format("%,.0f đ", state.price * 1000),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccentGreen
                    )
                    Text(
                        text = "Ngày ${state.date}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        is ReferencePriceState.NotAvailable -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Không có dữ liệu giá cho ngày này",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
