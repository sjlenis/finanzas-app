package com.finanzaspersonales.feature.planning

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finanzaspersonales.core.designsystem.component.AmountField
import com.finanzaspersonales.core.designsystem.component.Choice
import com.finanzaspersonales.core.designsystem.component.ChoiceField
import com.finanzaspersonales.core.designsystem.component.DateField
import com.finanzaspersonales.core.designsystem.component.FinanceCard
import com.finanzaspersonales.core.model.CategoryClass
import java.io.File

/**
 * Registrar desde una foto: capturas de la app del banco, comprobantes de pago o
 * facturas. La imagen se lee en el teléfono y no se guarda; antes de guardar muestra
 * lo que entendió para que lo revises.
 */
@Composable
fun ScanRoute(onBack: () -> Unit, viewModel: ScanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { viewModel.read(it) }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraFile?.let(::File)
        if (ok && file != null) viewModel.read(Uri.fromFile(file), temporary = file) else file?.delete()
    }
    fun openCamera() {
        val dir = File(context.cacheDir, "fotos").apply { mkdirs() }
        val file = File(dir, "foto-${System.currentTimeMillis()}.jpg")
        cameraFile = file.absolutePath
        takePicture.launch(FileProvider.getUriForFile(context, "${context.packageName}.fotos", file))
    }

    PlanningScaffold("Registrar desde una foto", onBack) {
        item {
            FinanceCard(title = "Elige una imagen") {
                Note(
                    "Sirve con capturas de la app de tu banco (información de un crédito o tarjeta), comprobantes de " +
                        "transferencia o pago y facturas. La foto se lee en tu teléfono y no se guarda.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Outlined.PhotoLibrary, null)
                        Text(" Galería")
                    }
                    OutlinedButton(onClick = ::openCamera) {
                        Icon(Icons.Outlined.PhotoCamera, null)
                        Text(" Tomar foto")
                    }
                }
            }
        }
        when (val s = state) {
            ScanState.Idle -> Unit
            ScanState.Reading -> item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Leyendo la imagen…")
                }
            }
            is ScanState.Failed -> item { FinanceCard(title = "No pude registrarla") { Note(s.message, error = true) } }
            is ScanState.DebtFound -> item {
                FinanceCard(title = if (s.existing != null) "Encontré tu crédito «${s.existing.name}»" else "Encontré un crédito") {
                    s.summary.forEach { Text(it) }
                    s.notes.forEach { Note(it) }
                    if (s.existing != null) Note("Ya tienes esta deuda registrada: al guardar se actualizan su saldo, cuota y tasa.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.saveDebt(s.form, s.existing) }, enabled = s.form.paymentDigits.isNotEmpty()) {
                            Text(if (s.existing != null) "Actualizar deuda" else "Guardar deuda")
                        }
                        OutlinedButton(onClick = { editing = true }) { Text("Revisar y editar") }
                    }
                    TextButton(onClick = viewModel::reset) { Text("Descartar") }
                }
            }
            is ScanState.MovementFound -> item {
                FinanceCard(title = if (s.form.isIncome) "Encontré un ingreso" else "Encontré un pago") {
                    s.summary.forEach { Text(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.saveMovement(s.form) }, enabled = s.form.accountId != null && s.form.amountDigits.isNotEmpty()) { Text("Guardar") }
                        OutlinedButton(onClick = { editing = true }) { Text("Editar") }
                    }
                    TextButton(onClick = viewModel::reset) { Text("Descartar") }
                }
            }
        }
    }

    val s = state
    if (editing && s is ScanState.DebtFound) {
        DebtFormDialog("Revisa la deuda", s.form, onDismiss = { editing = false }) { form ->
            editing = false
            viewModel.saveDebt(form, s.existing)
        }
    }
    if (editing && s is ScanState.MovementFound) {
        MovementDialog(
            initial = s.form,
            accounts = accounts.map { Choice(it.id, it.name) },
            categories = { income -> categories.filter { it.categoryClass == if (income) CategoryClass.INCOME else CategoryClass.EXPENSE }.map { Choice(it.id, it.name) } },
            onDismiss = { editing = false },
            onApply = { editing = false; viewModel.updateMovement(it) },
        )
    }
    message?.let { MessageDialog(it, viewModel::clearMessage) }
}

@Composable
private fun MovementDialog(
    initial: MovementForm,
    accounts: List<Choice<Long>>,
    categories: (Boolean) -> List<Choice<Long>>,
    onDismiss: () -> Unit,
    onApply: (MovementForm) -> Unit,
) {
    var f by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Revisa el movimiento") },
        text = {
            DialogColumn {
                ChoiceField("Tipo", f.isIncome, listOf(Choice(false, "Gasto"), Choice(true, "Ingreso")), { f = f.copy(isIncome = it, categoryId = null) })
                AmountField(f.amountDigits, { f = f.copy(amountDigits = it) }, label = "Valor")
                OutlinedTextField(f.description, { f = f.copy(description = it.take(60)) }, label = { Text("Descripción") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChoiceField("Categoría", f.categoryId ?: -1L, listOf(Choice(-1L, "Sin categoría")) + categories(f.isIncome), { f = f.copy(categoryId = it.takeIf { id -> id != -1L }) })
                ChoiceField("Cuenta", f.accountId, accounts, { f = f.copy(accountId = it) })
                DateField(f.date, { f = f.copy(date = it) }, maxDate = java.time.LocalDate.now())
            }
        },
        confirmButton = { TextButton(onClick = { onApply(f) }) { Text("Listo") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
