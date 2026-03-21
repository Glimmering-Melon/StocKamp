package com.stockamp.ui.journal

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stockamp.data.model.JournalEntry
import com.stockamp.data.repository.JournalRepository
import com.stockamp.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddEditJournalViewModel @Inject constructor(
    private val journalRepository: JournalRepository
) : ViewModel() {

    var entry by mutableStateOf<JournalEntry?>(null)
        private set

    fun loadEntry(id: Long?) {
        if (id == null) return
        viewModelScope.launch {
            entry = journalRepository.getEntryById(id)
        }
    }

    fun saveEntry(
        symbol: String,
        action: String,
        quantity: Int,
        price: Double,
        notes: String,
        emotion: String,
        strategy: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            val newEntry = JournalEntry(
                id = entry?.id ?: 0,
                symbol = symbol.uppercase(),
                action = action,
                quantity = quantity,
                price = price,
                totalValue = quantity * price,
                notes = notes,
                emotion = emotion,
                strategy = strategy
            )
            if (entry != null) {
                journalRepository.updateEntry(newEntry)
            } else {
                journalRepository.addEntry(newEntry)
            }
            onSuccess()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditJournalScreen(
    entryId: Long?,
    onNavigateBack: () -> Unit,
    viewModel: AddEditJournalViewModel = hiltViewModel()
) {
    LaunchedEffect(entryId) {
        viewModel.loadEntry(entryId)
    }

    val existingEntry = viewModel.entry
    var symbol by remember(existingEntry) { mutableStateOf(existingEntry?.symbol ?: "") }
    var action by remember(existingEntry) { mutableStateOf(existingEntry?.action ?: "BUY") }
    var quantity by remember(existingEntry) { mutableStateOf(existingEntry?.quantity?.toString() ?: "") }
    var price by remember(existingEntry) { mutableStateOf(existingEntry?.price?.toString() ?: "") }
    var notes by remember(existingEntry) { mutableStateOf(existingEntry?.notes ?: "") }
    var emotion by remember(existingEntry) { mutableStateOf(existingEntry?.emotion ?: "neutral") }
    var strategy by remember(existingEntry) { mutableStateOf(existingEntry?.strategy ?: "") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (entryId != null) "Sửa giao dịch" else "Thêm giao dịch",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
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
            Text("Loại giao dịch", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = action == "BUY",
                    onClick = { action = "BUY" },
                    label = { Text("MUA (BUY)", fontWeight = FontWeight.SemiBold) },
                    leadingIcon = if (action == "BUY") {{ Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }} else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentGreen.copy(alpha = 0.15f),
                        selectedLabelColor = AccentGreen
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                FilterChip(
                    selected = action == "SELL",
                    onClick = { action = "SELL" },
                    label = { Text("BÁN (SELL)", fontWeight = FontWeight.SemiBold) },
                    leadingIcon = if (action == "SELL") {{ Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }} else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentRed.copy(alpha = 0.15f),
                        selectedLabelColor = AccentRed
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Symbol
            OutlinedTextField(
                value = symbol,
                onValueChange = { symbol = it.uppercase() },
                label = { Text("Mã cổ phiếu") },
                leadingIcon = { Icon(Icons.Default.Business, "Symbol") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Quantity & Price
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it.filter { c -> c.isDigit() } },
                    label = { Text("Số lượng") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Giá (VNĐ)") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            // Total display
            val totalValue = (quantity.toIntOrNull() ?: 0) * (price.toDoubleOrNull() ?: 0.0)
            if (totalValue > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Tổng giá trị", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            String.format("%,.0f VNĐ", totalValue),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Strategy
            OutlinedTextField(
                value = strategy,
                onValueChange = { strategy = it },
                label = { Text("Chiến lược") },
                placeholder = { Text("VD: Swing Trade, Long-term Hold...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Emotion
            Text("Cảm xúc khi giao dịch", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("confident" to "😎 Tự tin", "neutral" to "😐 Bình thường", "nervous" to "😰 Lo lắng").forEach { (value, label) ->
                    FilterChip(
                        selected = emotion == value,
                        onClick = { emotion = value },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Notes
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Ghi chú") },
                placeholder = { Text("Lý do giao dịch, phân tích...") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                shape = RoundedCornerShape(12.dp),
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Save Button
            Button(
                onClick = {
                    val qty = quantity.toIntOrNull() ?: return@Button
                    val prc = price.toDoubleOrNull() ?: return@Button
                    if (symbol.isBlank()) return@Button
                    viewModel.saveEntry(symbol, action, qty, prc, notes, emotion, strategy, onNavigateBack)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = symbol.isNotBlank() && quantity.isNotBlank() && price.isNotBlank()
            ) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (entryId != null) "Cập nhật" else "Lưu giao dịch",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
