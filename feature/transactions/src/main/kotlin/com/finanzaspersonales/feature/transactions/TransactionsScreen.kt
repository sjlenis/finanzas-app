package com.finanzaspersonales.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finanzaspersonales.core.common.Period
import com.finanzaspersonales.core.designsystem.component.EmptyState
import com.finanzaspersonales.core.designsystem.component.MoneyText
import com.finanzaspersonales.core.designsystem.component.MoneyTone
import com.finanzaspersonales.core.designsystem.component.TransactionItem
import com.finanzaspersonales.core.designsystem.component.formatLongDate

@Composable
fun TransactionsRoute(
    onAdd: () -> Unit,
    onOpen: (transactionId: Long) -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TransactionsScreen(
        state = state,
        onPeriod = viewModel::setPeriod,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onAccount = viewModel::setAccount,
        onCategory = viewModel::setCategory,
        onAdd = onAdd,
        onOpen = onOpen,
    )
}

@Composable
fun TransactionsScreen(
    state: TransactionsUiState,
    onPeriod: (Period) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAccount: (Long?) -> Unit,
    onCategory: (Long?) -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Filters(state, onPeriod, onPrevious, onNext, onAccount, onCategory)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Ingresos", style = MaterialTheme.typography.labelMedium)
                    MoneyText(state.income, tone = MoneyTone.Income)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Gastos", style = MaterialTheme.typography.labelMedium)
                    MoneyText(state.expense, tone = MoneyTone.Expense)
                }
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.groups.isEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    title = "Sin movimientos",
                    message = "No hay movimientos con estos filtros. Toca \"Registrar\" para agregar uno.",
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    state.groups.forEach { group ->
                        item(key = "h${group.date}") {
                            Text(
                                formatLongDate(group.date),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(group.items, key = { it.id }) { tx ->
                            TransactionItem(tx, onClick = { onOpen(tx.id) })
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Registrar") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun Filters(
    state: TransactionsUiState,
    onPeriod: (Period) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAccount: (Long?) -> Unit,
    onCategory: (Long?) -> Unit,
) {
    val f = state.filters
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Period.entries.forEachIndexed { index, period ->
                SegmentedButton(
                    selected = f.period == period,
                    onClick = { onPeriod(period) },
                    shape = SegmentedButtonDefaults.itemShape(index, Period.entries.size),
                ) { Text(period.label, maxLines = 1) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Periodo anterior") }
            Text(
                periodLabel(f.period, f.anchor, state.today),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = onNext, enabled = state.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Periodo siguiente")
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterMenu(
                allLabel = "Todas las cuentas",
                selectedLabel = state.accounts.firstOrNull { it.id == f.accountId }?.name,
                options = state.accounts.map { it.id to it.name },
                onSelect = onAccount,
            )
            FilterMenu(
                allLabel = "Todas las categorías",
                selectedLabel = state.categories.firstOrNull { it.id == f.categoryId }?.name,
                // Solo categorías principales: el filtro incluye sus subcategorías.
                options = state.categories.filter { it.parentId == null }.map { it.id to it.name },
                onSelect = onCategory,
            )
        }
    }
}

@Composable
private fun FilterMenu(
    allLabel: String,
    selectedLabel: String?,
    options: List<Pair<Long, String>>,
    onSelect: (Long?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selectedLabel != null,
            onClick = { open = true },
            label = { Text(selectedLabel ?: allLabel) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { onSelect(null); open = false })
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); open = false })
            }
        }
    }
}
