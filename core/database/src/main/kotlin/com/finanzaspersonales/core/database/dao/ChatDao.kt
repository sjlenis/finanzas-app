package com.finanzaspersonales.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.finanzaspersonales.core.database.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Update
    suspend fun update(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getById(id: Long): ChatMessageEntity?

    /** Los últimos [limit] mensajes, del más antiguo al más reciente. */
    @Query("SELECT * FROM (SELECT * FROM chat_messages ORDER BY id DESC LIMIT :limit) ORDER BY id")
    fun observeLatest(limit: Int): Flow<List<ChatMessageEntity>>

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}
