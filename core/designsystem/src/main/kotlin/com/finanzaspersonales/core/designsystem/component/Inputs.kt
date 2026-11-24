package com.finanzaspersonales.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.finanzaspersonales.core.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SpanishColombia: Locale = Locale.forLanguageTag("es-CO")
private val LongDate = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", SpanishColombia)
private val ShortDate = DateTimeFormatter.ofPattern("EEE d MMM", SpanishColombia)

fun formatLongDate(date: LocalDate): String = LongDate.format(date).replaceFirstChar { it.uppercase() }
fun formatShortDate(date: LocalDate): String = ShortDate.format(date).replaceFirstChar { it.uppercase() }

/** Solo los dígitos que escribe el usuario, como pesos enteros. */
fun digitsToMoney(digits: String): Money? = digits.toLongOrNull()?.takeIf { it > 0 }?.let { Money.ofPesos(it) }

/** Muestra "15000" como "15.000" mientras se escribe, sin cambiar el texto real. */
private object ThousandsTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val sb = StringBuilder()
        // Para cada posición del texto original, dónde queda en el texto mostrado.
        val originalToTransformed = IntArray(raw.length + 1)
        raw.forEachIndexed { i, c ->
            if (i > 0 && (raw.length - i) % 3 == 0) sb.append('.')
            originalToTransformed[i] = sb.length
            sb.append(c)
        }
        originalToTransformed[raw.length] = sb.length
        val out = sb.toString()
        return TransformedText(
            AnnotatedString(out),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = originalToTransformed[offset.coerceIn(0, raw.length)]
                override fun transformedToOriginal(offset: Int): Int {
                    val o = offset.coerceIn(0, out.length)
                    return out.substring(0, o).count { it != '.' }
                }
            },
        )
    }
}

/**
 * Campo de valor en pesos. Guarda solo dígitos ([digits]) y los muestra con puntos de miles.
 * Máximo 12 dígitos.
 */
@Composable
fun AmountField(
    digits: String,
    onDigitsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Valor",
    isError: Boolean = false,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = digits,
        onValueChange = { new -> onDigitsChange(new.filter { it.isDigit() }.trimStart('0').take(12)) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        prefix = { Text("$ ") },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
        visualTransformation = ThousandsTransformation,
    )
}

/** Selector de fecha con el calendario de Material 3. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Fecha",
    maxDate: LocalDate? = null,
) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    OutlinedTextField(
        value = formatLongDate(date),
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = "Elegir fecha") },
        modifier = modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null) { open = true },
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            disabledTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
            disabledBorderColor = androidx.compose.material3.MaterialTheme.colorScheme.outline,
            disabledLabelColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            disabledTrailingIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
    if (open) {
        // El DatePicker trabaja en milisegundos UTC a medianoche.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    maxDate == null || !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(maxDate)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    open = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancelar") } },
        ) {
            DatePicker(state = state)
        }
    }
}

/** Opción de una lista desplegable. */
data class Choice<T>(val value: T, val label: String)

/** Lista desplegable de solo lectura. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceField(
    label: String,
    selected: T?,
    choices: List<Choice<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Elegir",
    isError: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = choices.firstOrNull { it.value == selected }?.label ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            isError = isError,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        onSelect(choice.value)
                        expanded = false
                    },
                )
            }
        }
    }
}
