package com.finanzaspersonales.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.finanzaspersonales.core.data.CategoryRepository
import com.finanzaspersonales.core.designsystem.component.Choice
import com.finanzaspersonales.core.designsystem.component.ChoiceField
import com.finanzaspersonales.core.model.Category
import com.finanzaspersonales.core.model.CategoryClass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val repository: CategoryRepository,
) : ViewModel() {
    val categories: StateFlow<List<Category>> =
        repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun create(name: String, parentId: Long?, categoryClass: CategoryClass, essential: Boolean, onCreated: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.create(name, parentId, categoryClass, essential) }
                .onSuccess { onCreated() }
                .onFailure { _error.value = it.message }
        }
    }

    fun clearError() { _error.value = null }
}

private fun CategoryClass.label() = when (this) {
    CategoryClass.EXPENSE -> "Gasto"
    CategoryClass.INCOME -> "Ingreso"
    CategoryClass.NEUTRAL -> "Movimiento de dinero"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesRoute(onBack: () -> Unit, viewModel: CategoriesViewModel = hiltViewModel()) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categorías") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Nueva categoría") }
        },
    ) { padding ->
        val parents = categories.filter { it.parentId == null }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            CategoryClass.entries.forEach { cls ->
                val group = parents.filter { it.categoryClass == cls }
                if (group.isEmpty()) return@forEach
                item(key = "h$cls") {
                    Text(
                        cls.label(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                group.forEach { parent ->
                    item(key = parent.id) {
                        ListItem(
                            headlineContent = { Text(parent.name) },
                            supportingContent = if (parent.isEssential) ({ Text("Gasto esencial") }) else null,
                        )
                    }
                    items(categories.filter { it.parentId == parent.id }, key = { it.id }) { child ->
                        ListItem(
                            headlineContent = { Text(child.name) },
                            modifier = Modifier.padding(start = 32.dp),
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        NewCategoryDialog(
            parents = categories.filter { it.parentId == null && it.categoryClass != CategoryClass.NEUTRAL },
            onDismiss = { adding = false },
            onCreate = { name, parent, cls, essential -> viewModel.create(name, parent, cls, essential) { adding = false } },
        )
    }
    error?.let {
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text("No se pudo crear") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text("Entendido") } },
        )
    }
}

@Composable
private fun NewCategoryDialog(
    parents: List<Category>,
    onDismiss: () -> Unit,
    onCreate: (String, Long?, CategoryClass, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var parentId by remember { mutableStateOf<Long?>(null) }
    var cls by remember { mutableStateOf(CategoryClass.EXPENSE) }
    var essential by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva categoría") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Nombre") }, singleLine = true)
                ChoiceField(
                    label = "Dentro de (opcional)",
                    selected = parentId,
                    choices = listOf(Choice<Long?>(null, "Ninguna: categoría principal")) + parents.map { Choice<Long?>(it.id, it.name) },
                    onSelect = { parentId = it },
                )
                if (parentId == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = cls == CategoryClass.EXPENSE, onClick = { cls = CategoryClass.EXPENSE }, label = { Text("Gasto") })
                        FilterChip(selected = cls == CategoryClass.INCOME, onClick = { cls = CategoryClass.INCOME }, label = { Text("Ingreso") })
                    }
                    if (cls == CategoryClass.EXPENSE) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = essential, onCheckedChange = { essential = it })
                            Text("Gasto esencial (cuenta para el fondo de emergencia)")
                        }
                    }
                } else {
                    Text("La subcategoría usa el tipo de su categoría principal.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, parentId, cls, essential) }, enabled = name.isNotBlank()) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
