package com.artmondo.algomodo.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY createdAt DESC")
    fun getAllPresets(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE id = :id")
    suspend fun getPreset(id: String): PresetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: PresetEntity)

    @Delete
    suspend fun deletePreset(preset: PresetEntity)

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM presets")
    suspend fun getAllPresetsSync(): List<PresetEntity>
}
