package com.finanzaspersonales.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.finanzaspersonales.core.database.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("SELECT * FROM categories ORDER BY parent_id IS NOT NULL, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE parent_id IS NULL AND name = :name LIMIT 1")
    suspend fun findTopLevelByName(name: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories WHERE name = :name COLLATE NOCASE AND ((:parentId IS NULL AND parent_id IS NULL) OR parent_id = :parentId)")
    suspend fun countByName(name: String, parentId: Long?): Int
}
