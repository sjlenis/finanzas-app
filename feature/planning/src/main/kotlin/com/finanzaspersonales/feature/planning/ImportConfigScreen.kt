package com.finanzaspersonales.feature.planning

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.data.ConfigImporter
import com.finanzaspersonales.core.data.ConfigPlan
import com.finanzaspersonales.core.designsystem.component.FinanceCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ImportState(
    val plan: ConfigPlan? = null,
    val working: Boolean = false,
    val result: List<String>? = null,
    val error: String? = null,
)

@HiltViewModel
class ImportConfigViewModel @Inject constructor(private val importer: ConfigImporter) : ViewModel() {
    private val _state = MutableStateFlow(ImportState())
    val state = _state.asStateFlow()

    /** Lee el archivo elegido. Solo se lee lo que eliges; nada sale del teléfono. */
    fun load(context: Context, uri: Uri) = viewModelScope.launch {
        val text = runCatching {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readNBytesCompat(MAX_BYTES + 1)
                    require(bytes.size <= MAX_BYTES) { "El archivo es demasiado grande" }
                    String(bytes, Charsets.UTF_8)
                } ?: error("No pude abrir el archivo")
            }
        }
        text.mapCatching { ConfigPlan.parse(it) }
            .onSuccess { _state.value = ImportState(plan = it) }
            .onFailure { _state.value = ImportState(error = it.message ?: "No pude leer el archivo") }
    }

    fun apply() = viewModelScope.launch {
        val plan = _state.value.plan ?: return@launch
        _state.value = _state.value.copy(working = true)
        runCatching { importer.apply(plan) }
            .onSuccess { _state.value = ImportState(result = it) }
            .onFailure { _state.value = _state.value.copy(working = false, error = it.message ?: "No se pudo aplicar") }
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    private companion object {
        const val MAX_BYTES = 200_000
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

@Composable
fun ImportConfigRoute(onBack: () -> Unit, viewModel: ImportConfigViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.load(context, it) }
    }

    PlanningScaffold("Importar mi configuración", onBack) {
        item {
            FinanceCard(title = "Cómo funciona") {
                Note(
                    "Elige el archivo de configuración (.json). Antes de aplicar te muestro todo lo que va a cambiar. " +
                        "No se borra nada: lo que ya existe con el mismo nombre se actualiza y lo demás se crea. " +
                        "Los saldos se cuadran con un ajuste, que no cuenta como ingreso ni gasto.",
                )
                OutlinedButton(onClick = { pick.pickFile("application/json", "text/plain", "application/octet-stream") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Elegir archivo")
                }
            }
        }
        state.plan?.let { plan ->
            item {
                FinanceCard(title = "Esto se va a aplicar") {
                    plan.summary().forEach { Text("• $it") }
                    Button(onClick = viewModel::apply, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.working) "Aplicando…" else "Aplicar")
                    }
                }
            }
        }
        state.result?.let { lines ->
            item {
                FinanceCard(title = "Listo") {
                    lines.forEach { Text("✓ $it") }
                    Note("Ya puedes borrar el archivo de tu teléfono.")
                }
            }
        }
    }
    state.error?.let { MessageDialog(it, viewModel::clearError) }
}

private fun androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Uri?>.pickFile(vararg types: String) = launch(kotlin.arrayOf(*types))
