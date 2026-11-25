package com.finanzaspersonales.feature.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.finanzaspersonales.core.designsystem.component.MoneyText
import com.finanzaspersonales.core.model.Money
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val esCO: Locale = Locale.forLanguageTag("es-CO")
private val monthFormat = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", esCO)

internal fun YearMonth.label(): String = format(monthFormat)

/** Valor en pesos sin centavos para los campos de texto. */
internal fun Money?.toDigits(): String = this?.takeIf { it.centavos > 0 }?.pesos?.toString().orEmpty()

internal fun Int.monthsLabel(): String = when {
    this < 12 -> if (this == 1) "1 mes" else "$this meses"
    this % 12 == 0 -> if (this == 12) "1 año" else "${this / 12} años"
    else -> "${this / 12} ${if (this / 12 == 1) "año" else "años"} y ${this % 12} ${if (this % 12 == 1) "mes" else "meses"}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanningScaffold(
    title: String,
    onBack: () -> Unit,
    fabLabel: String? = null,
    onFab: () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            )
        },
        floatingActionButton = {
            if (fabLabel != null) {
                ExtendedFloatingActionButton(onClick = onFab, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(fabLabel) })
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun Progress(percent: Int, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { (percent.coerceIn(0, 100)) / 100f },
        modifier = Modifier.fillMaxWidth(),
        color = color,
    )
}

@Composable
internal fun LabeledMoney(label: String, money: Money, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(money)
    }
}

@Composable
internal fun LabeledText(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun MessageDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Entendido") } },
    )
}

@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
internal fun DialogColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
