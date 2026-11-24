package com.finanzaspersonales.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.finanzaspersonales.core.model.Transaction
import com.finanzaspersonales.core.model.TransactionKind
import com.finanzaspersonales.core.model.TransactionStatus

fun TransactionKind.label(): String = when (this) {
    TransactionKind.INCOME -> "Ingreso"
    TransactionKind.EXPENSE -> "Gasto"
    TransactionKind.REFUND -> "Devolución"
    TransactionKind.FEE -> "Comisión"
    TransactionKind.TRANSFER -> "Transferencia"
    TransactionKind.WITHDRAWAL -> "Retiro"
    TransactionKind.LOAN_GIVEN -> "Préstamo realizado"
    TransactionKind.LOAN_RECEIVED -> "Préstamo recibido"
    TransactionKind.LOAN_REPAYMENT -> "Abono a préstamo"
    TransactionKind.ADJUSTMENT -> "Ajuste"
}

/** Fila de un movimiento: descripción, tipo · categoría · cuenta, y el valor con color. */
@Composable
fun TransactionItem(transaction: Transaction, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val details = buildList {
        add(transaction.kind.label())
        transaction.categoryName?.let { add(it) }
        add(transaction.accountName)
        if (transaction.status == TransactionStatus.PENDING) add("Por confirmar")
    }.joinToString(" · ")
    // Transferencias y ajustes no son ingreso ni gasto: se muestran en color neutro.
    val tone = when (transaction.kind) {
        TransactionKind.TRANSFER, TransactionKind.ADJUSTMENT -> MoneyTone.Neutral
        else -> MoneyTone.Signed
    }
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        headlineContent = { Text(transaction.description, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = { MoneyText(transaction.amount, tone = tone) },
    )
}
