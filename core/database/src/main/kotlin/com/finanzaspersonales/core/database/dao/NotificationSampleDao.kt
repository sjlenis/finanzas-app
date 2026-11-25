package com.finanzaspersonales.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.finanzaspersonales.core.database.entity.NotificationSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationSampleDao {
    @Insert
    suspend fun insert(sample: NotificationSampleEntity): Long

    @Query("SELECT * FROM notification_samples ORDER BY posted_at DESC")
    fun observeAll(): Flow<List<NotificationSampleEntity>>

    @Query("DELETE FROM notification_samples WHERE posted_at < :beforeEpochMs")
    suspend fun deleteOlderThan(beforeEpochMs: Long)

    @Query("DELETE FROM notification_samples")
    suspend fun deleteAll()
}
