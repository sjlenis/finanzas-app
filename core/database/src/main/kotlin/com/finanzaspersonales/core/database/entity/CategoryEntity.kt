package com.finanzaspersonales.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.finanzaspersonales.core.model.CategoryClass

/** Categoría o subcategoría (si tiene [parentId]). */
@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("parent_id"), Index(value = ["parent_id", "name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    @ColumnInfo(name = "category_class") val categoryClass: CategoryClass,
    /** Gasto esencial: se usa para calcular el fondo de emergencia. */
    @ColumnInfo(name = "is_essential") val isEssential: Boolean = false,
    @ColumnInfo(name = "icon_key") val iconKey: String? = null,
    @ColumnInfo(name = "is_system") val isSystem: Boolean = false,
)
