package com.finanzaspersonales.core.data

import com.finanzaspersonales.core.database.FinanzasDatabase
import com.finanzaspersonales.core.database.entity.CategoryEntity
import com.finanzaspersonales.core.model.Category
import com.finanzaspersonales.core.model.CategoryClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val db: FinanzasDatabase,
) {
    private val dao get() = db.categoryDao()

    fun observeAll(): Flow<List<Category>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    /**
     * Crea una categoría propia. Una subcategoría hereda la clase (gasto/ingreso)
     * de su categoría padre. Solo se permite un nivel de subcategorías.
     */
    suspend fun create(name: String, parentId: Long?, categoryClass: CategoryClass, isEssential: Boolean): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Escribe un nombre" }
        require(clean.length <= 40) { "El nombre es muy largo" }
        val parent = parentId?.let { requireNotNull(dao.getById(it)) { "La categoría principal no existe" } }
        require(parent?.parentId == null) { "Solo se permite un nivel de subcategorías" }
        require(dao.countByName(clean, parentId) == 0) { "Ya existe una categoría con ese nombre" }
        return dao.insert(
            CategoryEntity(
                name = clean,
                parentId = parentId,
                categoryClass = parent?.categoryClass ?: categoryClass,
                isEssential = parent?.isEssential ?: isEssential,
                isSystem = false,
            ),
        )
    }
}
